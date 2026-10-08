package com.swarm.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swarm.ai.ui.companion.*
import kotlin.math.cos
import kotlin.math.sin

val AgentColors = mapOf(
    "manager" to Color(0xFF6366F1),
    "planner" to Color(0xFF8B5CF6),
    "researcher" to Color(0xFFEC4899),
    "designer" to Color(0xFFF472B6),
    "architect" to Color(0xFF06B6D4),
    "coder" to Color(0xFF10B981),
    "tester" to Color(0xFFF59E0B),
    "reviewer" to Color(0xFFEF4444),
    "optimizer" to Color(0xFF84CC16),
    "vision" to Color(0xFF8B5CF6),
    "finalizer" to Color(0xFF6366F1)
)

@Composable
fun SwarmPage(state: CompanionState, online: Boolean, onAgentClick: (TeamMember) -> Unit, onShowAll: () -> Unit) {
    var showList by remember { mutableStateOf(false) }
    
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Agent Map", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.team.count { it.status != "idle" }} working · ${state.team.count { it.status == "idle" }} idle",
                    fontSize = 13.sp,
                    color = Muted
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { showList = !showList }) {
                    Icon(
                        if (showList) Icons.Rounded.AccountTree else Icons.Rounded.List,
                        contentDescription = if (showList) "Show graph" else "Show list",
                        tint = Blue
                    )
                }
            }
        }
        
        AnimatedContent(showList, label = "view-mode") { list ->
            if (list) {
                AgentListView(state, online, onAgentClick)
            } else {
                AgentGraphView(state, online, onAgentClick)
            }
        }
    }
}

@Composable
fun AgentGraphView(state: CompanionState, online: Boolean, onAgentClick: (TeamMember) -> Unit) {
    val density = LocalDensity.current.density
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val centerX = width / 2f
        val centerY = height / 3f
        
        // Calculate positions for agents in a radial layout
        val radius = minOf(width, height) * 0.35f
        val angleStep = 360f / state.team.size
        
        val positions = state.team.mapIndexed { index, member ->
            val angle = Math.toRadians((angleStep * index - 90).toDouble())
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            member to Offset(x, y)
        }
        
        // Draw connections
        Canvas(Modifier.fillMaxSize()) {
            positions.forEach { (member, pos) ->
                if (member.status != "idle") {
                    // Draw animated connection to center
                    drawLine(
                        color = AgentColors[member.identity.id] ?: Color.Gray,
                        start = Offset(centerX, centerY),
                        end = pos,
                        strokeWidth = 2.dp.toPx(),
                        alpha = if (member.status == "working") pulseAlpha else 0.3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }
            }
            
            // Draw center hub
            drawCircle(
                color = Blue,
                radius = 28.dp.toPx(),
                center = Offset(centerX, centerY),
                alpha = if (state.runStatus == "running") pulseAlpha else 0.5f
            )
        }
        
        // Center "SWARM" node
        Box(
            Modifier
                .offset(x = (centerX / density).dp - 28.dp, y = (centerY / density).dp - 28.dp)
                .size(56.dp)
                .background(Blue, CircleShape)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("SWARM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        
        // Agent nodes
        positions.forEach { (member, pos) ->
            val x = (pos.x / density).dp - 32.dp
            val y = (pos.y / density).dp - 32.dp
            
            AgentNode(
                member = member,
                online = online,
                pulseAlpha = pulseAlpha,
                onClick = { onAgentClick(member) },
                modifier = Modifier.offset(x, y)
            )
        }
    }
}

@Composable
fun AgentNode(member: TeamMember, online: Boolean, pulseAlpha: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val agentColor = AgentColors[member.identity.id] ?: Color.Gray
    val statusColor = when (member.status) {
        "working" -> Color(0xFF10B981)
        "waiting" -> Color(0xFFF59E0B)
        "blocked" -> Color(0xFFEF4444)
        "failed" -> Color(0xFFDC2626)
        else -> Color(0xFF94A3B8)
    }
    
    Column(
        modifier = modifier
            .width(64.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(agentColor.copy(alpha = 0.15f), CircleShape)
                .border(
                    2.dp,
                    if (member.status == "working" || member.status == "waiting") agentColor.copy(alpha = pulseAlpha) else agentColor.copy(alpha = 0.4f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Agent initial/icon
            Text(
                member.identity.name.first().toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = agentColor
            )
            
            // Status indicator
            if (online && member.status != "idle") {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(14.dp)
                        .background(statusColor, CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
        
        Spacer(Modifier.height(4.dp))
        
        Text(
            member.identity.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        
        if (member.task.isNotBlank()) {
            Text(
                member.task,
                fontSize = 9.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.alpha(if (member.status == "working") pulseAlpha else 0.6f)
            )
        }
    }
}

@Composable
fun AgentListView(state: CompanionState, online: Boolean, onAgentClick: (TeamMember) -> Unit) {
    val working = state.team.filter { it.status != "idle" }
    val idle = state.team.filter { it.status == "idle" }
    
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (working.isNotEmpty()) {
            item {
                Text(
                    "ACTIVE (${working.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(working, key = { it.identity.id }) { member ->
                AgentListItem(member, online, onAgentClick)
            }
        }
        
        if (idle.isNotEmpty()) {
            item {
                Text(
                    "IDLE (${idle.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Muted,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(idle, key = { it.identity.id }) { member ->
                AgentListItem(member, online, onAgentClick)
            }
        }
    }
}

@Composable
fun AgentListItem(member: TeamMember, online: Boolean, onAgentClick: (TeamMember) -> Unit) {
    val agentColor = AgentColors[member.identity.id] ?: Color.Gray
    val statusColor = when (member.status) {
        "working" -> Color(0xFF10B981)
        "waiting" -> Color(0xFFF59E0B)
        "blocked" -> Color(0xFFEF4444)
        "failed" -> Color(0xFFDC2626)
        else -> Color(0xFF94A3B8)
    }
    
    SoftCard(
        Modifier
            .fillMaxWidth()
            .clickable { onAgentClick(member) }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                Modifier
                    .size(48.dp)
                    .background(agentColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    member.identity.name.first().toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = agentColor
                )
                
                if (online && member.status != "idle") {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(statusColor, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
            }
            
            Column(Modifier.weight(1f)) {
                Text(member.identity.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(member.identity.role, fontSize = 12.sp, color = Muted)
                if (member.task.isNotBlank()) {
                    Text(member.task, fontSize = 12.sp, color = Color(0xFF64748B), maxLines = 1)
                }
                if (member.model.isNotBlank()) {
                    Text(member.model, fontSize = 10.sp, color = Muted, maxLines = 1)
                }
            }
            
            if (member.status != "idle") {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
