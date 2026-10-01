package com.personalai.mvp.importer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Bg = Color(0xFF070B10)
private val Card = Color(0xFF111A23)
private val TextPrimary = Color(0xFFF5F7FA)
private val TextSecondary = Color(0xFFADB8C7)
private val Accent = Color(0xFF7C5CFF)

@Composable
fun ModelImportScreen(
    installedModels: List<String>,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    onDownload: () -> Unit,
    downloading: Boolean,
    downloadProgress: Float,
    downloadStatus: String
) {
    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = TextPrimary) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Local AI Models", color = TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Qwen3 0.6B • XNNPACK • 2K is recommended for phones. The model is downloaded only when you choose it, stored locally, and then runs offline.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
            ) {
                Text(if (downloading) "Downloading model…" else "Download Qwen3 0.6B")
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (downloadProgress > 0f) (downloadProgress * 100).toInt().toString() + "% • Preparing local model"
                    else "Connecting to model server…",
                    color = TextSecondary
                )
            }
            if (downloadStatus.isNotBlank()) {
                Text(downloadStatus, color = TextSecondary)
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Local Model")
            }
            HorizontalDivider(color = Color(0xFF26313D))
            if (installedModels.isEmpty()) {
                Text("No local model installed.", color = TextSecondary)
                Text(
                    "The APK does not contain the large model file. This keeps installation size low. Download it once from this screen.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Installed models", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                installedModels.forEach { model ->
                    Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(model, color = TextPrimary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemove(model) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
