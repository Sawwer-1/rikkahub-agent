---
name: text-spinner
description: 在 webview 中渲染一个 3D 旋转的文字标签。返回聊天界面可嵌入的相对 webview URL。
compatibility: js
auto_load: false
---

# 指令

你必须使用 `run_js` 工具，并传入以下精确参数：

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: 一个 JSON 字符串，包含以下字段：
  - label: 要旋转展示的文本字符串。

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
