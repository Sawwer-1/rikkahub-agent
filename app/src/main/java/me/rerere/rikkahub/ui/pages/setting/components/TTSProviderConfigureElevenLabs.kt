package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.tts.provider.TTSProviderSetting

// ElevenLabs TTS configuration (ported from jude snapshot)

private data class ElevenLabsModelOption(
    val id: String,
    val name: String,
)

private data class TtsLanguageOption(
    val labelRes: Int,
    val value: String?,
)

private val elevenLabsModelOptions = listOf(
    ElevenLabsModelOption(
        id = "eleven_multilingual_v2",
        name = "Eleven Multilingual v2",
    ),
    ElevenLabsModelOption(
        id = "eleven_v3",
        name = "Eleven v3",
    ),
)

private val elevenLabsLanguageOptions = listOf(
    TtsLanguageOption(R.string.vc_tts_lang_auto, null),
    TtsLanguageOption(R.string.vc_tts_lang_zh, "zh"),
    TtsLanguageOption(R.string.vc_tts_lang_en, "en"),
    TtsLanguageOption(R.string.vc_tts_lang_ja, "ja"),
    TtsLanguageOption(R.string.vc_tts_lang_ko, "ko"),
    TtsLanguageOption(R.string.vc_tts_lang_fr, "fr"),
    TtsLanguageOption(R.string.vc_tts_lang_de, "de"),
    TtsLanguageOption(R.string.vc_tts_lang_es, "es"),
    TtsLanguageOption(R.string.vc_tts_lang_pt, "pt"),
)

@Composable
internal fun ElevenLabsTTSConfiguration(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.vc_tts_elevenlabs_api_key_desc)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            isError = setting.apiKey.isBlank(),
            placeholder = { Text("xi-xxx") },
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.vc_tts_elevenlabs_base_url_desc)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.vc_tts_elevenlabs_voice_id_desc)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            isError = setting.voiceId.isBlank(),
            placeholder = { Text("21m00Tcm4TlvDq8ikWAM") },
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.vc_tts_elevenlabs_model_desc)) }
    ) {
        val selectedModel = elevenLabsModelOptions.firstOrNull {
            it.id.equals(setting.model, ignoreCase = true) ||
                (it.id == "eleven_v3" && setting.model.equals("eleven_multilingual_v3", ignoreCase = true))
        } ?: elevenLabsModelOptions.first()
        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = {
                modelMenuExpanded = !modelMenuExpanded
            },
        ) {
            OutlinedTextField(
                value = "${selectedModel.name} (${selectedModel.id})",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                elevenLabsModelOptions.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.name)
                                Text(
                                    text = model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onValueChange(setting.copy(model = model.id))
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.vc_tts_language)) },
        description = { Text(stringResource(R.string.vc_tts_language_desc)) }
    ) {
        val selectedLanguage = elevenLabsLanguageOptions.firstOrNull {
            it.value == setting.languageCode
        }
        ExposedDropdownMenuBox(
            expanded = languageMenuExpanded,
            onExpandedChange = { languageMenuExpanded = !languageMenuExpanded },
        ) {
            OutlinedTextField(
                value = selectedLanguage?.let { stringResource(it.labelRes) } ?: setting.languageCode.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false },
            ) {
                elevenLabsLanguageOptions.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(stringResource(language.labelRes)) },
                        onClick = {
                            onValueChange(setting.copy(languageCode = language.value))
                            languageMenuExpanded = false
                        },
                    )
                }
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.vc_tts_stability)) },
        description = { Text(stringResource(R.string.vc_tts_stability_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                if (newStability in TTSProviderSetting.ElevenLabs.MIN_STABILITY..TTSProviderSetting.ElevenLabs.MAX_STABILITY) {
                    onValueChange(setting.copy(stability = newStability))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.vc_tts_stability),
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.vc_tts_similarity_boost)) },
        description = { Text(stringResource(R.string.vc_tts_similarity_boost_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                if (newSimilarityBoost in TTSProviderSetting.ElevenLabs.MIN_SIMILARITY_BOOST..TTSProviderSetting.ElevenLabs.MAX_SIMILARITY_BOOST) {
                    onValueChange(setting.copy(similarityBoost = newSimilarityBoost))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.vc_tts_similarity_boost),
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.vc_tts_speaker_boost)) },
        description = { Text(stringResource(R.string.vc_tts_speaker_boost_desc)) },
        tail = {
            androidx.compose.material3.Switch(
                checked = setting.useSpeakerBoost,
                onCheckedChange = { enabled ->
                    onValueChange(setting.copy(useSpeakerBoost = enabled))
                },
            )
        },
    )

    FormItem(
        label = { Text(stringResource(R.string.vc_tts_style)) },
        description = { Text(stringResource(R.string.vc_tts_style_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.style,
            onValueChange = { newStyle ->
                if (newStyle in TTSProviderSetting.ElevenLabs.MIN_STYLE..TTSProviderSetting.ElevenLabs.MAX_STYLE) {
                    onValueChange(setting.copy(style = newStyle))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.vc_tts_style),
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.vc_tts_speed_desc)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in TTSProviderSetting.ElevenLabs.MIN_SPEED..TTSProviderSetting.ElevenLabs.MAX_SPEED) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}
