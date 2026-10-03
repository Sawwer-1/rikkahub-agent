---
name: virtual-piano
display_name: 虚拟钢琴
description: 在 webview 中显示一台可弹奏的 88 键虚拟钢琴，带有采样音符音频。
compatibility: js
auto_load: false
---

# 虚拟钢琴

一台可弹奏、可横向滚动的虚拟钢琴键盘，使用 Web Audio 发声。

## 文件
- `scripts/index.html`: 本地入口，加载 `scripts/index.js`。
- `scripts/index.js`: 返回 webview URL `ui.html?v=<timestamp>`，指向 `assets/` 下的本地 UI。
- `assets/ui.html`: 钢琴键盘 UI（3D、88 键、横向滚动）。
- `assets/assets/<n>.mp3`: 88 个琴键各自的采样音频。

## 提示词 / 触发方式
- "打开虚拟钢琴"
- "弹钢琴"
- "我想弹钢琴"
- "给我看一个钢琴键盘"

## 指令

调用 `run_js` 工具，传入：
- skill_name: `virtual-piano`
- script: `scripts/index.html`
- data: 一个 JSON 字符串（任意内容，本技能会忽略）。

本技能返回一个 `webview` 结果，内含相对 URL（`ui.html?v=...`）。聊天界面自行决定如何渲染嵌入的 webview 返回；若无法内联 iframe，则回退为可点击的链接。

主页：<https://github.com/google-ai-edge/gallery/tree/main/skills/featured/virtual-piano>

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
