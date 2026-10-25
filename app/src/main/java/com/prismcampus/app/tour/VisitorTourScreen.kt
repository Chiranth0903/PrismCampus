package com.prismcampus.app.tour

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.SupportedLanguage
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CrimsonAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PillActiveBackground
import com.prismcampus.app.ui.theme.PillActiveBorder
import com.prismcampus.app.ui.theme.PillInactiveBackground
import com.prismcampus.app.ui.theme.PillInactiveBorder
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import com.prismcampus.app.ui.theme.VioletAccent

@Composable
fun VisitorTourScreen(
    tourController: TourGuideController,
    voiceManager: VoiceAssistantManager,
    onNavigateToLandmark: (LandmarkTourPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedLanguage by voiceManager.selectedLanguage.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val selectedLandmark by tourController.selectedLandmark.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Tour Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1B4B), PrismSurface)
                        )
                    )
                    .border(1.dp, VioletAccent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(VioletAccent.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            tint = VioletAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "MSRIT Campus Tour & Parent Guide",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Offline Trilingual Voice Narration • Est. 1962",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Welcome prospective students, parents, and distinguished guests! Tap any campus landmark to hear narrated historical milestones, department infrastructure, and national accreditation highlights in English, Hindi, or Kannada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Language selector pills for tour
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio Language: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    SupportedLanguage.values().forEach { lang ->
                        val isSelected = lang == selectedLanguage
                        val bg = if (isSelected) PillActiveBackground else PillInactiveBackground
                        val border = if (isSelected) PillActiveBorder else PillInactiveBorder
                        val textColor = if (isSelected) CyberEmerald else TextSecondary

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .border(1.dp, border, RoundedCornerShape(8.dp))
                                .clickable { voiceManager.setLanguage(lang) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = lang.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = textColor
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "CAMPUS LANDMARKS & ACADEMIC COMPLEXES (${tourController.landmarks.size} KEY LOCATIONS)",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
        }

        items(tourController.landmarks) { landmark ->
            LandmarkCard(
                landmark = landmark,
                selectedLanguage = selectedLanguage,
                isSpeaking = isSpeaking && selectedLandmark?.id == landmark.id,
                onPlayNarration = {
                    if (isSpeaking && selectedLandmark?.id == landmark.id) {
                        voiceManager.stopSpeaking()
                    } else {
                        tourController.playNarrationForLandmark(landmark, selectedLanguage)
                    }
                },
                onNavigate = { onNavigateToLandmark(landmark) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LandmarkCard(
    landmark: LandmarkTourPoint,
    selectedLanguage: SupportedLanguage,
    isSpeaking: Boolean,
    onPlayNarration: () -> Unit,
    onNavigate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PrismSurface)
            .border(1.dp, if (isSpeaking) CyberEmerald else PrismBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Apartment,
                    contentDescription = null,
                    tint = ElectricBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = landmark.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = landmark.yearEstablished,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F1A2E))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = landmark.code,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ElectricBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = landmark.shortSubtitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Accreditation Badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = AmberAlert,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = landmark.accreditationHighlights,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = AmberAlert
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Departments housed bullet points
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(PrismSurfaceVariant)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            landmark.departmentsHoused.forEach { dept ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(CyberEmerald)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dept,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Narration Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPlayNarration,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeaking) CrimsonAlert else CyberEmeraldDark,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isSpeaking) "Stop Audio" else "Play Narration (${selectedLanguage.displayName})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onNavigate,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint = ElectricBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Map", color = ElectricBlue, fontSize = 12.sp)
            }
        }
    }
}
