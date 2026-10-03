# Tools —— RikkaHub 代理参考

代理可以调用的全部工具，按能力面分组。每个条目列出：它做什么、何时该用它，以及不那么显然的坑。这些工具以开关形式呈现在应用内的*本地工具*页面——用户启用了哪些，决定你运行时实际拥有哪些。这里列出的工具并非总是全部可用；挑出本回合工具列表中实际存在的那些。

## 内置（Phase 0）

- **`eval_javascript`** —— 在 QuickJS 中运行 JS，用于算术、字符串变换、JSON 塑形。没有 Node / DOM。
- **`get_time_info`** —— 日期、星期、ISO 时间、时区、epoch 毫秒。低成本；做任何调度前先调用。
- **`clipboard_tool`** —— 读写设备剪贴板。用户没要求就不要写。
- **`text_to_speech`** —— 朗读文本。立即返回；音频在后台播放。
- **`ask_user`** —— 抛出问题，可附预置选项。当不澄清就继续会浪费工作时使用。

## 设备信息（Phase 1）

- **`get_battery_status`** —— 百分比、充电状态、插头类型、温度。
- **`get_audio_info`** —— 当前音频模式、耳机连接、铃声模式。
- **`get_telephony_info`** —— SIM 运营商、网络类型、信号强度。需要 READ_PHONE_STATE。
- **`get_wifi_info`** —— 当前 SSID、BSSID、IP、信号。需要精确位置权限。
- **`list_sensors`** / **`read_sensor`** —— 枚举并采样任意设备传感器。
- **`get_storage_info`** —— 内部 + 外部存储的可用 / 已用 / 总字节数。

## 输出 / 通知（Phase 1）

- **`show_toast`** —— 短暂浮层；不存储。
- **`post_notification`** —— 系统通知，可带点击 intent。
- **`share`** —— 通过系统分享面板发送字符串 / 文件。

## 硬件控制（Phase 1）

- **`set_torch`** —— 手电筒开/关。
- **`vibrate`** —— 模式或时长。`pattern` 与 `duration_ms` 二选一，不能同时。
- **`get_brightness`** / **`set_brightness`** —— 1..255（工具会把低于 1 的值钳到 1，因为大多数 Android 版本上 brightness=0 不产生可见变化）。用户要"最低亮度"时传 `1`。需要 WRITE_SETTINGS。
- **`get_volume`** / **`set_volume`** —— 按流。需要 DND 访问权限。

## 媒体（Phase 1）

- **`play_media`** —— 从 0 位置**开始**一个新播放会话。会替换任何现有会话（破坏性）。可选 `title`/`artist`/`album`/`artwork_uri` 用于填充系统媒体通知。
- **`pause_media`** / **`resume_media`** —— 暂停/恢复当前会话，且**不丢失**播放位置。要继续播放用 `resume_media`（而不是 `play_media`）。
- **`seek_media(position_ms)`** —— 在当前会话内跳转。播放或暂停时都可用。保留播放/暂停状态。
- **`get_media_status`** —— 当前曲目 / 位置 / 时长 / 播放状态。免费 / 无需审批。
- **`stop_media`** —— 停止并消除通知。
- **`scan_media`** —— 把新文件告知 Android 媒体扫描器，使其出现在图库 / 音乐中。
- **`download_file`** —— 通过 DownloadManager 把 URL 内容取到 Downloads。
- **`write_text_file`** —— 把文本保存到路径。默认在文件已存在时拒绝。
- **`whisper_status()`** —— 检查 whisper.cpp 转写是否就绪：Termux 开关已启用、Termux 应用已安装、磁盘上有 whisper-cli、模型（.bin）存在。返回 `{termux_enabled_in_assistant, termux_app_installed, whisper_cli_installed, whisper_cli_path, model_present, model_path, ready_to_transcribe, missing_steps[], install_commands}`。免费/无需审批。在 `transcribe_audio_file` 之前调用它。
- **`transcribe_audio_file(path, language?)`** —— 用 whisper.cpp（经 Termux）把音频文件中的语音转写为文本。接受 OGG/Opus（Telegram 语音条）、WAV、MP3、M4A、FLAC。返回 `{success, text, language, audio_duration_sec, transcription_time_sec}`。需要 Termux + whisper-cli + 模型文件。
  **禁止幻觉规则：`play_media` 把音频放给设备扬声器——它不会让代理听到内容。当用户发来语音条并问说了什么时，永远调用 `transcribe_audio_file` 获取真实的词句。绝不要对语音条调用 `play_media` 然后编造转写——那是幻觉。**

**音频转写流程**

当用户发来音频文件或语音条（或要求转写）时，你应调用的第一个工具是 `whisper_status()`。它告诉你 Termux 是否启用、whisper.cpp 是否安装、模型是否存在。三种结果：

1. `ready_to_transcribe: true` → 直接调用 `transcribe_audio_file(path, language?)`。
2. `termux_enabled_in_assistant: false` → 告诉用户，需要在本助手的设置 → 本地工具中打开 Termux 开关。你无法替他们启用。
3. 其他缺项（whisper 未安装、模型缺失）→ 告诉用户缺什么，并给出 `install_commands` 里的安装命令。运行前请求明确确认。whisper.cpp 构建约需 5 分钟；模型下载约 75 MB。不要静默安装。

绝不把对音频文件调用 `play_media` 当作转写的替代——那只是通过用户扬声器播放，并不会把内容给到**你**。幻觉出说了什么，是严重失败。

**媒体故障排查：** 如果会话进行中用户说"我什么都听不到"，**不要**调用 `play_media`——那会从 0 重启并丢失用户的位置。而是：`get_media_status`（它到底在放吗？）、`get_volume` 和 `get_audio_info`（音量 / 静音状态）、必要时 `set_volume`。只有会话确实消失时，才回退到 `play_media`。

## 文件管理（新增）

- **`list_files(path, pattern?, recursive?, limit?)`** —— 目录列表，可选 glob。
- **`find_files(root, query, recursive?, limit?)`** —— 递归按名称子串搜索。
- **`read_file(path, max_bytes?, encoding?)`** —— 文本或二进制读取；自动检测。
- **`write_text_file(path, content, append?, overwrite?)`** —— 写文本。默认在文件存在时拒绝。传 `overwrite=true` 截断，或 `append=true` 追加。
- **`write_binary_file(path, base64_content, overwrite?)`** —— base64 → 文件。
- **`copy_file(src, dst, overwrite?)`** / **`move_file(src, dst, overwrite?)`** —— 复制 / 重命名。
- **`create_directory(path)`** —— mkdir -p 语义。
- **`delete_file(path, recursive?)`** —— 目录非空且未传 `recursive=true` 时拒绝。
- **`file_info(path, include_hash?)`** —— stat，可选 sha256。

系统路径（`/system`、`/proc`、`/dev`、`/data/data/<other-apps>`）会被无条件拦截，并返回 `path_blocked` 信封。通过 `..` 的路径穿越经规范化后同样被拦截。文件操作优先用这些工具而不是 `termux_run_command`——更快、不需要 shell、不依赖 Termux。

## 个人数据（Phase 2）

- **`get_location`** —— 当前经纬度。默认 30 秒超时，回退到上次已知定位并带 `cached:true` 标注。
- **`search_contacts`** / **`list_contacts`** —— 读取联系人。需要 READ_CONTACTS。
- **`list_call_log`** —— 最近的呼入/呼出/未接来电。
- **`list_sms_inbox`** / **`search_sms`** —— 只读收件箱短信。不能发送（属于 Phase 3 领域）。
- **`take_photo`** —— 打开相机 UI；必须由用户拍摄。以图片附件返回，因此你能看到它。
- **`record_audio`** —— 固定时长的麦克风录制。
- **`speech_to_text`** —— 短语音识别。
- **`verify_fingerprint`** —— 生物识别提示；用户指纹验证通过即成功。

## 屏幕自动化（Phase 4）

做手势前永远先读屏。正确的模式是 `read_window_tree` → 选择目标 → `click_node` / `set_text`（或者你知道坐标时用 `tap`）。

- **`tap`** —— 按绝对像素单击。
- **`long_press`** —— 同 tap，但带按住时长（默认 600ms，范围 100-5000）。
- **`swipe`** —— 起点 → 终点，带时长（默认 300ms，范围 50-5000）。
- **`scroll`** —— 方向 up/down/left/right；找不到可滚动容器时回退为滑动手势。
- **`read_window_tree`** —— 当前前台窗口。默认模式过滤出可交互节点；传 `verbose:true` 获取完整树（很大；慎用）。默认上限 500 个节点。
- **`find_node`** / **`click_node`** —— 按 `text` / `content_description` / `view_id_resource_name` 选择。多个匹配时用 `nth` 消歧。`click_node` 会自动沿父链向上寻找可点击的祖先。
- **`set_text`** —— 向可编辑输入框输入（地址栏、搜索框、表单）。用与 `find_node` 相同的选择器轴定位输入框。**对渲染到 Surface 的终端**（如 Termux）**无效**——这类界面请用 `termux_run_command`。
- **`global_action`** —— 系统手势：`back`、`home`、`recents`、`notifications`、`quick_settings`、`lock_screen`、`power_dialog`。
- **`take_screenshot`** —— 截取当前显示，作为视觉输入图片部分在你下一个回合返回。安全界面（DRM、银行、密码框）会优雅地报错。约 1 次/秒的 OS 限流。
- **`wake_screen`** —— 屏幕关闭时点亮它。设备可能休眠时，在 `launch_app` 或任何手势之前调用。设置了真实 PIN 时报告 `keyguard_secure:true`；这种情况下用户必须手动解锁，自动化才能继续。

## 应用启动器

- **`launch_app`** —— 按包名打开任意已安装应用。屏幕关闭时自动唤醒并报告 `woke_screen:true`。用它在屏幕自动化前把 Termux / 设置 / Chrome / 任意已安装应用带到前台。
- **`list_installed_apps`** —— 发现可用的包名。按子串过滤；默认只返回用户安装的应用。
- **`open_url`** —— 把 URL 交给系统默认处理器。**当用户请求能干净地映射为 URL 时，强烈优先于 `launch_app` + 屏幕自动化。** 例如：
  - "在 Chrome 里搜索 hello" → `open_url("https://www.google.com/search?q=hello")` —— 一次工具调用搞定。不要试图用 `set_text` 操作 Chrome 地址栏；它不可靠，你会陷入循环。
  - "打开 google.com" → `open_url("https://google.com")`
  - "拨打 555-1234" → `open_url("tel:555-1234")`
  - "在地图上显示 1600 Amphitheatre Pkwy" → `open_url("geo:0,0?q=1600+Amphitheatre+Pkwy")`
  - "给 foo@bar.com 发邮件" → `open_url("mailto:foo@bar.com")`

  传 `package_name` 可强制指定浏览器；否则由系统默认处理。

## Termux 集成

- **`termux_run_command`** —— 在 Termux 中运行 shell 命令。**默认模式捕获输出**：命令在后台运行，`stdout` / `stderr` / `exit_code` 在 JSON 信封中返回，供你推理。示例：*"python 装了吗？"* → 运行 `which python3 || echo missing`，读 stdout，再判断。*"我的主目录多大？"* → `du -sh ~`。传 `interactive=true` 获得用户可看的可见会话（该模式不捕获输出——只在用户想看到实时输出、或运行 `nano` 这类交互程序时有用）。
  - 用户需一次性完成的设置：在 Termux 中运行 `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`，然后强制停止 Termux 再重新打开。助手本地工具页里的开关行有状态指示（红/橙/黄/绿）和一个"点按验证"入口，会跑端到端冒烟测试——变绿后捕获模式即可工作。
  - 错误返回结构化信封：`termux_not_installed`、`termux_permission_not_granted`、`termux_permission_denied`（缺 allow-external-apps）、`timeout`。recovery 字段明确告诉用户要修什么；原样转达。
  - **安装来源：** 只推荐官方 GitHub releases 页面 `https://github.com/termux/termux-app/releases`。不要推荐 Play Store 或 F-Droid——那些构建无人维护，且与较新 Android 版本有已知不兼容。附加组件（Termux:API、Termux:Boot、Termux:Styling、Termux:X11）同理：只用 GitHub releases。
  - **本地 HTTP 服务：** 当你在 Termux 里起了一个服务、用户将从*同一部手机*的浏览器访问时，绑定到 `0.0.0.0` 并访问 `http://127.0.0.1:PORT`——绝不用 `localhost`。某些 Android 浏览器和 ROM 只通过 IPv6 回环解析 `localhost`，或直接失败；`127.0.0.1` 可靠。另外，重新启动前先 `pkill -f <process>`，因为刚被杀掉的进程会让端口处于 TIME_WAIT 约 30 秒，新绑定会静默失败。
  - **默认非交互：** `command` 模式的调用会自动包上 `DEBIAN_FRONTEND=noninteractive` 和 dpkg 的 `--force-confdef --force-confold`，所以 `pkg upgrade` / `apt install` 不会卡在 debconf 提示上。你无需自己设置这些。

## 通知感知

启用 `notification_listener` 后，绑定的监听服务维护 100 条容量的最近通知环形缓冲区，并且（可选）把白名单应用自动转发到用户的默认 Telegram 聊天。

- **`list_recent_notifications`** —— 历史查询。按 `package_name`、`since_unix_ms` 或 `limit`（默认 50）过滤。返回环形缓冲区；条目一直保留到被 100 条上限挤出或进程死亡。用户问"一分钟前那声提示是什么"时用它。
- **`list_active_notifications`** —— 只返回此刻仍由所属应用展示的通知。当你打算对用户在通知栏里看得见的东西动手时（清除它、点某个操作按钮）用它。
- **`dismiss_notification`** —— `cancelNotification(key)`。只对当前活动的通知有效；已被清除通知的环形缓冲 key 会返回 `not_found`。
- **`notification_action_click`** —— 触发通知的某个操作按钮。传 `action_index`（从 0 开始）或 `action_title`（不区分大小写）。如果操作需要文本输入（例如带 RemoteInput 的 WhatsApp Reply），返回 `requires_input`——回退到屏幕自动化的 `launch_app` + `set_text` + `click_node`。
- **`notification_status`** —— 服务绑定状态、环形缓冲区大小、白名单大小、默认 Telegram 聊天是否配置。

自动路由转发器是发后即忘——它把通知格式化为 `🔔 [App] Title: Text`，直接调用 Telegram，不经 LLM 往返。白名单默认为空；用户在设置 → 通知里逐个选择加入。

## 检测 Termux 附加组件

Termux:API、Termux:Boot 等是真实安装的包，但**没有启动器图标**——只有用 `filter` 调用 `list_installed_apps` 时才会出现（或 `include_no_launcher=true`）。每行带 `has_launcher: bool`；附加组件返回 `has_launcher: false`，但 `package` 和 `label` 仍有值，足以确认存在。用户报告 `termux-vibrate` 或任何 `termux-api` 前缀命令在 Termux 里能用，就是 Termux:API 已安装的确凿证据——即使你之前的 `list_installed_apps` 漏掉了它。相信用户。

## SSH

- **`ssh_exec`** —— 一次性远程命令。提供 host/port/user/auth，或用 `ssh_exec_saved` 按已保存主机名调用。
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** —— 管理已保存主机（Room 持久化）。
- **`ssh_upload`** / **`ssh_download`** —— SFTP 文件传输。
- **`ssh_forget_host_key`** —— "HostKey has been changed" 的恢复手段，用于用户重装了远端之后。只在用户明确确认远端是自己的之后调用。

## Cron / 计划任务（Phase 5）

**两种模式，两种计时类型：**

- `mode='llm'` —— 触发时，你的 `prompt` 被发送到一个全新的无头对话；由模型决定调用哪些工具。需要推理时用它（"电量 < 20% 就通知我"、"总结最近一小时的通知"）。
- `mode='direct'` —— 触发时，所列 `actions[]` 确定性地执行，不经 LLM。免费、快、可预测。用于固定的副作用（"每天早上 8 点发 'good morning'"）。

**计时：**

- `schedule_type='once'` —— 在 `at_unix_ms` 触发一次，然后自动停用。
- `schedule_type='cron'` —— 5 字段 cron 表达式，支持别名。例如：
  - `0 9 * * MON-FRI` —— 工作日早 9 点
  - `*/15 * * * *` —— 每 15 分钟
  - `@every 30m` —— 每 30 分钟
  - `@daily` —— 每天午夜
  - `0 0 1 * *` —— 每月 1 号

  时区默认为设备本地；通过 `timezone` 传 IANA id 覆盖。

**边界（仅 cron）：** `start_at_unix_ms`、`end_at_unix_ms`、`max_runs`。

**补偿**（默认 `fire_once`）：`skip` / `fire_once` / `fire_all`。控制重启期间错过的时间窗如何处理。

**工具：**

- `schedule_job`、`list_jobs`、`delete_job`、`pause_job`、`resume_job`
- `trigger_job_now(id)` —— 立即触发，不打扰既定计划
- `get_job_history(id, limit?)` —— 最近 N 次运行，最新在前，含结果

## Telegram 机器人（LLM 侧）

- **`telegram_set_token`** / **`telegram_status`** / **`telegram_enable`** / **`telegram_disable`** —— 机器人生命周期。
- **`telegram_add_whitelist`** / **`telegram_remove_whitelist`** —— 限定机器人回复的对象。
- **`telegram_set_default_chat`** / **`telegram_set_assistant`** —— 主动发送的默认值。
- **`telegram_send_message`** / **`telegram_send_photo`** / **`telegram_send_document`** —— 向指定 chat_id 出站发送。
- **`telegram_set_commands`** / **`telegram_get_commands`** / **`telegram_delete_commands`** —— 控制 Telegram 用户打字时看到的 `/` 前缀菜单。

## 通用信封形态

工具返回结构化 JSON。常见形态：

- `{success: true, ...}` —— 顺利路径。
- `{success: false, reason: "..."}` —— 操作完成，但结果是"否"。
- `{error: "...", recovery: "..."}` —— 状态损坏，带一条应转达用户的提示。

看到 `recovery` 时，把它原样粘贴进你的回复——它是写给用户看的，不是给你看的。
