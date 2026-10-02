package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/**
 * 断句窗口（jude 移植适配，batch 9）：目标仓的 ASRState 没有 extv 的 ASRVoiceTurn
 * （服务端 VAD 断句信号），故改为客户端判定：转写文本出现后，静默 [TURN_END_SILENCE_MS]
 * 无新增即视为一句说完。窗口需大于各供应商服务端 VAD 的提交间隔（OpenAI 500ms /
 * DashScope 800ms），又不能长到影响响应。
 */
private const val TURN_END_SILENCE_MS = 1_200L

/** Capture and generation run independently. Only TTS owns an exclusive microphone pause. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    private sealed interface Event {
        data class Utterance(val text: String) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception) : Event
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
    ) {
        if (job?.isCompleted == false) return
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = scope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        error = when (e) {
                            is TimeoutCancellationException -> getString(R.string.ui3_voice_asr_timeout)
                            is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: getString(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                stopSpeaking()
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null

        // Await replies in submission order, without blocking capture. Queue removal returns null.
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                // Finish any sentence already in progress before giving TTS the microphone pause.
                // 适配（batch 9）：目标仓无 voiceTurn.itemId，改用「转写为空 = 尚未开口」判定；
                // 每轮聆听都是全新 controller，转写文本只包含本轮话语，不会残留上一轮内容。
                val listeningAsr = asr
                if (speak != null && replies.isNotEmpty() &&
                    (listeningAsr == null || listeningAsr.state.value.transcript.isBlank())
                ) {
                    capture?.cancelAndJoin()
                    capture = null
                    asr = null
                    mutableState.update { it.copy(phase = VoicePhase.Speaking) }
                    speak(replies.removeFirst())
                    delay(300) // Let the loudspeaker's tail decay before opening the microphone.
                    continue
                }
                if (capture == null) {
                    mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "") }
                    val recorder = createAsr()
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.send(Event.Utterance(listen(recorder)))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e))
                        }
                    }
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        capture?.join()
                        capture = null
                        asr = null
                        if (event.text.isNotBlank()) {
                            val reply = enqueueMessage(event.text)
                            mutableState.update { it.copy(transcript = event.text, pendingReplies = it.pendingReplies + 1) }
                            submitted.send(reply)
                        }
                    }
                    is Event.Reply -> {
                        mutableState.update { it.copy(pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0)) }
                        event.text?.takeIf { speak != null && it.isNotBlank() }?.let { replies.addLast(it) }
                    }
                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            capture?.cancel()
            // Submitted messages belong to the chat queue; leaving voice mode only detaches observers.
            submitted.close()
        }
    }

    /**
     * 目标仓 ASR 桥接（jude 移植适配，batch 9）：extv 依赖 `voiceTurn.speechEnded` /
     * `voiceTurn.finalText` / `pauseCapture()`，目标仓 ASRController/ASRState 均无这些成员，
     * 故按目标仓 API 重写为「转写稳定即断句」：首轮转写出现前只等待（整体受 120s 预算约束），
     * 转写出现后，静默 [TURN_END_SILENCE_MS] 无变化即收句。状态机相位语义与 extv 一致。
     */
    private suspend fun listen(asr: ASRController): String {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also {
                    check(it.status == ASRStatus.Listening) { "Unable to start speech recognition" }
                }
            }
            val ended = withTimeout(120_000) {
                var text = ""
                var hasSpeech = false
                while (true) {
                    val next = if (hasSpeech) {
                        withTimeoutOrNull(TURN_END_SILENCE_MS) {
                            asr.state.first { state ->
                                check(state.errorMessage == null) { state.errorMessage.orEmpty() }
                                check(state.status == ASRStatus.Listening || state.status == ASRStatus.Stopping) {
                                    getString(R.string.ui3_voice_asr_disconnected)
                                }
                                state.transcript != text
                            }
                        } ?: return@withTimeout text
                    } else {
                        asr.state.first { state ->
                            check(state.errorMessage == null) { state.errorMessage.orEmpty() }
                            check(state.status == ASRStatus.Listening || state.status == ASRStatus.Stopping) {
                                getString(R.string.ui3_voice_asr_disconnected)
                            }
                            state.transcript.isNotBlank()
                        }
                    }
                    text = next.transcript
                    hasSpeech = text.isNotBlank()
                    mutableState.update { current ->
                        current.copy(phase = VoicePhase.Listening, transcript = text)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                text
            }
            // 目标仓无 pauseCapture()：转写已稳定，直接收句并释放（dispose 内含 stop）。
            mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended) }
            return ended
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        job?.cancel()
        mutableState.value = VoiceSessionState()
    }
}
