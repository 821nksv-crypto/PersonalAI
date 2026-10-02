package com.personalai.mvp.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personalai.mvp.ai.ExecuTorchLocalEngine
import com.personalai.mvp.ai.LocalModelEngine
import com.personalai.mvp.rag.PersistentVectorIndex
import java.io.File

private const val RUNTIME_MODEL = "SmolLM2-135M-Instruct-8da4w-2k.pte"
private const val RUNTIME_TOKENIZER = "tokenizer.json"

@Composable
fun ChatHostScreen(modelReady: Boolean) {
    val context = LocalContext.current
    val modelFile = remember { File(context.filesDir, "models/runtime/$RUNTIME_MODEL") }
    val tokenizerFile = remember { File(context.filesDir, "models/runtime/$RUNTIME_TOKENIZER") }
    val runtimeReady = modelFile.isFile && modelFile.length() > 0L &&
        tokenizerFile.isFile && tokenizerFile.length() > 0L
    val engine: LocalModelEngine = remember(runtimeReady, modelReady) {
        ExecuTorchLocalEngine(modelFile, tokenizerFile)
    }
    val index = remember { PersistentVectorIndex(context) }
    val runtimeKey = if (runtimeReady) "smollm2-6" else "unavailable"
    val vm: ChatViewModel = viewModel(
        key = "chat-$runtimeKey",
        factory = ChatViewModelFactory(context, engine, index)
    )

    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val stream by vm.stream.collectAsState()
    val route by vm.lastRoute.collectAsState()
    val sources by vm.lastSources.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Personal AI", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (runtimeReady && vm.modelReady) "On-device model: SmolLM2 135M • XNNPACK 2K"
            else "Install the recommended local model from Models."
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.filter { it.role != "system" }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message.role.uppercase(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(message.text)
                    }
                }
            }
            if (stream.active && stream.text.isNotBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("ASSISTANT • generating", style = MaterialTheme.typography.labelSmall)
                            Text(stream.text)
                        }
                    }
                }
            }
            if (!stream.active && stream.error != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("Local AI error: ${stream.error}", Modifier.padding(12.dp))
                    }
                }
            }
        }

        route?.let { Text("Route: ${it.name.replace('_', ' ')}", style = MaterialTheme.typography.labelSmall) }
        sources.forEachIndexed { i, s ->
            Text("[${i + 1}] ${s.documentName} — page ${s.pageNumber}", style = MaterialTheme.typography.bodySmall)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                enabled = !busy && runtimeReady && vm.modelReady,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (runtimeReady) "Talk to your local AI…" else "Download the local model first")
                }
            )
            if (busy) {
                Button(onClick = vm::stopGeneration) { Text("Stop") }
            } else {
                Button(
                    enabled = runtimeReady && vm.modelReady && input.isNotBlank(),
                    onClick = { vm.send(input); input = "" }
                ) { Text("Send") }
            }
        }
    }
}
