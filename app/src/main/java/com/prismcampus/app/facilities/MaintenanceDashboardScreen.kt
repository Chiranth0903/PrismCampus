package com.prismcampus.app.facilities

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusIssueReport
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CrimsonAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PrismBackground
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import com.prismcampus.app.ui.theme.VioletAccent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MaintenanceDashboardScreen(
    database: CampusDatabase,
    issueReporter: IssueReporter,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val issues by database.issuesFlow.collectAsState()
    val meshStatus by issueReporter.meshStatus.collectAsState()
    val isSyncing by issueReporter.isSyncing.collectAsState()

    val clusters = remember(issues) { issueReporter.computeIssueClusters(issues) }
    var simulatedNodeDetected by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Dashboard Title Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CrimsonAlert.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = CrimsonAlert,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Management Pre-Emption Hub",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Internal campus resolution before external social media leaks",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "PrismCampus clusters real-time student voice reports. Facilities teams resolve broken equipment and safety hazards proactively, protecting institutional reputation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // Uno Q Edge Mesh Buffer Telemetry Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, if (simulatedNodeDetected || meshStatus.isConnectedToMesh) CyberEmerald else PrismBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = if (simulatedNodeDetected || meshStatus.isConnectedToMesh) CyberEmerald else AmberAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ARDUINO UNO Q EDGE MESH BUFFER",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (simulatedNodeDetected || meshStatus.isConnectedToMesh) CyberEmerald.copy(alpha = 0.15f) else AmberAlert.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (simulatedNodeDetected || meshStatus.isConnectedToMesh) "NODE CONNECTED" else "OFFLINE BUFFER",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (simulatedNodeDetected || meshStatus.isConnectedToMesh) CyberEmerald else AmberAlert
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Edge Node SSID: ${if (simulatedNodeDetected) "MSRIT_Edge_Node_01 (Simulated)" else (meshStatus.detectedSsid ?: "None in range")}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextPrimary
                )

                Text(
                    text = "Pending Local Buffer: ${issues.count { !it.isSynced }} un-synced report(s) (0 KB cellular data)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = meshStatus.lastSyncStatus ?: "Ready",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = CyberEmerald
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                issueReporter.flushReportsToUnoQ()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberEmeraldDark,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Flushing...")
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Flush to Uno Q (Local Wi-Fi)", fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            simulatedNodeDetected = !simulatedNodeDetected
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (simulatedNodeDetected) "Disconnect" else "Simulate Node", color = ElectricBlue, fontSize = 11.sp)
                    }
                }
            }
        }

        // Pre-Emption Cluster Heat-Map
        item {
            Text(
                text = "CAMPUS INCIDENT CLUSTERS (${clusters.size} ACTIVE ZONES)",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
        }

        items(clusters) { cluster ->
            ClusterCard(cluster = cluster)
        }

        // Complete Live Incident Feed
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ALL LOGGED INCIDENTS (${issues.size} TOTAL)",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                letterSpacing = 0.5.sp
            )
        }

        items(issues) { issue ->
            IssueItemCard(issue = issue)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ClusterCard(cluster: IssueClusterSummary) {
    val borderColor = if (cluster.isEscalated) CrimsonAlert else AmberAlert
    val bgColor = if (cluster.isEscalated) CrimsonAlert.copy(alpha = 0.1f) else PrismSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PriorityHigh,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${cluster.building} • ${cluster.room}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(borderColor.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${cluster.count} COMPLAINTS",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = borderColor
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Category: ${cluster.recentCategory} | Urgency: ${cluster.highestUrgency}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        if (cluster.isEscalated) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "⚡ PRE-EMPTION TRIGGER: Multiple reports in 48h. Auto-escalated to MSRIT Head of Maintenance to prevent public social media escalation.",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = CrimsonAlert
            )
        }
    }
}

@Composable
private fun IssueItemCard(issue: CampusIssueReport) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.US) }
    val formattedDate = remember(issue.timestamp) { dateFormat.format(Date(issue.timestamp)) }

    val categoryColor = when (issue.category) {
        "Electrical" -> AmberAlert
        "Plumbing" -> ElectricBlue
        "Hardware/AC" -> VioletAccent
        "Furniture" -> CyberEmerald
        else -> TextSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PrismSurface)
            .border(1.dp, PrismBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(categoryColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = issue.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = categoryColor
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${issue.building} (${issue.room})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (issue.isSynced) {
                    Icon(Icons.Default.CloudDone, contentDescription = "Synced", tint = CyberEmerald, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Synced", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = CyberEmerald)
                } else {
                    Text("Mesh Pending", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = AmberAlert)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "“${issue.description}”",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Reported: $formattedDate • Urgency: ${issue.urgency}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = TextMuted
        )
    }
}
