package com.tetranova.metaldetectorpro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ConsoleScreen(vm: DetectorViewModelV2) {
    val messages = vm.console
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll in basso quando arrivano nuovi messaggi
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            reverseLayout = false   // i più recenti in basso
        ) {
            itemsIndexed(messages) { index, message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(4.dp),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = vm.cmd,
                onValueChange = { vm.cmd = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Comando...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { vm.send() }) {
                Text("Invia")
            }
        }
    }
}