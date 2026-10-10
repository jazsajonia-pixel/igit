package app.igit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

object IgitColors {
    val Canvas = Color(0xFF111211)
    val Surface = Color(0xFF1D1F20)
    val SurfaceHigh = Color(0xFF292B2D)
    val Accent = Color(0xFF80C7FF)
    val AccentContainer = Color(0xFF142033)
    val Success = Color(0xFF7DE2A2)
    val Danger = Color(0xFFFF8585)
    val Warning = Color(0xFFFFD28D)
    val Muted = Color(0xFF8F969B)
    val Secondary = Color(0xFFADB2B6)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = IgitColors.Muted, modifier = modifier)
}

@Composable
fun IgitCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(colors = CardDefaults.cardColors(containerColor = IgitColors.Surface), shape = RoundedCornerShape(17.dp), modifier = m) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Full-screen page with a back arrow, used for settings and detail screens. */
@Composable
fun PageScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            actions()
        }
        val body = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
        Column(
            modifier = if (scroll) body.verticalScroll(rememberScrollState()) else body,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = { content(); Spacer(Modifier.size(24.dp)) }
        )
    }
}

@Composable
fun SettingSwitch(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = IgitColors.Secondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SettingCheck(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Collapsible settings group (GitSync's App Sync / Scheduled Sync / Quick Sync sections). */
@Composable
fun ExpandableCard(title: String, subtitle: String?, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(colors = CardDefaults.cardColors(containerColor = IgitColors.Surface), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 15.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = IgitColors.Secondary)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = IgitColors.Secondary)
            }
            if (expanded) Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    message: String? = null,
    confirm: String = "Confirm",
    secret: Boolean = false,
    validate: (String) -> String? = { if (it.isBlank()) "Field cannot be empty" else null },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = IgitColors.Secondary)
                OutlinedTextField(
                    value = value, onValueChange = { value = it; error = null }, label = { Text(label) }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(it) } },
                    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { val e = validate(value); if (e != null) error = e else onConfirm(value.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (danger) IgitColors.Danger else IgitColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Text field with the AI "wand" button (GitSync's wand auto-complete). */
@Composable
fun WandTextField(value: String, onValueChange: (String) -> Unit, label: String, showWand: Boolean, onWand: (() -> Unit)?, modifier: Modifier = Modifier, minLines: Int = 1) {
    var working by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, minLines = minLines, modifier = modifier.fillMaxWidth(),
        trailingIcon = if (showWand && onWand != null) ({
            IconButton(onClick = { working = true; onWand(); working = false }) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = "AI wand", tint = IgitColors.Accent)
            }
        }) else null
    )
}

@Composable
fun Monospace(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, maxLines: Int = Int.MAX_VALUE) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier, maxLines = maxLines)
}

@Composable
fun LoadingBox() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
