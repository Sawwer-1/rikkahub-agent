# 并行实例治理 + 工具 schema 盘点（B06 / R26·R28）

- 日期：2026-10-19
- 结论：**R26 已落地（实例后缀 + 跨进程锁，自检通过）**；**R28 只完成量化，瘦身需真机口径 + 拍板**（见 §二）。

## 一、R26：不再让两个实例写坏同一个文件

### 事故背景（2026-10-16）

两个自动化实例同时跑同一分支、共用 `D:\dev\aaa-jude\work`：两边 `curl -C -` 交错追加同一个
`rel<版本>.zip` → 长度超出 artifact 声明值、zip 头尾皆坏；隔离后各自重下一次才恢复。
根因不是网络，是**同名临时文件没有互斥**。

### 提供的两件工具（仓库外 `work/`）

| 工具 | 作用 |
|---|---|
| `work/instance_lock.py` → `suffixed(path)` / `instance_tag()` | 给临时产物加实例后缀：`rel.zip` → `rel.24544.zip`，两个实例天然不同名 |
| `work/instance_lock.py` → `FileLock(path, timeout)` | 跨进程排他锁（Windows `msvcrt.locking` / POSIX `fcntl.flock`）；拿不到锁**返回 False 而不是抛异常**，调用方可以打印人话再退出 |
| `work/dl_artifact_locked.py` | 带锁的下载入口；同一 dest 的第二个实例直接退出 3，不写坏文件 |

### 自检证据

```text
> python work\instance_lock.py --selftest
second_instance_acquired=False (期望 False)
after_release_acquired=True (期望 True)
SUFFIX sample: work/rel.24544.zip
```

### 使用约定（下一轮起照这个来）

1. **大文件一律用 `dl_artifact_locked.py`**，不要直接调 `dl_artifact.py`；
2. 自己写的临时产物（日志、解包目录、比对文件）用 `suffixed()` 加实例后缀；
3. 必须共用同一路径时（单 commit 推送、下载续传）先 `FileLock`，失败就**退出**，别硬写；
4. 锁文件不删——删锁文件会引入「A 删、B 拿旧 inode 加锁」的竞态。

## 二、R28：工具 schema 体积——先量化，再决定动不动

### 静态盘点（`work/tool_schema_audit.py`）

```text
files=37 tools=63 desc_chars=2883 (静态估算，仅 data/ai/tools)
avg_desc_chars=45  max=212
>=600 字符的工具 0 个，合计 0 字符（占全部描述 0%）

tool                                   desc args  file
linux_run                               212   11  LinuxRuntimeTools.kt
post_anonymous_question                 200    6  LocalTools.kt
linux_grant_request                     167    4  LinuxGrantTools.kt
conversation_read_recent                166   17  SecondUserConversationReaderTools.kt
conversation_search                     138   20  SecondUserConversationReaderTools.kt
```

### 结论（重要，别按错误假设动手）

- **描述不是瓶颈**：63 个工具、描述合计只有 2,883 字符（平均 45），没有一个 ≥600 字符。
  历史口径里的「schema 29K」**不可能**主要来自工具描述。
- 真正的体积在**参数**：`conversation_search` 20 个参数、`conversation_read_recent` 17 个、
  `linux_run` 11 个——每个参数都带类型、描述、enum，JSON 展开才是那 29K 的来源。
- 因此「砍描述」这条路过不了验收；可行的三条路都要先有**运行时 JSON 实测**：
  1. 合并参数（多个可选项合成一个 `options` 对象）——动的是工具契约，要改 prompt 与调用方；
  2. 按助手/场景**分级暴露**（默认只暴露高频工具，其余按需发现）——已有 tool discovery 设施，
     但默认集合要用户拍板；
  3. 参数描述统一压缩到 X 字——收益可量化但需要真机测一轮 JSON 大小。

### 建议（需拍板）

先做**运行时侧一次性测量**（`materializeProviderToolSchemas()` 产出序列化后的字符数，落一条诊断日志），
拿到真实分布后再选 1/2/3。**在没有实测前自动改工具契约风险过高**（会直接改变模型可用参数）。

## 三、本批边界

- R26：已完成，纯工具侧（不产 APK、不占版本号）。
- R28：**只做量化，未改运行时**；按计划的口径转为待拍板项（需要真机测量与工具契约调整的授权）。
