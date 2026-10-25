package com.prismcampus.app.facilities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.HybridRouter
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CrimsonAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ReportIssueModalDialog(
    hybridRouter: HybridRouter,
    voiceManager: VoiceAssistantManager,
    onDismiss: () -> Unit,
    onIssueReported: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isListening by voiceManager.isListening.collectAsState()
    val recognizedText by voiceManager.recognizedQuery.collectAsState()

    var manualInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening { voiceStr ->
                manualInput = voiceStr
            }
        }
    }

    val effectiveText = if (manualInput.isNotBlank()) manualInput else recognizedText

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrismSurfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = AmberAlert,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Report Campus Maintenance Issue",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Speak or describe any broken fan, leaking tap, or lab issue. Qwen2.5-0.5B Edge SLM extracts location and urgency offline:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = { Text(if (isListening) "Listening to voice..." else "e.g. 'Fan in ESB 402 vibrating dangerously'") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberEmerald,
                            unfocusedBorderColor = PrismBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (isListening) {
                                voiceManager.stopListening()
                            } else {
                                val hasAudioPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (!hasAudioPerm) {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    voiceManager.startListening { voiceStr ->
                                        manualInput = voiceStr
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isListening) CrimsonAlert else AmberAlert)
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = Color.Black
                        )
                    }
                }

                if (isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AmberAlert, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Extracting entities via Qwen SLM...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                feedbackMessage?.let { msg ->
                    Text(
                        text = "✓ $msg",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberEmerald
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val query = effectiveText
                    if (query.isNotBlank()) {
                        coroutineScope.launch {
                            isProcessing = true
                            val result = hybridRouter.routeQuery(query)
                            isProcessing = false
                            feedbackMessage = result.responseText
                            onIssueReported()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberEmeraldDark),
                enabled = !isProcessing && effectiveText.isNotBlank()
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}
