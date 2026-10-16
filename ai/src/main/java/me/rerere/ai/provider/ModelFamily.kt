package me.rerere.ai.provider

/**
 * DeepSeek V4 家族判定（2026-10-17：判据统一）。
 *
 * 只按「deepseek + v4」这个家族特征判定，**大小写与分隔符（`-` `.` `_` 空格等一切非字母数字）
 * 一律归一**。
 *
 * 起因（工单 CB · 思考链降智 · 待确认项 4 + Dream 预算）：同一个上游、同一个模型，只因为模型名
 * 拼写不同就会走到不同分支。历史上有三处各自写了一套判据，其中两处写成
 * `"deepseek-v4-" in modelId.lowercase()`——只认连字符，于是：
 *   - `deepseek-v4-flash` / `DeepSeek-V4-Flash` → 命中
 *   - `deepseek-v4.1-flash` → **漏判**
 * 漏判的后果按调用点不同：工具轮 reasoning 回传与 `reasoning_effort` 写法对不上（降智），
 * 或 OpenCode 上的 Dream 拿不到扩容预算/超时（小预算被隐藏思考吃光、长任务超时）。
 *
 * 统一到这一处判定，新增模型名拼写不再需要逐处补丁。
 */
fun isDeepSeekV4Family(modelId: String): Boolean =
    "deepseekv4" in modelId.lowercase().filter { it.isLetterOrDigit() }
