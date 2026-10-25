package com.prismcampus.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.data.MemoryTelemetryInfo
import com.prismcampus.app.data.ScheduleOverride
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CyberEmerald
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

@Composable
fun TelemetryHUD(
    telemetry: MemoryTelemetryInfo,
    activeSection: String,
    onSectionSelected: (String) -> Unit,
    activeOverrides: List<ScheduleOverride>,
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
        // Top HUD Header: Brand, Telemetry & Edge Mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(CyberEmerald)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PRISMCAMPUS • MSRIT EDGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberEmerald,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = ElectricBlue,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${telemetry.averageInferenceLatencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live Memory Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "App memory: ${telemetry.usedMemoryMb} MiB / ${telemetry.maxMemoryMb} MiB budget",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )
            }
            Text(
                text = "${(telemetry.memoryUsageRatio * 100).toInt()}% Used",
                style = MaterialTheme.typography.labelSmall,
                color = if (telemetry.memoryUsageRatio > 0.8f) AmberAlert else CyberEmerald
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { telemetry.memoryUsageRatio },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (telemetry.memoryUsageRatio > 0.8f) AmberAlert else CyberEmerald,
            trackColor = PrismSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 1-Tap Section Pills: [Section A (AB-501)] [Section D (AB-504)] [Section I (AB-514)]
        Text(
            text = "ACTIVE SECTION TIMETABLE",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionPill(
                sectionId = "A",
                label = "Section A (AB-501)",
                dept = "CSE",
                isSelected = activeSection.equals("A", ignoreCase = true),
                onClick = { onSectionSelected("A") }
            )
            SectionPill(
                sectionId = "D",
                label = "Section D (AB-504)",
                dept = "ISE",
                isSelected = activeSection.equals("D", ignoreCase = true),
                onClick = { onSectionSelected("D") }
            )
            SectionPill(
                sectionId = "I",
                label = "Section I (AB-514)",
                dept = "AIML",
                isSelected = activeSection.equals("I", ignoreCase = true),
                onClick = { onSectionSelected("I") }
            )
        }

        // Active Override Alert Banner (if any)
        AnimatedVisibility(visible = activeOverrides.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                activeOverrides.forEach { override ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AmberAlert.copy(alpha = 0.15f))
                            .border(1.dp, AmberAlert.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AmberAlert,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = override.reason,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = AmberAlert,
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionPill(
    sectionId: String,
    label: String,
    dept: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PillActiveBackground else PillInactiveBackground
    val border = if (isSelected) PillActiveBorder else PillInactiveBorder
    val textColor = if (isSelected) CyberEmerald else TextSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isSelected) CyberEmerald else Color.Transparent)
        )
        if (isSelected) Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
            color = textColor
        )
    }
}
