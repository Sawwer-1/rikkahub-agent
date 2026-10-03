package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
internal fun ChatVoiceReplyTextSegment(
    text: String,
    assistant: Assistant?,
    loading: Boolean,
    onTtsSpeak: ((String) -> Unit)?,
) {
    val settings = LocalSettings.current.displaySetting
    val content = text.replaceRegexes(
        assistant = assistant,
        scope = AssistantAffectScope.ASSISTANT,
        visual = true,
    )
    // jude 原版经 AssistantTextContent 渲染（支持段落级 TTS 按钮与 moments 气泡模式）；
    // 本 fork 没有该组件：AssistantTextParagraphs/splitAssistantTextSegments 不存在，
    // Assistant 也无 momentsChatStyle 字段，showParagraphTtsButtons 在 DisplaySetting 中有声明
    // 但全仓无消费者。故按本仓 ChatMessage.kt 的 assistant 文本路径用 MarkdownBlock 渲染，
    // 气泡底色取本仓 assistant 气泡同款 surfaceContainerHigh + bubbleOpacity；
    // 段落级 TTS 按钮略去（onTtsSpeak 参数保留以兼容两处既有调用点，当前未使用）。
    val textContent: @Composable () -> Unit = {
        if (settings.showAssistantBubble) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
                    .copy(alpha = settings.bubbleOpacity),
            ) {
                Column(Modifier.padding(8.dp)) {
                    MarkdownBlock(content = content, onClickCitation = {})
                }
            }
        } else {
            MarkdownBlock(content = content, onClickCitation = {})
        }
    }
    // 与 ChatMessage.kt 一致：流式生成期间不启用文本选择，稳定后再启用。
    if (loading) {
        textContent()
    } else {
        SelectionContainer {
            textContent()
        }
    }
}
