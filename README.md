Metal Detector Pro
Open source Induction Balance metal detector with phase discrimination, based on ESP32, ADS1115 and Android USB interface.

https://img.shields.io/badge/GitHub-Repository-blue?logo=github

Overview
Metal Detector Pro is an open source project that builds a professional induction balance (IB) metal detector using a DD coil, a ±10V analog front end, an ESP32 for TX signal generation and data acquisition, and an Android application for IQ vector processing, discrimination and user feedback. The system achieves a 30 cm air test range on a coin without false positives, thanks to a passive noise gate and a thermal drift compensation algorithm.

USB (CDC) connection was chosen over Bluetooth because the latter introduced unacceptable electromagnetic interference into the analog chain.

Technical Specifications
Default operating frequency: 5 kHz (adjustable from the app)

Coil: DD type (slightly overlapping windings)

TX: 50 turns, triple-stranded wire (0.3 mm) on 17 cm former; parallel capacitor 100 nF (indicative value, to be tuned)

RX: 200 turns (0.25 mm) on 17 cm former; parallel capacitor 18 nF (indicative value, to be tuned)

The null balance is intentionally non‑zero (≈30 mVpp) to exploit the rectifier threshold as a noise gate.

LC capacitor tuning note: the indicated capacitor values are starting points. To achieve resonance (with TX supply at only 10 V and a TX peak signal of about 50 Vpp), the TX and RX capacitors must be experimentally selected. Resonance frequency directly affects field amplitude and sensitivity.

Power supply: Li‑ion 16.8 V battery

+3.2 V for ESP32 and ADS1115 (LM317 regulator)

+10 V and –10 V for op‑amps (LM317 + ICL7660S with LC filter)

Analog front end (two OPA2134):

Non‑inverting preamplifier with DC‑coupled RX coil input

Precision full‑wave rectifier (two BAT43 diodes)

Phase comparator (LM393) to convert RX signal into square wave (two outputs to ESP32 GPIOs)

ADC: ADS1115 (16 bit, 250 SPS, GAIN_TWO, 0–3.2 V input protected by Zener diode)

Microcontroller: ESP32

Host interface: USB CDC (115200 baud)

Android app: Kotlin + Jetpack Compose, MVVM architecture

Hardware – Operating Principle
The TX coil is driven at 5 kHz by a MOSFET controlled by the ESP32. The signal induced on the RX coil is amplified and then split into two parallel paths:

Precision rectifier → extracts signal amplitude (proportional to metal proximity) and sends it to the ADS1115.

Phase comparator → converts the RX signal into a square wave; the ESP32 measures the phase shift against its own TX reference.

The residual null (≈30 mVpp) is low enough that the rectifier diodes do not conduct in the absence of metal (passive noise gate). When metal approaches, the amplitude rises, the rectifier begins to conduct, and the ADS1115 reads a positive voltage.

Power supplies:

+10 V and –10 V power the op‑amps and comparator, ensuring symmetrical dynamics.

+3.2 V powers the ESP32, ADS1115 and pull‑ups.

Tuning note: the TX and LC tank capacitors must be chosen so that, with the TX powered at only +10 V (not direct 16.8 V), the resonant TX signal is about 50 Vpp. This energy balance optimises sensitivity and reduces heating.

ESP32 Firmware
Generates the TX signal at frequency and duty cycle adjustable from the app (SETFREQ, SETDUTY commands).

Captures pulses from the comparator (phase measurement).

Reads the ADS1115 (amplitude).

Periodically (every 10 ms) sends batches of 5 samples: B:delta,phase;delta,phase;....

System commands: STATUS, REBOOT, SETFREQ, SETDUTY.

No hardware calibration – offset is handled entirely by the Android app.

Android Application
The app connects via USB, receives data and processes it in real time.

Signal processing
Software calibration (calibra): with the coil raised, the app samples 2 seconds of data, calculates the average IQ vector (baseline) and the RMS noise estimate.

Vector subtraction: for each sample, the differential vector (current – baseline) is computed.

Hysteresis gate: the energy of the tangential component decides when the signal is high enough to indicate metal.

Relative angle: when the gate is open, the phase angle is calculated (0° = no metal, ±90° discrimination).

VDI and depth: derived from angle and amplitude.

Thermal drift: the baseline is updated very slowly (α = 0.0005) when the signal is stable and no metal is detected, compensating for temperature variations without manual recalibration.

Audio feedback
The system produces a continuous whisper (background audio) whose frequency is continuously modulated by the signal amplitude. In the absence of metal the tone is very low (almost imperceptible). When metal approaches, the frequency gradually rises, giving an intuitive sense of proximity.

Additionally, the type of metal (ferrous vs. non‑ferrous) changes the timbre (e.g., an extra modulation or waveform change) to help the user distinguish the material by ear without looking at the screen. The audio discrimination is adjustable along with the VDI parameters.

Graphical representation – I/Q plane
The UI includes a Cartesian I/Q plane (orthogonal) where the fingerprint (trajectory) of the differential vector is drawn in real time. Each sample (I, Q) (or the vector (delta_I, delta_Q)) is displayed as a point or a trail. This allows direct observation of the signal behaviour: a radial elongation indicates amplitude increase, a rotation indicates phase change. The fingerprint helps to recognise different metals (e.g., ferrous materials tend to produce trajectories with negative angles, non‑ferrous with positive angles). The user can also view the baseline point and the current vector.

In‑app adjustments
Operating frequency (1 to 10 kHz) – sends SETFREQ to the ESP32.

TX duty cycle (10–90%) – SETDUTY command.

Sensitivity (1 to 10): modifies the K_VECT factor (from 4.0 down to 0.8), which scales the gate entry threshold.

IQ Dead Zone (0–5°): phase hysteresis to stabilise the angle when the signal is weak.

Discrimination thresholds (low/high angle) to classify ferrous/non‑ferrous.

Disc polarity inversion.

Performance
Air test range (€1 coin): 30 cm without false positives.

Discrimination: correct identification of iron, aluminium, copper and coins up to the maximum range.

Stability: thermal drift keeps the baseline stable for over 30 minutes without manual recalibration.

Intuitive audio: the frequency‑modulated whisper and timbre changes allow use even when the screen is covered.

Replication instructions
Required hardware
Self‑built DD coil (see specifications above).

4S Li‑ion battery pack (16.8 V) with protection.

Regulators: LM317 (two), ICL7660S, capacitors, inductor for filter.

ESP32 dev board (USB‑CDC).

ADS1115.

OPA2134 (two pieces).

LM393.

IRF640 MOSFET.

BAT43 diodes (4+), 3.3 V Zener diode.

Resistors, capacitors (values as per schematic – available on request).

Schematics: detailed circuit diagrams (power supply, TX stage, RX stage, rectifier, comparator, connections) are available on request by contacting the author or by checking the repository (hardware/ folder). This README describes the operating logic; for physical realisation refer to the official schematics.

Software
ESP32 firmware (PlatformIO / Arduino IDE):

Libraries: Adafruit_ADS1X15, driver/mcpwm, driver/timer.

Upload main.cpp (from the repository).

Serial speed 115200.

Android app:

Clone the repository.

Open with Android Studio.

Build and install (min SDK 24).

Grant USB permission.

Typical use
Connect the ESP32 to the phone via USB‑OTG.

Power on the circuit.

Open the app – automatic connection.

Perform Calibrate (I/Q) with the coil raised.

Adjust sensitivity and dead zone until the needle stabilises at 0°.

(Optional) Change frequency and duty from the app to adapt to the ground or for experimentation.

Move the coil: watch the fingerprint on the I/Q plane, the phase indicator, VDI, depth, and listen to the modulated whisper.

Design choices
DD coil and non‑zero null: simplifies the electronics and exploits the rectifier threshold as a noise gate; tested at 30 cm.

Dual ±10 V supply: symmetrical dynamics for signals centred at 0 V.

Precision rectifier: linearity even for small signals.

Resonant capacitors to be tuned: nominal values are indicative; fine‑tuning is essential to achieve 50 Vpp on the TX with only 10 V.

USB only: eliminates RF interference.

Continuous whisper audio: less tiring than discrete beeps, and frequency modulation gives an intuitive sense of distance.

I/Q plane: a powerful diagnostic and educational tool for understanding detector behaviour.

Repository and license
GitHub: https://github.com/flipflopginius/metal-detector-pro

Author: Carmelo Terranova

License: MIT

