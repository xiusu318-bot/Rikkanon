package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.ViewOffSlash
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SecretKeyEntry
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SecretKeysPage() {
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var editingEntry by remember { mutableStateOf<SecretKeyEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var revealedIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }

    fun updateSecretKeys(nextKeys: List<SecretKeyEntry>) {
        scope.launch {
            settingsStore.update { it.copy(secretKeys = nextKeys) }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("密钥代名词") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = "添加密钥")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = settings.enableSecretKeyAlias,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                settingsStore.update { it.copy(enableSecretKeyAlias = enabled) }
                            }
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Key01,
                                containerColor = if (settings.enableSecretKeyAlias) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (settings.enableSecretKeyAlias) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        headlineContent = { Text("启用密钥代名词替换") },
                        supportingContent = {
                            Text("开启后，模型工具调用中的代名词会自动替换为真实密钥；同时在助手本地工具中开放「询问密钥」开关")
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text("密钥列表 (${settings.secretKeys.size})") }
                ) {
                    settings.secretKeys.forEach { entry ->
                        val isRevealed = entry.id in revealedIds
                        item(
                            onClick = { editingEntry = entry },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Key01,
                                    containerColor = if (entry.enabled) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (entry.enabled) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = entry.keyName,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = if (isRevealed) entry.secretValue else "••••••••",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (entry.description.isNotBlank()) {
                                        Text(
                                            text = entry.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            revealedIds = if (isRevealed) revealedIds - entry.id else revealedIds + entry.id
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isRevealed) HugeIcons.ViewOffSlash else HugeIcons.Eye,
                                            contentDescription = "显示/隐藏密钥",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            updateSecretKeys(settings.secretKeys.filterNot { it.id == entry.id })
                                        }
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = "删除密钥",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Checkbox(
                                        checked = entry.enabled,
                                        onCheckedChange = { checked ->
                                            updateSecretKeys(
                                                settings.secretKeys.map {
                                                    if (it.id == entry.id) it.copy(enabled = checked) else it
                                                }
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }

                    item(
                        onClick = { showCreateDialog = true },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Add01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = { Text("添加密钥") },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        SecretKeyEditDialog(
            initialEntry = null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { newEntry ->
                val updated = settings.secretKeys.filterNot { it.keyName == newEntry.keyName } + newEntry
                updateSecretKeys(updated)
                showCreateDialog = false
            },
        )
    }

    editingEntry?.let { target ->
        SecretKeyEditDialog(
            initialEntry = target,
            onDismiss = { editingEntry = null },
            onConfirm = { updatedEntry ->
                updateSecretKeys(
                    settings.secretKeys.map {
                        if (it.id == target.id) updatedEntry else it
                    }
                )
                editingEntry = null
            },
        )
    }
}

@Composable
fun SecretKeyEditDialog(
    initialEntry: SecretKeyEntry?,
    onDismiss: () -> Unit,
    onConfirm: (SecretKeyEntry) -> Unit,
) {
    var keyName by remember(initialEntry) { mutableStateOf(initialEntry?.keyName ?: "") }
    var secretValue by remember(initialEntry) { mutableStateOf(initialEntry?.secretValue ?: "") }
    var description by remember(initialEntry) { mutableStateOf(initialEntry?.description ?: "") }
    var showSecret by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEntry == null) "添加密钥" else "编辑密钥") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = keyName,
                    onValueChange = { keyName = it },
                    label = { Text("代名词 (key_name)") },
                    placeholder = { Text("如: MY_SSH_PASS") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = secretValue,
                    onValueChange = { secretValue = it },
                    label = { Text("真实密钥") },
                    singleLine = false,
                    maxLines = 4,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) HugeIcons.ViewOffSlash else HugeIcons.Eye,
                                contentDescription = "切换密码可见性",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = keyName.isNotBlank() && secretValue.isNotEmpty(),
                onClick = {
                    onConfirm(
                        SecretKeyEntry(
                            id = initialEntry?.id ?: Uuid.random(),
                            keyName = keyName.trim(),
                            secretValue = secretValue,
                            description = description.trim(),
                            enabled = initialEntry?.enabled ?: true,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
