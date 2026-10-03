package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.ripple.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Translate
import me.rerere.rikkahub.R
import java.util.Locale

/**
 * 翻译按钮（jude 移植，供语音条/通话浮层复用；依赖 LanguageSelectionDialog 由本仓 ChatMessageTranslation.kt 提供）。
 */
@Composable
fun TranslateMessageButton(
    onTranslate: (Locale) -> Unit,
    onClearTranslation: () -> Unit = {},
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showLabel: Boolean = false,
    defaultLanguage: Locale? = null,
) {
    var showTranslateDialog by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier = modifier
        .clip(CircleShape)
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = {
                if (defaultLanguage != null) {
                    onTranslate(defaultLanguage)
                } else {
                    showTranslateDialog = true
                }
            },
        )

    if (showLabel) {
        Row(
            modifier = clickableModifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Translate,
                contentDescription = stringResource(R.string.translate),
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            Text(
                text = stringResource(R.string.translate),
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    } else {
        Icon(
            imageVector = HugeIcons.Translate,
            contentDescription = stringResource(R.string.translate),
            modifier = clickableModifier
                .padding(8.dp)
                .size(16.dp),
            tint = tint,
        )
    }

    if (showTranslateDialog) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation()
            },
            onDismissRequest = { showTranslateDialog = false },
        )
    }
}
