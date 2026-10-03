package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

private val ELEVEN_LABS_AUDIO_TAG_REGEX = Regex("""\[[A-Za-z][A-Za-z0-9 ,.'!?-]{0,48}]""")
private val VOICE_CALL_AUDIO_TAG_REGEX =
    Regex("""(?:\[[A-Za-z][A-Za-z0-9 ,.'!?-]{0,48}]|\([A-Za-z][A-Za-z0-9 ,.'!?-]{0,48}\))""")

val LocalElevenLabsAudioTagAnnotations = staticCompositionLocalOf { false }

internal fun AnnotatedString.Builder.appendElevenLabsAudioTagAwareText(
    text: String,
    colorScheme: ColorScheme,
) {
    var cursor = 0
    ELEVEN_LABS_AUDIO_TAG_REGEX.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(
            SpanStyle(
                color = colorScheme.onPrimaryContainer,
                background = colorScheme.primaryContainer.copy(alpha = 0.65f),
                fontStyle = FontStyle.Italic,
            )
        ) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

internal fun AnnotatedString.Builder.appendVoiceCallAudioTagAwareText(
    text: String,
    colorScheme: ColorScheme,
) {
    var cursor = 0
    VOICE_CALL_AUDIO_TAG_REGEX.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(
            SpanStyle(
                color = colorScheme.onPrimaryContainer,
                background = colorScheme.primaryContainer.copy(alpha = 0.65f),
                fontStyle = FontStyle.Italic,
            )
        ) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
