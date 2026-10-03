---
name: morning-briefing
display_name: 晨间简报
description: 汇总用户的晨间简报——当前天气、今日日程、未读邮件数、接下来的计划任务，以及任何电量/存储警告。输出一个短段落，让用户能在 10 秒内读完。
allowed-tools: get_time_info get_battery_status get_storage_info list_active_notifications list_recent_notifications get_jobs_history list_call_log get_location launch_app read_window_tree
---

# 晨间简报

用一个短段落告诉用户开始新一天所需的全部信息。

## 适用场景

用户问"我今天什么安排"、"晨间简报"、"早上好，今天有什么议程"、"来个总结"。或者，你在一个每个工作日早上 7 点运行的工作流中触发本技能。

## 步骤

在工具接口允许的范围内并行执行所有读取操作；最后统一汇总。

1. **时间锚点。** `get_time_info` —— 确认本地日期 / 星期。问候语依此决定（"周五早上"还是"周六早上"，或是节日名）。
2. **设备状态。**
   - `get_battery_status` —— 仅在电量 <= 20% 且未充电时提及。
   - `get_storage_info` —— 仅在剩余空间 < 5% 时提及。
3. **通信。**
   - `list_active_notifications`，过滤到用户在 `notification_listener` 设置中列入白名单的应用包——按应用包分组，统计未读数。
   - `list_call_log(type = "missed", limit = 5)` —— 指出用户自上次交互以来错过的来电。
4. **日程 / 天气。** 两者都依赖具体应用。选择用户使用的日历应用（`com.google.android.calendar`、`com.microsoft.office.outlook` 等）——在日视图上 `launch_app` + `read_window_tree`，以文本形式取出今天的日程。天气同理：通过厂商天气应用或用户偏好的应用（Pixel Weather、Google、AccuWeather）。
5. **计划任务。** `get_jobs_history(limit = 5, since_ms = <last 24h>)` —— 指出夜间失败的任务。
6. **撰写段落。** 以问候语 + 日期开头。接着是警告（如有）。然后是会议（如有）。最后是通信摘要。以一句 "anything else?" 收尾，方便用户继续追问。

## 输出形态

- 总计 ≤4 句。不要注水。
- 纯文本——不要 markdown 标题。用户是在手机上阅读，或通过 TTS 收听。
- 如果一切正常（电量正常、存储正常、无未接来电、无紧急通知、日程为空），用一句话说明后结束。

## 异常情形

- **日历应用未安装 / 无障碍视图无法渲染结构化文本。** 跳过会议部分；提示"我读不了你的日历——如果你今天有安排，请自己打开看一下"。
- **天气需要定位而用户拒绝了授权。** 静默跳过；不要在简报场景里为权限问题打扰用户。
- **通知监听被禁用。** 提醒一次："顺便说一句，你的通知监听是关闭的，我看不到应用动态——如果你希望我把这些纳入明天的简报，请在设置中打开它。"

## 禁止事项

- 不要用本技能逐条念出所有通知。如果有 47 封未读邮件，就说"47 封未读邮件"，而不是列 47 行。
- 不要引用任何消息正文——只预览标题 + 发件人。邮件预览里经常含有重置链接、验证码等用户不希望被念出来的内容。
- 不要根据日程元数据揣测用户的一天（"看起来好忙！"）。只陈述事实。
