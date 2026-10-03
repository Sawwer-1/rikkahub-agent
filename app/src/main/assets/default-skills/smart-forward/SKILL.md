---
name: smart-forward
description: 捕获某个应用的通知，并将其内容转发给另一应用中的联系人（通常是 Telegram），附上一句收件人可直接照做的摘要。适用于分享验证码、快递追踪更新、新闻提醒，或"你看到这个了吗"这类时刻。
allowed-tools: list_recent_notifications notification_action_click launch_app read_window_tree find_node click_node set_text take_screenshot telegram_send_message global_action
---

# 智能转发

从应用 A 取一条通知，把其实质内容转发给应用 B 中的联系人。在顶部加一句摘要，让收件人不必自己去解读原文。

## 适用场景

- "把这封邮件转发给 <person>"
- "把刚收到的快递单号发给 <person>"
- "告诉 <person> 我刚收到的验证码"（小心——见禁止事项一节）
- 收到一条匹配用户自定义转发规则的通知（工作流触发器）

## 步骤

1. **选定来源通知。** `list_recent_notifications` —— 找到用户所指的那条（如果说的是 "this" / "that"，就取最新一条）。记下发件人、可见的正文文本和来源应用包。
2. **判断正文是否需要补全。** 通知通常会被截断到 80-120 个字符。如果用户想转发完整内容，就打开应用：对这条通知的主操作调用 `notification_action_click`，再对打开的界面 `read_window_tree`，以文本形式取出完整消息正文。
3. **撰写摘要。** 一句话。例如：
   - 快递通知："你的包裹正在派送——预计今天 18:00 前送达。"
   - 新闻提醒："路透社：<headline>。"
   - 邮件："<sender> 给你发了 <subject>——<one-line gist>。"
   - 验证码：绝不转发——见禁止事项。
4. **选择目的地。**
   - 如果用户说"用 Telegram 发给 <name>"：`telegram_send_message(chat_id = <name's chat id from whitelist>, text = <summary + body>)`。
   - 如果用户说的是短信联系人 / 非 Telegram 的聊天应用：打开对应应用，用 `find_node` 找到联系人，打开会话，通过 `set_text` + 点击发送按钮完成粘贴发送。
5. **确认。** 回复用户"已转发给 <person>" + 一句预览。
6. **回到主屏。** `global_action(action = "home")`。

## 用到的工具

- `list_recent_notifications`、`notification_action_click`
- `launch_app`、`read_window_tree`、`find_node`、`click_node`、`set_text`
- `take_screenshot`（仅用于调试）
- `telegram_send_message`
- `global_action`

## 异常情形

- **来源应用未在无障碍树中暴露正文。** 一些银行 / 两步验证应用会刻意隐藏内容。只转发通知文本，并说明"应用把其余内容藏起来了——要看完整内容请在手机上打开它"。
- **目标联系人有歧义。** "Send Anna the article"——如果白名单里有两个 Anna，先问用户是哪一个。不要猜。
- **来源通知已被清除。** 它还会在最近通知环形缓冲区里保留几分钟——可以从那里着手，但缓存的预览可能已被截断。

## 禁止事项

- **绝不转发验证码 / 校验码 / 密码重置链接**，除非用户自己明确打出验证码并要求你转达。这些东西存在的全部意义，就是不该多传一手。如果用户要求"把 OTP 发给 Bob"，用"我不会自动转发验证码——它只该给你本人看"拒绝。
- **绝不把通知完整正文转发到用户白名单之外的聊天。** 白名单里的聊天是明确的；其余一律不行。
- 不要对同一内容重复"总结 + 转发"——如果用户已经回复过一次 "forward it"，下一条 "yes" 不要再次触发。
