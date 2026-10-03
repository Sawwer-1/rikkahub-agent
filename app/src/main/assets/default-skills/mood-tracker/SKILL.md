---
name: mood-tracker
display_name: 心情追踪
description: 记录每日 1-10 分的心情评分与备注，并查看趋势仪表盘。历史数据存储在 WebView localStorage 中。
compatibility: js
auto_load: false
---

# 心情追踪

## 指令

`mood-tracker` 技能帮助你记录每日的情绪状态。你可以按 1 到 10 分记录心情，并附上一句简短的感受备注。

### 操作

#### 1. 记录心情
当用户想记录心情时，调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："log_mood"
  - `score`：Number（1-10）
  - `comment`：String（可选）
  - `date`：String。**重要**：确认这条记录对应的日期。
    - 如果用户说 "today"，就传 "today"。
    - 如果用户说 "yesterday"，就传 "yesterday"。
    - 如果用户给出具体日期（例如 "March 18"），格式化为 **YYYY-MM-DD**，或原样传该日期字符串。
    - 如果未提及日期，默认使用 "today"。

#### 2. 查询指定日期的心情
当用户询问某天的心情时，调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："get_mood"
  - `date`：String（从用户的请求中确认日期）

#### 3. 获取历史 / 显示仪表盘
当用户想查看心情历史（"last week"、"past 10 days"）或仪表盘时，调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："get_history"
  - `days`：Number（可选，默认 7。例如 "last week" 传 7）
  - `show_dashboard`：Boolean（可选）

#### 4. 绘制心情趋势（折线图）
当用户想用图表可视化心情趋势时（例如 "Plot my mood for 7 days"），调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："get_history"
  - `days`：Number（可选，默认 7）
  - `show_dashboard`：`true`
  - **提示**：这会触发仪表盘中的绘图视图。

#### 5. 分析趋势与模式
当用户要求分析其心情时（例如"有没有什么趋势？"、"我是不是感觉好些了？"），按以下步骤操作：
1. 调用 `run_js`，传 `action: "get_history"` 和合适的 `days` 数量（例如按月分析传 30）。
2. 收到 JSON 历史数据后，分析评分与备注。
3. 给用户一段有思考的回复，涵盖：
   - 总体趋势（变好、变差、平稳）。
   - 是否有特别好或特别差的成片日子。
   - 备注中体现的主题或模式。

#### 6. 删除指定日期的心情
当用户只想删除某一天的记录时（例如 "Delete my mood for today"），调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："delete_mood"
  - `date`：String（确认日期）

#### 7. 导出数据（备份）
当用户想备份或导出数据时，调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："export_data"

#### 8. 抹除全部数据
当用户想清空全部心情历史、从头开始时，调用 `run_js` 工具，传入：
- **script**：`scripts/index.html`
- **data**：一个 JSON 字符串，包含：
  - `action`："wipe_data"

### 示例指令

你可以参考这些示例与心情追踪技能交互：

- **记录心情：**
  - "记录我今天的心情为 8 分，感觉很好！"
  - "把我昨天的心情记为 2 分"
  - "把 2026 年 3 月 18 日的心情记为 1 分"
  - "我今天感觉是 5 分，有点累。"
  - "上周五我感觉是 7 分。"
  - "帮我记一条 9 分的心情。"

- **查看历史：**
  - "显示我的心情历史。"
  - "看看我上周的心情。"
  - "我最近状态怎么样？"
  - "显示我最近 10 天的心情。"
  - "打开心情仪表盘。"
  - "我 3 月 18 日的心情是多少？"
  - "我昨天的心情是多少？"

- **分析趋势：**
  - "分析我最近 30 天的心情——有什么规律吗？"
  - "总体来看，我的心情是在变好还是变差？"
  - "我的历史记录里有没有成片状态很差的日子？"
  - "我最近的备注反映出我的状态如何？"

- **抹除与删除：**
  - "删除我今天的心情记录。"
  - "移除我昨天的心情记录。"
  - "删除 3 月 18 日的记录。"
  - "清空我的心情历史。"（这种情况使用 `wipe_data`）
  - "抹除我的数据。"（这种情况使用 `wipe_data`）

- **绘制趋势图：**
  - "绘制我最近 7 天的心情曲线。"
  - "给我看我这个月的心情图表。"
  - "可视化我过去 14 天的评分。"
  - "画出我的心情变化图。"

### 规则
- **隐私**：所有数据都存储在本地设备上。
- **无记录**：如果请求的日期没有心情记录，明确告知用户该日期没有找到记录。
- **更新**：为已有记录的日期记录心情，会更新该条记录。
- **仪表盘**：只有当你明确要求查看历史或仪表盘本身时，才会显示仪表盘。

## 来源说明

移植自 [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)，遵循 Apache-2.0 许可。原始版权归 Google LLC 所有。
