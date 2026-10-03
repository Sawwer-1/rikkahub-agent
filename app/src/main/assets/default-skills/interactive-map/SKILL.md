---
name: interactive-map
description: 为某个地点显示可交互的 Google Maps 嵌入地图。返回聊天界面可渲染的 webview URL。
compatibility: js
auto_load: false
---

# 交互式地图

## 示例

- "在交互式地图上显示[某地]"
- "在交互式地图上查找[某地]"

## 指令

调用 `run_js` 工具，并传入以下精确参数：

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: 一个 JSON 字符串，包含以下字段
  - location: 要在地图上显示的地点。

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
