package com.prismcampus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.LocalSLMManager
import com.prismcampus.app.ai.ModelDownloadState
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ModelSetupDialog(
    voiceManager: VoiceAssistantManager,
    localSlmManager: LocalSLMManager,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isVoskInstalled by voiceManager.voskEngine.isModelInstalled.collectAsState()
    val isQwenLoaded by localSlmManager.isModelLoaded.collectAsState()
    val downloadState by voiceManager.modelDownloader.downloadState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrismSurfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = CyberEmerald,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Offline AI Model Management",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Manage on-device neural models for 100% offline speech recognition and edge LLM inference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // ==========================================
                // 1. OFFLINE SPEECH MODEL (Vosk English Small)
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrismSurface)
                        .border(1.dp, if (isVoskInstalled) CyberEmerald.copy(alpha = 0.4f) else AmberAlert.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Offline Voice Recognition", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                        }

                        Text(
                            text = if (isVoskInstalled) "INSTALLED" else "MISSING",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (isVoskInstalled) CyberEmerald else AmberAlert
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Engine: Vosk-Android • Model: vosk-model-small-en-us-0.15\nSize: ~40 MB • Location: app internal storage",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    when (val state = downloadState) {
                        is ModelDownloadState.Downloading -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = CyberEmerald
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Downloading offline speech model: ${state.progressPercent}% (${state.downloadedBytes / (1024 * 1024)} MB)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = CyberEmerald
                                )
                            }
                        }
                        is ModelDownloadState.Extracting -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyberEmerald)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Extracting acoustic files to filesDir...", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = CyberEmerald)
                            }
                        }
                        is ModelDownloadState.Error -> {
                            Text(
                                text = "Error: ${state.message}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = AmberAlert
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        voiceManager.modelDownloader.downloadAndInstallModel()
                                        voiceManager.voskEngine.checkModelInstallation()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberAlert, contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Retry Download (~40 MB)", fontSize = 11.sp)
                            }
                        }
                        else -> {
                            if (!isVoskInstalled) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            voiceManager.modelDownloader.downloadAndInstallModel()
                                            voiceManager.voskEngine.checkModelInstallation()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download English Model (40 MB)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ready for offline voice recognition", style = MaterialTheme.typography.labelSmall, color = CyberEmerald)
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 2. ON-DEVICE QWEN2.5-0.5B SLM
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrismSurface)
                        .border(1.dp, if (isQwenLoaded) CyberEmerald.copy(alpha = 0.4f) else PrismBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Local Qwen2.5-0.5B SLM", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                        }

                        Text(
                            text = if (isQwenLoaded) "LOADED" else "CAMPUS DB ACTIVE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (isQwenLoaded) CyberEmerald else ElectricBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isQwenLoaded) {
                            "Qwen2.5-0.5B ready for on-device inference.\nllama.cpp • ${com.prismcampus.app.ai.AppMemoryBudget.MIB} MiB memory budget."
                        } else {
                            "Qwen GGUF weights are bundled with the app (~469 MB). Installed automatically on your first offline question. Campus commands respond instantly without loading Qwen."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = TextMuted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = CyberEmerald, fontWeight = FontWeight.Bold)
            }
        }
    )
}
