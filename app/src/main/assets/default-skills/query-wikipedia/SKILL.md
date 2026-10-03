---
name: query-wikipedia
description: 在维基百科上模糊搜索某个主题，返回引言及信息框摘要。需要联网。
compatibility: js
auto_load: false
---

# 查询 Wiki

## 指令

使用 `script: scripts/index.html` 调用 `run_js` 工具，`data` 传一个 JSON 字符串，包含以下字段：
- **topic**：必填。只提取主要实体、人物或事件（例如 "2026 Oscars"、"Albert Einstein"）。你必须删除所有具体的问题细节、动作词或对话性文字（例如不要包含 "winner"、"best picture"、"who won"、"history of" 这类词）。搜索宽泛的主题，以便工具能返回主条目。
- **lang**：必填。两位字母的语言代码。该代码必须与你在 `topic` 字段中提供的关键词语言一致。使用标准代码，例如 "en"（英语）、"es"（西班牙语）、"zh"（中文）、"fr"（法语）、"de"（德语）、"ja"（日语）、"ko"（韩语）、"it"（意大利语）、"pt"（葡萄牙语）、"ru"（俄语）、"ar"（阿拉伯语）、"hi"（印地语）。

**约束：**
- 提供简洁的摘要（1-3 个完整句子）以节省上下文。务必确保回复以完整句子结尾。回复必须使用与用户原始提示相同的语言书写。
- 对于周期性活动或时效性事实，查询具体的届次（例如 "2026 Oscars"）。若用户省略年份，默认使用当前年份。
- 若摘要中找不到用户问题的确切答案，简要说明这一点，然后主动提供一条*确实*在文本中找到的相关信息。

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
