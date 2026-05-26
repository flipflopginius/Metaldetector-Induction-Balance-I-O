package com.tetranova.metaldetectorpro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RawDataScreen(vm: DetectorViewModelV2) {
    val rawMessages by vm.rawDataMessages.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll in basso quando arrivano nuovi messaggi
    LaunchedEffect(rawMessages.size) {
        if (rawMessages.isNotEmpty()) {
            listState.animateScrollToItem(rawMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "📡 Dati grezzi ESP32 (1 su 10 mostrati)",
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(8.dp),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = false  // i nuovi in basso
        ) {
            itemsIndexed(rawMessages) { index, msg ->
                Text(
                    text = msg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        msg.contains("delta") -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}