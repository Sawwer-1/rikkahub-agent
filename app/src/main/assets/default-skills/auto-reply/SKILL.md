---
name: auto-reply
description: 代替用户回复任意聊天应用中的来信。读取可见对话，起草符合语境的回复并发送。组合了通知监听、无障碍点击/滚动/读取工具，以及返回主屏的全局操作。
allowed-tools: list_recent_notifications list_active_notifications launch_app read_window_tree find_node click_node set_text scroll global_action take_screenshot
---

# 自动回复

在来信所在的聊天应用内直接回复，不打乱用户正在做的事。

## 适用场景

用户说"帮我回复 <person>"、"给 <person> 发'我马上到'"、"给最后一条消息起草一条回复"之类的话，或者你在 `list_recent_notifications` 中发现一条未读聊天（Telegram / WhatsApp / Signal / Messages / Slack），且用户已要求你自主处理回复。

如果用户只是想给一个还没出现过的联系人发一条全新的消息，不要使用本技能——那种情况用 `telegram_send_message`（仅限 Telegram），或自己打开对应应用。

## 步骤

1. **确认来信会话。** 调用 `list_recent_notifications`，挑选最新一条未读、且应用包为聊天应用的通知。记下 `package_name`、`title`（通常是联系人名）、`text`（最后一条消息的预览）、`key`。
2. **打开应用。** 如果可用，用 `notification_action_click` 触发该条通知的主操作；否则 `launch_app(package_name = "<x>")`，应用会落在聊天列表页。
3. **打开与联系人的会话。** 读取 `read_window_tree`，用 `find_node` 找到联系人名字的文本节点，`click_node`。如果会话已经打开，跳过此步。
4. **读取可见上下文。** 在聊天界面调用 `read_window_tree`。如果最近几条消息不在可见范围内，用 `scroll(direction = "up")` 向上滚动一次。从树里取出最近 3-5 条消息的纯文本。
5. **起草回复。** 匹配用户的语气（你有记忆；检查 `enableMemory`）。保持简短。如果来信是问题，就回答；如果是状态更新，就回应；如果是请求，判断用户现在能否完成，还是需要推迟。
6. **发送。** 用 `find_node` 找到消息输入框，`set_text` 写入草稿，用 `find_node` 找到发送按钮（形似纸飞机 / 箭头），`click_node`。
7. **确认。** 截一张 `take_screenshot`，让用户能在聊天记录中核对。
8. **回到主屏。** `global_action(action = "home")`。

## 用到的工具

- `list_recent_notifications`、`list_active_notifications`
- `launch_app`、`notification_action_click`
- `read_window_tree`、`find_node`、`click_node`、`set_text`、`scroll`
- `take_screenshot`
- `global_action`

## 异常情形

- **找不到输入框。** 有些应用把发消息渲染在弹窗浮层里。稍等片刻再试一次 `read_window_tree`；仍找不到就中止，并告诉用户"我打开了聊天，但找不到输入框——请手动回复"。
- **发送按钮是灰的。** 草稿多半没设置成功。再试一次 `set_text`；仍是灰的就中止。
- **打开的联系人不对。** 如果 `find_node` 匹配到了预期之外的人（名字相近时常见），用 `global_action(action = "back")` 退回，改用搜索框。
- **通知已被清除。** `list_recent_notifications` 是 100 条容量的环形缓冲区——较早的条目在清除后仍可见。不要轻信会话就在最顶部，始终以打开应用后的实际情况为准。

## 禁止事项

- 未经明确确认，不要代替任何人在群聊中回复——太容易出洋相。
- 不要发送含有用户真实个人信息（全名、电话、地址）的内容，除非用户自己打出来。
- 发送前不要先把对话总结回给用户，除非用户要求你先确认草稿。
