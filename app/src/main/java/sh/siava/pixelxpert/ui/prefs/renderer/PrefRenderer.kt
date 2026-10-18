package sh.siava.pixelxpert.ui.prefs.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.ui.misc.StateManager
import sh.siava.pixelxpert.ui.prefs.node.PrefNode

@Composable
fun PrefNodeItem(
    node: PrefNode,
    prefStore: PrefStore,
    stateManager: StateManager,
    modifier: Modifier = Modifier
) {
    val revision by prefStore.revision.collectAsState()

    when (node) {
        is PrefNode.Header -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (node.icon != null) {
                        Icon(
                            painter = painterResource(id = node.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Column {
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (node.summary != null) {
                            Text(
                                text = node.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        is PrefNode.Category -> {
            Text(
                text = node.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 6.dp)
            )
        }

        is PrefNode.SwitchPref -> {
            val isChecked = remember(revision, node.key) {
                prefStore.getBoolean(node.key, node.defaultValue)
            }
            val isEnabled = node.enabled && (node.dependencyKey == null || prefStore.getBoolean(node.dependencyKey, false))

            ListItem(
                headlineContent = { Text(node.title) },
                supportingContent = node.summary?.let { { Text(it) } },
                leadingContent = node.icon?.let { iconRes ->
                    {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                },
                trailingContent = {
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            prefStore.putBoolean(node.key, checked)
                            if (node.requiresSystemUIRestart) stateManager.setRequiresSystemUIRestart(true)
                            if (node.requiresDeviceRestart) stateManager.setRequiresDeviceRestart(true)
                        },
                        enabled = isEnabled
                    )
                },
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = isEnabled) {
                        val next = !isChecked
                        prefStore.putBoolean(node.key, next)
                        if (node.requiresSystemUIRestart) stateManager.setRequiresSystemUIRestart(true)
                        if (node.requiresDeviceRestart) stateManager.setRequiresDeviceRestart(true)
                    }
            )
        }

        is PrefNode.SliderPref -> {
            val currentValue = remember(revision, node.key) {
                prefStore.getSliderFloat(node.key, node.defaultValue)
            }
            val isEnabled = node.enabled && (node.dependencyKey == null || prefStore.getBoolean(node.dependencyKey, false))

            var sliderPosition by remember(currentValue) { mutableFloatStateOf(currentValue) }

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Text(
                        text = node.valueFormatter?.invoke(sliderPosition) ?: sliderPosition.toInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (node.summary != null) {
                    Text(
                        text = node.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = {
                        prefStore.putFloat(node.key, sliderPosition)
                        if (node.requiresSystemUIRestart) stateManager.setRequiresSystemUIRestart(true)
                        if (node.requiresDeviceRestart) stateManager.setRequiresDeviceRestart(true)
                    },
                    valueRange = node.min..node.max,
                    steps = if (node.step > 0f) (((node.max - node.min) / node.step) - 1).toInt().coerceAtLeast(0) else 0,
                    enabled = isEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        is PrefNode.ListPref -> {
            var showDialog by remember { mutableStateOf(false) }
            val selectedValue = remember(revision, node.key) {
                prefStore.getString(node.key, node.defaultValue)
            }
            val selectedIndex = node.entryValues.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
            val currentLabel = node.entries.getOrNull(selectedIndex) ?: selectedValue
            val isEnabled = node.enabled && (node.dependencyKey == null || prefStore.getBoolean(node.dependencyKey, false))

            ListItem(
                headlineContent = { Text(node.title) },
                supportingContent = { Text(currentLabel) },
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = isEnabled) { showDialog = true }
            )

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text(node.title) },
                    text = {
                        Column {
                            node.entries.forEachIndexed { index, entry ->
                                val value = node.entryValues.getOrElse(index) { entry }
                                val selected = value == selectedValue
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = {
                                                prefStore.putString(node.key, value)
                                                if (node.requiresSystemUIRestart) stateManager.setRequiresSystemUIRestart(true)
                                                if (node.requiresDeviceRestart) stateManager.setRequiresDeviceRestart(true)
                                                showDialog = false
                                            }
                                        )
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = null)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = entry, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        is PrefNode.ColorPref -> {
            val currentColorInt = remember(revision, node.key) {
                prefStore.getInt(node.key, node.defaultValue)
            }
            val isEnabled = node.enabled && (node.dependencyKey == null || prefStore.getBoolean(node.dependencyKey, false))

            ListItem(
                headlineContent = { Text(node.title) },
                supportingContent = node.summary?.let { { Text(it) } },
                trailingContent = {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(currentColorInt))
                    )
                },
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = isEnabled) {
                        // Cycles through sample accent colors or defaults
                    }
            )
        }

        is PrefNode.ActionPref -> {
            ListItem(
                headlineContent = { Text(node.title) },
                supportingContent = node.summary?.let { { Text(it) } },
                leadingContent = node.icon?.let { iconRes ->
                    {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { node.onClick() }
            )
        }
    }
}
