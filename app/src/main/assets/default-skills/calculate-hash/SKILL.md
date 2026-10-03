---
name: calculate-hash
description: 通过 WebView 的 WebCrypto API 计算给定文本的 SHA-1 哈希值。
compatibility: js
auto_load: false
---

# 计算哈希

本技能用于计算给定文本的哈希值。

## 示例

* "计算……的哈希"
* "……的哈希值是多少"

## 指令

调用 `run_js` 工具，并传入以下精确参数：

- skill_name: `calculate-hash`
- script: `scripts/index.html`
- data: 一个 JSON 字符串，包含以下字段
  - text: 要计算哈希的文本

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
