package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): TTSProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.6-turbo",
        val voiceId: String = "female-shaonv",
        val emotion: String = "calm",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("qwen")
    data class Qwen(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Qwen TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        val model: String = "qwen3-tts-flash",
        val voice: String = "Cherry",
        val languageType: String = "Auto"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("groq")
    data class Groq(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Groq TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.groq.com/openai/v1",
        val model: String = "canopylabs/orpheus-v1-english",
        val voice: String = "austin"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("xai")
    data class XAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "xAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.x.ai/v1",
        val voiceId: String = "eve",
        val language: String = "auto"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("mimo")
    // 默认值仅用于快捷起步 可在设置页任意修改
    data class MiMo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2-tts",
        val voice: String = "mimo_default"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("aura")
    data class Aura(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Aura TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://tts.aurastd.com/api/v1",
        val model: String = "speech-2.8-turbo",
        val voiceId: String = "female-shaonv",
        val emotion: String = "neutral",
        val speed: Float = 1.0f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.elevenlabs.io",
        val model: String = "eleven_v3",
        val voiceId: String = "",
        val languageCode: String? = null,
        val stability: Float = 0.5f,
        val similarityBoost: Float = 0.75f,
        val useSpeakerBoost: Boolean = true,
        val style: Float = 0.0f,
        val speed: Float = 1.0f,
    ) : TTSProviderSetting() {
        companion object {
            const val MIN_STABILITY = 0.0f
            const val MAX_STABILITY = 1.0f
            const val MIN_SIMILARITY_BOOST = 0.0f
            const val MAX_SIMILARITY_BOOST = 1.0f
            const val MIN_STYLE = 0.0f
            const val MAX_STYLE = 1.0f
            const val MIN_SPEED = 0.7f
            const val MAX_SPEED = 1.2f
        }

        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("generic_http")
    data class GenericHttp(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Generic HTTP TTS",
        val endpoint: String = "",
        val method: GenericHttpMethod = GenericHttpMethod.POST,
        val bodyEncoding: GenericHttpBodyEncoding = GenericHttpBodyEncoding.JSON,
        val bodyTemplate: String = "{\"text\":\"{{text}}\"}",
        val headers: List<GenericHttpHeader> = emptyList(),
        val responseMode: GenericHttpResponseMode = GenericHttpResponseMode.RAW_AUDIO,
        val responseJsonPath: String = "audio",
        val audioFormat: me.rerere.tts.model.AudioFormat = me.rerere.tts.model.AudioFormat.MP3,
        val voice: String = "",
        val language: String = "",
        val allowPrivateNetwork: Boolean = false,
        val maxResponseBytes: Int = 16 * 1024 * 1024,
        @kotlinx.serialization.Transient val runtimeSecret: String = "",
    ) : TTSProviderSetting() {
        override fun copyProvider(id: Uuid, name: String): TTSProviderSetting = copy(id = id, name = name)
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                Aura::class,
                Qwen::class,
                Groq::class,
                XAI::class,
                ElevenLabs::class,
                MiMo::class,
                GenericHttp::class,
            )
        }
    }
}

@Serializable
enum class GenericHttpMethod { GET, POST }

@Serializable
enum class GenericHttpBodyEncoding { JSON, FORM }

@Serializable
enum class GenericHttpResponseMode { RAW_AUDIO, BASE64_JSON, URL_JSON }

@Serializable
data class GenericHttpHeader(
    val name: String,
    /** Safe literal/template. Use {{secret}} for the bound Vault slot. */
    val valueTemplate: String,
)

fun TTSProviderSetting.isElevenLabsV3(): Boolean {
    if (this !is TTSProviderSetting.ElevenLabs) return false
    return model.trim().equals("eleven_v3", ignoreCase = true) ||
        model.trim().equals("eleven_multilingual_v3", ignoreCase = true)
}
