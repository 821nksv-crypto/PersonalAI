package com.personalai.mvp.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personalai.mvp.rag.EmbeddingStatus

private val Bg = Color(0xFF070B10)
private val Card = Color(0xFF111A23)
private val TextPrimary = Color(0xFFF5F7FA)
private val TextSecondary = Color(0xFFADB8C7)
private val Accent = Color(0xFF7C5CFF)

@Composable
fun SettingsScreen(
    internetEnabled: Boolean,
    memoryEnabled: Boolean,
    modelReady: Boolean,
    onInternetChanged: (Boolean) -> Unit,
    onMemoryChanged: (Boolean) -> Unit,
    onClearMemory: () -> Unit,
    embeddingStatus: EmbeddingStatus,
    onImportEmbedding: () -> Unit,
    onRemoveEmbedding: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("personalai_settings", Context.MODE_PRIVATE) }
    var instructions by remember { mutableStateOf(prefs.getString("ai_instructions", "") ?: "") }
    var saved by remember { mutableStateOf(false) }
    val storageBytes = remember(modelReady) {
        val root = java.io.File(context.filesDir, "models")
        if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = TextPrimary) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", color = TextPrimary, style = MaterialTheme.typography.headlineSmall)

            SettingsCard("AI Instructions") {
                Text(
                    "Set how PersonalAI should answer. The instruction is stored only on this device.",
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it; saved = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    label = { Text("Instructions for AI") },
                    placeholder = { Text("Example: Answer in Hindi, be concise, and explain with examples.") },
                    minLines = 5,
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color(0xFF566170),
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = Accent
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = {
                            prefs.edit().putString("ai_instructions", instructions.trim()).apply()
                            saved = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("Save instructions") }
                    if (saved) Text("Saved", color = Color(0xFF36D987), modifier = Modifier.padding(top = 12.dp))
                }
            }

            SettingsCard("Privacy & Network") {
                SettingSwitch("Internet access", "Only needed for web search and model downloads.", internetEnabled, onInternetChanged)
                SettingSwitch("Local memory", "Keep bounded conversation context on this device.", memoryEnabled, onMemoryChanged)
                OutlinedButton(onClick = onClearMemory, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear local memory")
                }
            }

            SettingsCard("Local model") {
                Text(
                    if (modelReady) "Qwen3 0.6B is installed and ready." else "No local model installed. Open Models to download one.",
                    color = if (modelReady) Color(0xFF36D987) else Color(0xFFFFB84D)
                )
                Text("Model storage: " + (storageBytes / (1024 * 1024)) + " MB", color = TextSecondary)
            }

            SettingsCard("Document embeddings") {
                Text("Offline baseline: hashing baseline", color = TextSecondary)
                Text("Status: " + embeddingStatus, color = TextPrimary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onImportEmbedding, modifier = Modifier.weight(1f)) { Text("Import ONNX") }
                    OutlinedButton(onClick = onRemoveEmbedding, modifier = Modifier.weight(1f)) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary)
            Text(description, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}
