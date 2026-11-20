package com.example.discordcleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.discordcleaner.data.Account

@Composable
fun Avatar(a: Account?, size: Dp = 26.dp) {
    val color = a?.let { C.avatars[it.colorIdx % C.avatars.size] } ?: C.raised
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(a?.name?.take(1) ?: "?", color = Color(0xFF14151A), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun AccountChip(a: Account?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.surface).clickable(onClick = onClick)
            .padding(start = 3.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(a, 24.dp)
        Spacer(Modifier.width(6.dp))
        Text(a?.name ?: "アカウント", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        if (a?.needsLogin == true) Text(" !", color = C.danger, fontWeight = FontWeight.Bold)
        Icon(Icons.Default.ArrowDropDown, null, tint = C.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, color = C.muted, fontSize = 12.sp, modifier = modifier)

@Composable
fun SectionLabel(text: String) =
    Text(text, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))

@Composable
fun BigButton(text: String, onClick: () -> Unit, color: Color = C.accent, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White, disabledContainerColor = C.raised),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun SubButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.raised, contentColor = C.fg),
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Caption(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f, fill = false))
        Text("  ›", color = C.muted)
    }
}

/** − 値 + の数値入力。 */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, min: Int, max: Int, step: Int = 1, suffix: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−", fontSize = 20.sp) }
        Text("$value$suffix", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("＋", fontSize = 20.sp) }
    }
}

@Composable
fun StepDots(step: Int, count: Int = 3) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) C.accent else C.raised))
        }
    }
}
