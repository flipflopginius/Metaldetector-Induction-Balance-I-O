// ====================================================================================
// METAL DETECTOR PROFESSIONALE - ESP32
// VERSIONE: OFFICINA MASTER v5.8-USB-ONLY
// ====================================================================================
#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_ADS1X15.h>
#include "driver/mcpwm.h"
#include "driver/timer.h"
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

void IRAM_ATTR isr_tx_ref();
void IRAM_ATTR isr_rx();

#define TIMER_DIVIDER   2
#undef TIMER_BASE_CLK
#define TIMER_BASE_CLK  80000000ULL
#define TIMER_FREQ_HZ   (TIMER_BASE_CLK / TIMER_DIVIDER)

Adafruit_ADS1115 ads;

// PIN
const int PIN_TX_MOSFET  = 18;
const int PIN_TX_REF_OUT = 19;
const int PIN_TX_REF_IN  = 35;
const int PIN_RX_PHASE   = 34;
const int PIN_LED_STATO  = 2;

// PARAMETRI
int   FREQUENZA_LAVORO   = 5000;
int   POTENZA_DUTY_CYCLE = 40;
const unsigned long SAMPLE_PERIOD_US         = 10000;
const int           BATCH_SIZE               = 5;
const int           PHASE_AVG_SAMPLES        = 4;
const int           PHASE_MIN_VALID          = 2;
const uint32_t      PHASE_COLLECT_TIMEOUT_US = 300;
const float         PHASE_EMA_ALPHA          = 0.18f;
const int           EMA_INIT_SAMPLES         = 8;
const int64_t       MIN_RX_INTERVAL_TICKS    = 80;
const float         FREQ_TOLERANCE_LOW       = 0.75f;
const float         FREQ_TOLERANCE_HIGH      = 1.30f;

// Buffer batch statico — zero heap nel hot path
#define SAMPLE_MAX_CHARS  18
#define TX_BUF_SIZE       (2 + BATCH_SIZE * SAMPLE_MAX_CHARS + 2)
static char txBuf[TX_BUF_SIZE];
static int  txOffset     = 0;
static int  batchCounter = 0;

// ── Stato globale ─────────────────────────────────────────────
volatile int64_t period_ticks     = 8000;
volatile int64_t min_period_ticks = 6000;
volatile int64_t max_period_ticks = 10400;
float livelloBase     = 0.0f;
float batteryVoltage  = 12.6f;
bool  isSimulated     = false;
bool  streamingActive = true;
bool  simMetalActive  = false;
int   simCounter      = 0;
float phase_degrees    = 0.0f;
float phase_offset     = 0.0f;
float phase_last_valid = 0.0f;
float phase_ema        = 0.0f;
bool  ema_initialized  = false;
int   ema_init_count   = 0;
float ema_init_sin     = 0.0f;
float ema_init_cos     = 0.0f;
volatile bool calibrationActive = false;
hw_timer_t* precisionTimer = nullptr;

// ── ISR helpers ───────────────────────────────────────────────
inline int64_t getTimerTick() { return (int64_t)timerRead(precisionTimer); }

volatile uint32_t isr_tx_hi    = 0;
volatile uint32_t isr_tx_lo    = 0;
volatile uint32_t isr_tx_hi2   = 0;
volatile bool     isr_tx_valid = false;

inline int64_t IRAM_ATTR readTxTickSafe() {
    uint32_t hi, lo, hi2;
    do { hi = isr_tx_hi; lo = isr_tx_lo; hi2 = isr_tx_hi2; } while (hi != hi2);
    return ((int64_t)hi << 32) | (int64_t)lo;
}

portMUX_TYPE isrMux = portMUX_INITIALIZER_UNLOCKED;
volatile int64_t isr_last_rx_tick = 0;

#define RX_QUEUE_SIZE 64
#define RX_QUEUE_MASK (RX_QUEUE_SIZE - 1)
volatile int64_t rx_queue[RX_QUEUE_SIZE];
volatile uint8_t rx_head = 0;
volatile uint8_t rx_tail = 0;

// ── ISR ───────────────────────────────────────────────────────
void IRAM_ATTR isr_tx_ref() {
    int64_t t  = getTimerTick();
    isr_tx_hi  = (uint32_t)((uint64_t)t >> 32);
    isr_tx_lo  = (uint32_t)((uint64_t)t & 0xFFFFFFFFULL);
    isr_tx_hi2 = isr_tx_hi;
    isr_tx_valid = true;
}

void IRAM_ATTR isr_rx() {
    int64_t now         = getTimerTick();
    int64_t delta_rx_rx = now - isr_last_rx_tick;
    if (delta_rx_rx < MIN_RX_INTERVAL_TICKS) return;
    
    portENTER_CRITICAL(&isrMux);
    bool    valid   = isr_tx_valid;
    int64_t tx_tick = 0;
    if (valid) tx_tick = readTxTickSafe();
    
    if (valid) {
        int64_t delta_tx_rx = now - tx_tick;
        if (delta_tx_rx >= 0 && delta_tx_rx <= period_ticks * 2) {
            if (delta_rx_rx >= min_period_ticks && delta_rx_rx <= max_period_ticks) {
                uint8_t next = (rx_head + 1) & RX_QUEUE_MASK;
                if (next != rx_tail) { rx_queue[rx_head] = delta_tx_rx; rx_head = next; }
            }
        }
    }
    isr_last_rx_tick = now;
    portEXIT_CRITICAL(&isrMux);
}

// ── Output verso host (solo USB) ──────────────────────────────
inline void sendToHost(const char* msg) {
    Serial.print(msg);
    Serial.write('\n');
}

inline void sendToHostRaw(const uint8_t* buf, size_t len) {
    Serial.write(buf, len);
}

// ── Hardware TX ───────────────────────────────────────────────
void aggiornaHardwareTX(int freq, int duty) {
    mcpwm_gpio_init(MCPWM_UNIT_0, MCPWM0A, PIN_TX_MOSFET);
    mcpwm_gpio_init(MCPWM_UNIT_0, MCPWM0B, PIN_TX_REF_OUT);
    
    mcpwm_config_t pwm_config;
    pwm_config.frequency    = freq;
    pwm_config.cmpr_a       = (float)duty;
    pwm_config.cmpr_b       = (float)duty;
    pwm_config.counter_mode = MCPWM_UP_COUNTER;
    pwm_config.duty_mode    = MCPWM_DUTY_MODE_0;
    
    mcpwm_init(MCPWM_UNIT_0, MCPWM_TIMER_0, &pwm_config);
    mcpwm_start(MCPWM_UNIT_0, MCPWM_TIMER_0);
    
    mcpwm_set_duty(MCPWM_UNIT_0, MCPWM_TIMER_0, MCPWM_OPR_A, duty);
    mcpwm_set_duty_type(MCPWM_UNIT_0, MCPWM_TIMER_0, MCPWM_OPR_A, MCPWM_DUTY_MODE_0);
    mcpwm_set_duty(MCPWM_UNIT_0, MCPWM_TIMER_0, MCPWM_OPR_B, duty);
    mcpwm_set_duty_type(MCPWM_UNIT_0, MCPWM_TIMER_0, MCPWM_OPR_B, MCPWM_DUTY_MODE_0);

    period_ticks     = (int64_t)(TIMER_FREQ_HZ / freq);
    min_period_ticks = (int64_t)(period_ticks * FREQ_TOLERANCE_LOW);
    max_period_ticks = (int64_t)(period_ticks * FREQ_TOLERANCE_HIGH);

    portENTER_CRITICAL(&isrMux);
    rx_head = rx_tail    = 0;
    isr_tx_valid         = false;
    isr_last_rx_tick     = 0;
    portEXIT_CRITICAL(&isrMux);

    char buf[64];
    snprintf(buf, sizeof(buf), "TX aggiornato: %dHz  period=%lld tick", freq, period_ticks);
    sendToHost(buf);
}

// ── Segnali ───────────────────────────────────────────────────
float leggiSegnaleRX() {
    if (isSimulated) {
        if (!simMetalActive && random(0, 100) == 0) simMetalActive = true;
        if (simMetalActive) {
            simCounter++;
            float s = livelloBase + sinf(simCounter * 0.2f) * 50.0f;
            if (simCounter > 30) { simMetalActive = false; simCounter = 0; }
            return s;
        }
        return livelloBase + (random(-200, 200) / 100.0f);
    }
    return (ads.readADC_SingleEnded(0) * 2048.0f) / 32767.0f;
}

void leggiFase() {
    if (isSimulated) {
        phase_degrees    = simMetalActive ? -155.0f : 0.0f;
        phase_last_valid = phase_degrees;
        if (!ema_initialized) {
            phase_ema = phase_degrees; ema_initialized = true;
            ema_init_count = 0; ema_init_sin = ema_init_cos = 0.0f;
        }
        return;
    }
    
    float sin_acc = 0.0f, cos_acc = 0.0f;
    int   valid   = 0;
    const float COHERENCE_THRESHOLD = ema_initialized ? 90.0f : 120.0f;
    uint32_t deadline = micros() + PHASE_COLLECT_TIMEOUT_US;

    while (valid < PHASE_AVG_SAMPLES) {
        if ((int32_t)(micros() - deadline) >= 0) break;
        if (rx_tail == rx_head) { delayMicroseconds(10); continue; }

        int64_t delta_ticks;
        portENTER_CRITICAL(&isrMux);
        delta_ticks = rx_queue[rx_tail];
        rx_tail     = (rx_tail + 1) & RX_QUEUE_MASK;
        portEXIT_CRITICAL(&isrMux);

        int64_t norm_delta = delta_ticks % period_ticks;
        if (norm_delta < 0) norm_delta += period_ticks;

        float phase_raw = ((float)norm_delta / (float)period_ticks) * 360.0f - phase_offset;
        while (phase_raw >  180.0f) phase_raw -= 360.0f;
        while (phase_raw < -180.0f) phase_raw += 360.0f;

        if (ema_initialized) {
            float diff = phase_raw - phase_ema;
            if (diff >  180.0f) diff -= 360.0f;
            if (diff < -180.0f) diff += 360.0f;
            if (fabsf(diff) > COHERENCE_THRESHOLD) continue;
        }

        sin_acc += sinf(phase_raw * DEG_TO_RAD);
        cos_acc += cosf(phase_raw * DEG_TO_RAD);
        valid++;
    }

    if (valid < PHASE_MIN_VALID) { phase_degrees = phase_last_valid; return; }

    float current_phase = atan2f(sin_acc, cos_acc) * RAD_TO_DEG;

    if (!ema_initialized) {
        ema_init_sin += sinf(current_phase * DEG_TO_RAD);
        ema_init_cos += cosf(current_phase * DEG_TO_RAD);
        ema_init_count++;
        if (ema_init_count >= EMA_INIT_SAMPLES) {
            phase_ema       = atan2f(ema_init_sin, ema_init_cos) * RAD_TO_DEG;
            ema_initialized = true; 
            ema_init_count = 0; ema_init_sin = ema_init_cos = 0.0f;
            char msg[48];
            snprintf(msg, sizeof(msg), "EMA fase inizializzata: %.2f°", phase_ema);
            sendToHost(msg);
        }
        phase_degrees = phase_last_valid;
        return;
    }

    float diff = current_phase - phase_ema;
    if (diff >  180.0f) diff -= 360.0f;
    if (diff < -180.0f) diff += 360.0f;
    phase_ema += PHASE_EMA_ALPHA * diff;
    if (phase_ema >  180.0f) phase_ema -= 360.0f;
    if (phase_ema < -180.0f) phase_ema += 360.0f;
    phase_degrees = phase_last_valid = phase_ema;
}

float leggiBatteria() {
    if (isSimulated) return 12.6f;
    for (int i = 0; i < 2; i++) { ads.readADC_SingleEnded(1); delay(2); }
    long sum = 0;
    for (int i = 0; i < 4; i++) { sum += ads.readADC_SingleEnded(1); delay(2); }
    int16_t raw = (int16_t)(sum / 4);
    return ((raw * 2048.0f) / 32767.0f / 1000.0f) * 60.65f;
}

// ── Calibrazione ──────────────────────────────────────────────
enum CalibState { CALIB_IDLE, CALIB_RX_SAMPLING, CALIB_PHASE_SAMPLING, CALIB_DONE };
CalibState    calibState       = CALIB_IDLE;
int           calibSampleCount = 0;
float         calibSum         = 0.0f;
float         calibSinSum      = 0.0f;
float         calibCosSum      = 0.0f;
unsigned long lastCalibTime    = 0;
const int CALIB_RX_SAMPLES    = 50;
const int CALIB_PHASE_SAMPLES = 40;
const int CALIB_PHASE_DISCARD = 10;

void startCalibration() {
    streamingActive = false; txOffset = 0; batchCounter = 0;
    calibrationActive = true; calibState = CALIB_RX_SAMPLING; calibSampleCount = 0;
    calibSum = calibSinSum = calibCosSum = 0.0f;
    lastCalibTime = millis();
    ema_initialized = false; ema_init_count = 0; ema_init_sin = ema_init_cos = 0.0f;
    phase_last_valid = phase_degrees = phase_ema = 0.0f;
    sendToHost("MSG:CALIBRATION_START");
}

void updateCalibration() {
    if (calibState == CALIB_IDLE) return;
    unsigned long now = millis();
    if (now - lastCalibTime < 20) return;
    lastCalibTime = now;
    
    switch (calibState) {
        case CALIB_RX_SAMPLING: {
            calibSum += leggiSegnaleRX();
            if (++calibSampleCount >= CALIB_RX_SAMPLES) {
                livelloBase = calibSum / CALIB_RX_SAMPLES;
                phase_offset = 0.0f; calibSampleCount = 0;
                calibState = isSimulated ? CALIB_DONE : CALIB_PHASE_SAMPLING;
            }
            break;
        }
        case CALIB_PHASE_SAMPLING: {
            leggiFase();
            if (++calibSampleCount > CALIB_PHASE_DISCARD) {
                calibSinSum += sinf(phase_degrees * DEG_TO_RAD);
                calibCosSum += cosf(phase_degrees * DEG_TO_RAD);
            }
            if (calibSampleCount >= CALIB_PHASE_SAMPLES) {
                phase_offset = atan2f(calibSinSum, calibCosSum) * RAD_TO_DEG;
                phase_last_valid = phase_ema = 0.0f;
                ema_initialized = false; ema_init_count = 0; ema_init_sin = ema_init_cos = 0.0f;
                calibState = CALIB_DONE;
            }
            break;
        }
        case CALIB_DONE: {
            phase_offset = atan2f(calibSinSum, calibCosSum) * RAD_TO_DEG;
            phase_last_valid = phase_ema = 0.0f;
            ema_initialized = false; ema_init_count = 0; ema_init_sin = ema_init_cos = 0.0f;

            portENTER_CRITICAL(&isrMux);
            rx_tail          = rx_head;
            isr_tx_valid     = false;
            isr_last_rx_tick = 0;
            portEXIT_CRITICAL(&isrMux);

            char msg[64];
            snprintf(msg, sizeof(msg), "CALIB:%.2f,PHASE_OFF:%.2f", livelloBase, phase_offset);
            sendToHost(msg);
            streamingActive = true; calibrationActive = false; calibState = CALIB_IDLE;
            break;
        }
        default: break;
    }
}

// ── Parser comandi non-bloccante ──────────────────────────────
static char cmdBuf[64];
static uint8_t cmdIdx = 0;
static unsigned long cmdLastByte = 0;
const unsigned long CMD_TIMEOUT_MS = 200;

void processSerialCommand(const String& cmd) {
    String cleanCmd = cmd;
    cleanCmd.trim();
    if (calibrationActive && (cleanCmd.startsWith("SETFREQ ") || cleanCmd.startsWith("SETDUTY "))) {
        sendToHost("ERR:CALIB_ACTIVE"); return;
    }
    if      (cleanCmd == "CALIBRATE")       { startCalibration(); }
    else if (cleanCmd == "REBOOT")          { ESP.restart(); }
    else if (cleanCmd == "PING")            { sendToHost("PONG"); }
    else if (cleanCmd.startsWith("SETFREQ ")) {
        int f = cleanCmd.substring(8).toInt();
        if (f >= 1000 && f <= 50000) {
            FREQUENZA_LAVORO = f;
            aggiornaHardwareTX(FREQUENZA_LAVORO, POTENZA_DUTY_CYCLE);
            sendToHost("FREQ_OK");
        }
    }
    else if (cleanCmd.startsWith("SETDUTY ")) {
        int d = cleanCmd.substring(8).toInt();
        if (d >= 10 && d <= 90) {
            POTENZA_DUTY_CYCLE = d;
            aggiornaHardwareTX(FREQUENZA_LAVORO, POTENZA_DUTY_CYCLE);
            sendToHost("DUTY_OK");
        }
    }
    else if (cleanCmd == "STATUS") {
        char buf[128];
        snprintf(buf, sizeof(buf), "STATUS:%.2f,%.2f,%.2f,EMA:%.2f,INIT:%d,ALPHA:%.3f,TRANSPORT:USB",
                 livelloBase, phase_offset, batteryVoltage,
                 phase_ema, ema_initialized ? 1 : 0, PHASE_EMA_ALPHA);
        sendToHost(buf);
    }
}

void handleSerialCommands(Stream& port) {
    while (port.available()) {
        char c = port.read();
        cmdLastByte = millis();
        
        if (c == '\n' || c == '\r') {
            if (cmdIdx > 0) {
                cmdBuf[cmdIdx] = '\0';
                processSerialCommand(String(cmdBuf));
                cmdIdx = 0;
            }
        } else if (cmdIdx < sizeof(cmdBuf) - 1) {
            cmdBuf[cmdIdx++] = c;
        }
    }
    if (cmdIdx > 0 && millis() - cmdLastByte > CMD_TIMEOUT_MS) {
        cmdIdx = 0;
    }
}

// ── Setup ─────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(2000);

#if defined(CONFIG_IDF_TARGET_ESP32S3)
    unsigned long start = millis();
    while (!Serial && (millis() - start < 3000)) {
        delay(10);
    }
#endif

    Wire.begin();
    Wire.setClock(400000);
    pinMode(PIN_TX_MOSFET, OUTPUT);
    digitalWrite(PIN_TX_MOSFET, LOW);
    pinMode(PIN_LED_STATO, OUTPUT);

    precisionTimer = timerBegin(0, TIMER_DIVIDER, true);
    if (!precisionTimer) {
        Serial.println("ERRORE CRITICO: timerBegin fallito!");
        delay(2000); ESP.restart();
    }

    if (!ads.begin()) {
        isSimulated = true; livelloBase = 256.0f;
        Serial.println("ADS1115 non trovato: modalità simulazione");
    } else {
        ads.setGain(GAIN_TWO);
        ads.setDataRate(RATE_ADS1115_250SPS);
        pinMode(PIN_TX_REF_IN, INPUT);
        attachInterrupt(digitalPinToInterrupt(PIN_TX_REF_IN), isr_tx_ref, RISING);
        pinMode(PIN_RX_PHASE, INPUT);
        attachInterrupt(digitalPinToInterrupt(PIN_RX_PHASE), isr_rx, RISING);
        delay(100);
        batteryVoltage = leggiBatteria();
        char bootBuf[48];
        snprintf(bootBuf, sizeof(bootBuf), "INF:%.2f,%d", batteryVoltage, FREQUENZA_LAVORO);
        sendToHost(bootBuf);
    }

    aggiornaHardwareTX(FREQUENZA_LAVORO, POTENZA_DUTY_CYCLE);
    sendToHost("MSG:READY");
    sendToHost("MSG:MODE_USB");
    Serial.println("=== OFFICINA MASTER v5.8-USB-ONLY ===");
}

// ── Loop ──────────────────────────────────────────────────────
void loop() {
    handleSerialCommands(Serial);
    updateCalibration();
    
    static unsigned long lastDataMicros = 0;
    static bool firstRun = true;
    if (firstRun) { lastDataMicros = micros(); firstRun = false; }

    unsigned long nowMicros = micros();

    if (streamingActive && (nowMicros - lastDataMicros) >= SAMPLE_PERIOD_US) {
        lastDataMicros += SAMPLE_PERIOD_US;
        if (lastDataMicros < nowMicros - SAMPLE_PERIOD_US * 2) {
            lastDataMicros = nowMicros;
        }

        leggiFase();
        float val   = leggiSegnaleRX();
        float delta = val - livelloBase;

        if (batchCounter == 0) { txBuf[0] = 'B'; txBuf[1] = ':'; txOffset = 2; }

        int written = snprintf(txBuf + txOffset, TX_BUF_SIZE - txOffset, "%.2f,%.2f;", delta, phase_degrees);
        if (written > 0 && (txOffset + written) < TX_BUF_SIZE) txOffset += written;
        batchCounter++;

        if (batchCounter >= BATCH_SIZE) {
            txBuf[txOffset++] = '\n';
            sendToHostRaw((const uint8_t*)txBuf, txOffset);
            txOffset = 0; batchCounter = 0;
            yield();
        }
    }

    static unsigned long lastBlink = 0;
    unsigned long nowMillis = millis();
    if ((nowMillis - lastBlink) >= 1000) {
        lastBlink = nowMillis;
        digitalWrite(PIN_LED_STATO, !digitalRead(PIN_LED_STATO));
    }

    static unsigned long lastBatteryRead = 0;
    if (!isSimulated && calibState == CALIB_IDLE && (nowMillis - lastBatteryRead) >= 60000) {
        lastBatteryRead = nowMillis;
        detachInterrupt(digitalPinToInterrupt(PIN_RX_PHASE));
        detachInterrupt(digitalPinToInterrupt(PIN_TX_REF_IN));
        batteryVoltage  = leggiBatteria();
        attachInterrupt(digitalPinToInterrupt(PIN_TX_REF_IN), isr_tx_ref, RISING);
        attachInterrupt(digitalPinToInterrupt(PIN_RX_PHASE), isr_rx, RISING);
        portENTER_CRITICAL(&isrMux);
        rx_tail = rx_head;
        portEXIT_CRITICAL(&isrMux);
        char buf[48];
        snprintf(buf, sizeof(buf), "INF:%.2f,%d", batteryVoltage, FREQUENZA_LAVORO);
        sendToHost(buf);
    }
}