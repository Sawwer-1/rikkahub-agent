---
name: qr-code
description: 为给定 URL 生成 512x512 的二维码 PNG。返回 base64 编码的图片。需要联网（从 cdnjs 加载 qrcode.js）。
compatibility: js
auto_load: false
---

# 指令

你必须使用 `run_js` 工具，并传入以下精确参数：

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: 一个 JSON 字符串，包含以下字段：
  - url: String —— 要生成二维码的 URL

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
