# 微信桥 `context_token` 冷启动行为记录（B05 / R18）

- 日期：2026-10-19
- 适用：255+（微信桥 / iLink 通道）
- 结论摘要：**冷启动后、收到该用户第一条入站消息之前，主动推送必然被跳过**。这不是 bug，是协议约束 + 当前实现的组合结果；本轮记录代码路径与判据，真机复测步骤见 §四。

## 一、代码事实（可复核）

| 事实 | 位置 |
|---|---|
| token 存在**进程内存**里（`ConcurrentHashMap`），不落盘 | `data/weixin/WeixinBotPreferences.kt`（`contextTokens`） |
| 缓存只在**收到入站消息**时写入（`rememberContextToken`） | 同上；调用点在 `WeixinBotService.handleIncoming` |
| 主动消息出口（外部生成完成泵）先查缓存，查不到就**跳过并打日志** | `WeixinBotService.listenForExternalGenerationCompletions` |
| 普通回复路径查不到 token 时同样直接返回 | `WeixinBotService.sendTextOrLog` |

iLink 协议要求 BOT 主动发消息也带一个 `context_token`（来自该用户最近一条入站消息），
没有 token 就无法把消息关联到已有会话，因此「跳过」是唯一安全行为。

## 二、冷启动时序（推导，非实测）

```
进程被杀 / 设备重启
  └─ 进程起来，contextTokens 为空
       ├─ 用户 A 的心跳/cron 任务生成完成 → 主动推送：无 token → 跳过（日志 reason=no context_token）
       └─ 用户 A 发来一条消息 → handleIncoming 写入 token
            └─ 此后的主动推送才会成功
```

**副作用（用户可感知）**：重启后到用户下一次主动发消息之间，定时任务/心跳的产出不会推到微信，
只留在 App 内。这与「BOT 不能凭空发起会话」的协议预期一致。

## 三、判据（真机或日志里怎么确认）

- 跳过：`I/WeixinBot: external-gen-pump: no context_token for <uid>, skipping proactive push`
- 跳过（回复路径）：`W/WeixinBot: no cached context_token for <uid>, cannot send`
- 成功：`I/WeixinBot: external-gen-pump: pushed N chars to wechat user <uid>`

## 四、真机复测步骤（三步，5 分钟）

```sh
# 1) 起日志（只留微信桥标签）
adb logcat -c && adb logcat -s WeixinBot:V

# 2) 强行杀掉 App，模拟冷启动
adb shell am force-stop me.rerere.rikkahub && adb shell monkey -p me.rerere.rikkahub 1

# 3) 触发一次「外部生成完成」（心跳/定时任务；或等 cron 到点），观察日志
#    预期：出现 "no context_token ... skipping proactive push"
#    然后在微信里给 BOT 发一句话，再触发一次
#    预期：这次出现 "pushed N chars to wechat user <uid>"
```

## 五、待拍板（不在本批范围）

- **是否把 token 落盘**：落盘能让冷启动后的第一次主动推送也成功，但 token 属于会话凭据，
  落盘要过「第二用户密钥库 / 加密存储」这条线（见 R7 A12 Vault）。**需要用户点头再做。**
- 若不落盘，建议在设置页把这条限制写成人话（「重启后需你先说一句话，定时任务才会推送到微信」）——
  这属于 UI 文案，等下次统一处理时一起做。
