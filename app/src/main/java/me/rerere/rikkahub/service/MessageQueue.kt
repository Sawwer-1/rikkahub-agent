package me.rerere.rikkahub.service

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

@Serializable
private data class QueuedMessageBackup(
    val id: Uuid,
    val parts: List<UIMessagePart>,
    val answer: Boolean,
)

class MessageQueue(
    // 文件式持久化（jude 移植，batch 9）：extv 原版纯内存；按任务书补 JSON 快照，
    // 进程被杀后未派发的排队消息不丢。reply 观察者不持久化，重载后按未挂观察者处理。
    storageFile: File? = null,
) {
    private val backupFile: File? = storageFile
    private val backupJson = Json { ignoreUnknownKeys = true }

    private val mutableState = MutableStateFlow(MessageQueueState(messages = loadBackup()))
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
            ),
        )
        persist()
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        persist()
        return next
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        persist()
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        persist()
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        persist()
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }

    private fun persist() {
        val file = backupFile ?: return
        runCatching {
            val messages = state.value.messages
            if (messages.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(
                    backupJson.encodeToString(
                        messages.map { QueuedMessageBackup(id = it.id, parts = it.parts, answer = it.answer) },
                    ),
                )
            }
        }
    }

    private fun loadBackup(): List<QueuedMessage> {
        val file = backupFile ?: return emptyList()
        if (!file.isFile) return emptyList()
        // 编辑态是短暂的对话框占位，进程死亡后无编辑者，一律按可派发恢复，避免卡死队列。
        return runCatching {
            backupJson.decodeFromString<List<QueuedMessageBackup>>(file.readText())
                .map { QueuedMessage(id = it.id, parts = it.parts, answer = it.answer) }
        }.getOrDefault(emptyList())
    }
}
