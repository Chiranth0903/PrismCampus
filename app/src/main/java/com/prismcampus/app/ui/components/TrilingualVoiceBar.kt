package com.prismcampus.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.SupportedLanguage
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PillActiveBackground
import com.prismcampus.app.ui.theme.PillActiveBorder
import com.prismcampus.app.ui.theme.PillInactiveBackground
import com.prismcampus.app.ui.theme.PillInactiveBorder
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary

@Composable
fun TrilingualVoiceBar(
    selectedLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onSpeakTriggered: () -> Unit,
    onMicTriggered: () -> Unit,
    isSpeaking: Boolean,
    isListening: Boolean,
    lastSpokenText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PrismSurface)
            .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRILINGUAL VOICE ASSISTANT",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )

            // Language Selector: English, हिंदी, ಕನ್ನಡ
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SupportedLanguage.values().forEach { lang ->
                    LanguageChip(
                        name = lang.displayName,
                        isSelected = lang == selectedLanguage,
                        onClick = { onLanguageSelected(lang) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Playback & Voice Trigger Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSpeaking) CyberEmerald.copy(alpha = 0.2f) else PillInactiveBackground)
                        .border(1.dp, if (isSpeaking) CyberEmerald else PrismBorder, CircleShape)
                        .clickable(onClick = onSpeakTriggered),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (isSpeaking) CyberEmerald else TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = if (isSpeaking) "Speaking navigation..." else "Tap to hear offline directions",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        ),
                        color = if (isSpeaking) CyberEmerald else TextPrimary
                    )
                    Text(
                        text = "Native Android TTS (${selectedLanguage.displayName})",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted
                    )
                }
            }

            // Voice input / SpeechRecognizer button
            IconButton(
                onClick = onMicTriggered,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isListening) ElectricBlue.copy(alpha = 0.2f) else PillInactiveBackground)
                    .border(1.dp, if (isListening) ElectricBlue else PrismBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice query",
                    tint = if (isListening) ElectricBlue else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Real-time Subtitle / Spoken text display
        AnimatedVisibility(visible = lastSpokenText.isNotBlank()) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F1A2E))
                        .border(1.dp, PrismBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "“$lastSpokenText”",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = 12.sp
                        ),
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PillActiveBackground else PillInactiveBackground
    val border = if (isSelected) PillActiveBorder else PillInactiveBorder
    val textColor = if (isSelected) CyberEmerald else TextSecondary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 11.sp
            ),
            color = textColor
        )
    }
}
