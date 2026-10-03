---
name: openclaw-converter
description: 将 ClawHub（或原始 markdown）上的 OpenClaw 技能转换为 RikkaHub 兼容的技能。只要用户给出 OpenClaw 技能 URL、OpenClaw 技能的 GitHub 链接，或需要转换的原始 OpenClaw 技能 markdown，就使用本技能。
auto_load: false
---

# OpenClaw 到 RikkaHub 技能转换器

将 ClawHub（或原始 markdown）上的 OpenClaw 技能转换为 RikkaHub 兼容的技能。只要用户给出 OpenClaw 技能 URL、OpenClaw 技能的 GitHub 链接，或需要转换的原始 OpenClaw 技能 markdown，就应用本技能。

## 如何获取源文件

1. ClawHub URL（`https://clawhub.ai/<owner>/<slug>`）：该页面是 JavaScript 单页应用，直接抓取会返回空内容。改用应用内浏览器：`browser_open` 打开该 URL，等待内容渲染，提取渲染后的 SKILL.md 文本（上限约 16000 字符），然后关闭浏览器。
2. GitHub raw URL：如果你知道仓库，尝试 `https://raw.githubusercontent.com/<owner>/<repo>/main/SKILL.md`。有些技能位于 `https://github.com/<owner>/<repo>/tree/main/skills/<name>`。
3. 用户提供的原始 markdown：如果用户直接粘贴 SKILL.md，原样使用。

## 转换规则

### 路径

| OpenClaw | RikkaHub |
|---|---|
| `~/.openclaw/workspace/` | `~/` |
| `~/.openclaw/workspace/.learnings/` | `~/learnings/` |
| `~/.openclaw/skills/<name>/` | 通过 `skill_install_from_text` 安装；文件写入 `~/` |
| 任意项目根目录下的 `.learnings/` | `~/learnings/` |

### 工具引用

| OpenClaw 工具 / 概念 | RikkaHub 对应物 | 说明 |
|---|---|---|
| `sessions_list` | 不可用 | 删除该小节，或注明此限制 |
| `sessions_history` | 不可用 | 删除该小节，或注明此限制 |
| `sessions_send` | `telegram_send_message` | 跨会话变为跨设备通知 |
| `sessions_spawn` | `subagent_dispatch` | 仅在用户启用了子代理时可用 |
| `clawdhub install <name>` | `skill_install_from_url` 或 `skill_install_from_text` | 替换安装说明 |
| `openclaw hooks enable` | `schedule_job` 或 `workflow_create` | Hook 变为计划任务/工作流自动化 |
| `memory`（OpenClaw） | `memory_tool`（create/edit/delete） | 概念相同，API 不同 |
| Shell 命令 | `termux_run_command` | 需要 root 的命令加 `su -c` 前缀 |
| 文件读写 | `write_text_file`、`read_file`、`list_files` | 路径相同，写法适配 |

### 升级目标

| OpenClaw 目标 | RikkaHub 目标 |
|---|---|
| `CLAUDE.md` | 项目级文件（若项目存在）或 `~/learnings/` |
| `AGENTS.md` | RikkaHub 技能文件或 `memory_tool` |
| `SOUL.md` | 用 `memory_tool` 记录行为模式 |
| `TOOLS.md` | 更新相关技能的内容 |
| `MEMORY.md` | `memory_tool` |
| `.github/copilot-instructions.md` | 对 GitHub Copilot 用户保持原样 |

### 需要删除或替换的小节

- "OpenClaw Setup" / "OpenClaw Workspace Structure"：替换为 RikkaHub 的工作区路径。
- "Inter-Session Communication"：删除 `sessions_*` 工具；如果这个概念有价值，建议用 `telegram_send_message` 作为跨会话通知的变通方案。
- "Hook Integration" / "Enable Hook"：替换为 RikkaHub 工作流（`workflow_create`）或计划任务（`schedule_job`）。
- "Claude Code / Codex Setup"：整体删除（属于其他代理平台）。
- 通过 `clawdhub` 或 `git clone` 安装：替换为"通过 `skill_install_from_url` 或 `skill_install_from_text` 安装"及 RikkaHub 兼容路径。

### 保持原样的小节

日志格式（LEARNINGS.md / ERRORS.md 结构）、检测触发器、优先级指引、领域标签、最佳实践（除非引用了已删除的工具），以及核心工作流 / 快速参考表。

### 格式头

RikkaHub 技能使用简单的 frontmatter 块：`name`、`description` 和 `auto_load`。剥离 OpenClaw 的 YAML frontmatter，用第一个 `# Heading` 作为标题。

### 命名

小写字母加连字符，最多 40 个字符。尽可能保留原名。

## 安装后检查清单

`skill_install_from_text` 返回成功后：
1. 确认技能已安装并自动启用。
2. 如果技能需要目录（例如 `~/learnings/`），用 `write_text_file` 创建。
3. 如果技能引用外部工具（whisper、docker 等），注明依赖。
4. 把这次转换以 `best_practice` 记录到 `~/learnings/LEARNINGS.md`。

## 转换流程示例

```
User: "fetch this skill and convert it https://clawhub.ai/owner/skill-name"

1. browser_open(url)
2. wait for the rendered article/markdown
3. extract the page text (cap ~16000 chars)
4. close the browser
5. apply the conversion rules above to produce adapted markdown
6. skill_install_from_text(content=adapted, name="skill-name",
     source_label="Converted from <url> for RikkaHub")
7. initialise any required directories
8. confirm to the user
```

## 已知边界情况

- 极简技能（几段话）：只需改路径和工具名。
- 带 hook 脚本的技能：`scripts/` 目录和 hook 设置是 OpenClaw 专有的；替换为 RikkaHub 工作流/计划任务，或直接删除。
- 引用 `~/.openclaw/` 路径的技能：全部替换为 `~/` 对应路径。
- 带 YAML frontmatter 的技能：剥离顶部的分隔块。
- 已安装的技能：用同名 `skill_install_from_text` 原地更新。不要靠改名强装新版本；更新是首选。

## 触发条件

当用户给出 ClawHub URL、说"转换这个 OpenClaw 技能"、带着 OpenClaw 技能内容说"让它在这里也能用"、粘贴原始 OpenClaw SKILL.md 要求安装，或要求"抓取并转换"来自其他代理平台的技能时，应用本技能。
