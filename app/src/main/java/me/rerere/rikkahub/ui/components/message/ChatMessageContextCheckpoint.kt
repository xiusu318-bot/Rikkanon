package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.SUB_AGENT_INTERRUPT_NOTIFICATION_HEADER
import me.rerere.rikkahub.service.SUB_AGENT_NOTIFICATION_HEADER
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

private val SUB_AGENT_ID_TAG_REGEX = Regex("""### Sub-Agent `([^`]+)`""")

val UIMessage.isSubAgentNotification: Boolean
    get() = role == MessageRole.USER &&
        parts.firstOrNull()?.let {
            it is UIMessagePart.Text && (
                it.text.startsWith(SUB_AGENT_NOTIFICATION_HEADER) ||
                    it.text.startsWith(SUB_AGENT_INTERRUPT_NOTIFICATION_HEADER)
                )
        } == true

/**
 * 压缩检查点：分隔线上方的消息仍可查看，但不再发送给模型，由这里的摘要代替。
 * 默认折叠，避免长摘要打断历史消息的阅读。
 */
@Composable
fun ChatMessageContextCheckpoint(
    message: UIMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Icon(
                imageVector = HugeIcons.Package01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.chat_page_compress_checkpoint_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_checkpoint_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MarkdownBlock(content = message.toText())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.edit))
                        }
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 后台子代理自动完成通知条：默认折叠为紧凑分隔条，点击可展开查看子代理回传详情，并支持重发、编辑或删除。
 */
@Composable
fun ChatMessageSubAgentNotification(
    message: UIMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rawText = message.toText()
    val subIds = SUB_AGENT_ID_TAG_REGEX.findAll(rawText)
        .map { it.groupValues[1] }
        .toList()
    val labelSuffix = if (subIds.isNotEmpty()) " (${subIds.joinToString(", ")})" else ""
    val bodyMarkdown = rawText
        .removePrefix(SUB_AGENT_NOTIFICATION_HEADER)
        .removePrefix(SUB_AGENT_INTERRUPT_NOTIFICATION_HEADER)
        .trim()
    val isWaitingApproval = rawText.contains("- Status: waiting_approval")
    val isErrorOrInterrupted = rawText.contains("- Status: failed") ||
        rawText.contains("- Status: interrupted_by_user") ||
        rawText.startsWith(SUB_AGENT_INTERRUPT_NOTIFICATION_HEADER)
    val bannerTitle = when {
        isWaitingApproval -> "子代理请求工具审批$labelSuffix"
        isErrorOrInterrupted -> "子代理后台任务已中断/异常$labelSuffix"
        else -> "子代理后台任务已完成$labelSuffix"
    }
    val bannerColor = when {
        isWaitingApproval -> MaterialTheme.colorScheme.tertiary
        isErrorOrInterrupted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Icon(
                imageVector = HugeIcons.Message02,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = bannerColor,
            )
            Text(
                text = bannerTitle,
                style = MaterialTheme.typography.labelMedium,
                color = bannerColor,
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = bannerColor,
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarkdownBlock(content = bodyMarkdown)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onRegenerate) {
                            Text("重新触发汇报")
                        }
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.edit))
                        }
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

