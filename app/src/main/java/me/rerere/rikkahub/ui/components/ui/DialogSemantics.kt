package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * 弹窗 / 底部表等**独立窗口**的 ComposeView 根上没有 RouteActivity 那个
 * `testTagsAsResourceId = true`（它只挂在 `RouteActivity.kt:429` 的主窗口根上），
 * 因此弹窗里的 `Modifier.testTag` 不会落成无障碍 resource-id。
 *
 * 2026-10-15 自检 §三实测：弹窗打开时 `find_node by=view_id_resource_name` 查
 * `compress_dialog_*` 四个 id **全空**，而同一 App 主窗口的 `chat_input` /
 * `chat_send_button` 能正常读到 —— 机制本身是通的，只是没覆盖到弹窗窗口。
 *
 * 用法：给弹窗自己的根容器套 [DialogTestTagsAsResourceId]，需要被自动化定位的控件用
 * [testTagInDialog]（两者都加，兼容"只看节点自身 config"与"只看窗口根"两种实现）。
 */
val DialogTestTagsAsResourceId: Modifier
    get() = Modifier.semantics { testTagsAsResourceId = true }

/** 弹窗内控件的稳定定位点：`testTag` + 同节点开启 resource-id 暴露。 */
fun Modifier.testTagInDialog(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }
