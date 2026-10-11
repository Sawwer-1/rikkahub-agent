package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Mini App config: a user-added web page surfaced inside the chat input sheet, Telegram-style.
 * Stored in the settings JSON store (no Room table), mirroring the groupChats list pattern.
 *
 * @param id unique identifier
 * @param name display name
 * @param url web page loaded in the in-app WebView
 * @param icon optional image URL; blank falls back to a favicon lookup of [url]
 * @param description optional note shown in the manager page
 * @param order sort weight (lower first)
 */
@Serializable
data class MiniApp(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val url: String = "",
    val icon: String = "",
    val description: String = "",
    val order: Int = 0,
) {
    companion object
}
