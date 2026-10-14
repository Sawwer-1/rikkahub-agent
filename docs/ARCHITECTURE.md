# 架构与协作指南（写给协作者与 AI 助手）

> 本文件是 jude-port fork 的"通用事实 + 坑清单"。骨架吸收自 `xiwangone/rikkahub-agents` 的
> `docs/ARCHITECTURE.md`（2026-10-14），内容按我们四源合并版（aaa / ExTV / Jude / 橘瓣）的实情改写。
> 只写通用事实，**不要**把本机路径、主机名、内网地址、私有环境信息写进来（本文件随仓库公开）。

---

## 0 · 一分钟速览

**这是什么**：Android LLM 客户端 + 设备端 Agent（内置工具按需启用、子代理、工作流、定时任务、内置浏览器、SSH、MCP、Skills，以及我们自有的 Dream 记忆合成 / 语音通话 / 时刻 / 匿名提问）。

**技术栈**：Kotlin · Jetpack Compose (Material 3) · MVVM · OkHttp + Ktor · kotlinx.serialization · Koin · Room + DataStore · Gradle + AGP。

**模块地图**：

| 模块 | 职责 |
|---|---|
| `app` | 应用外壳：UI、设置、会话、**工具装配**、生成循环、记忆/Dream |
| `ai` | LLM 抽象：提供商、消息/流式协议、工具调用协议、`Tool`/`InputSchema` |
| `workspace` | 工作区沙箱：Rootfs 读写、挂载、命令执行 |
| `document` · `search` · `speech` · `web` | 文档解析 · 联网检索 · 语音（TTS/ASR） · 内置浏览器 |
| `local-llm` · `assistant-proxy` · `usage-tracker` · `weather` · `common` · `material3` · `highlight` | 本地推理 · 助手代理 · 用量 · 天气 · 通用 · 主题组件 · 代码高亮 |

---

## 1 · 隐式约定（**最容易踩的坑，先看这里**）

1. **领域模型与数据库实体分离。**
   `Conversation`（领域模型）↔ `ConversationEntity`（Room 实体）通过映射函数转换；真正的映射实现是**文件末尾的顶层函数**（名字以 `Impl` 结尾），类内同名方法只是薄包装。改映射时**别改错地方**。

2. **给会话加字段，必须同时落库。**
   会话级字段（工作区 / 压缩配置 / 运行时状态等）只加在领域模型上，**执行时会丢**——流程是「创建 → 落库 → 按 id 重新加载」。必须同时改：Entity 列 + 双向映射 + 数据库版本与迁移 + **一条"实体往返"单测**。否则症状是"回显有、实效无"。

3. **一个文件里可能有多个 ViewModel**（如 `ChatPage.kt` 同时有外部传入的 `vm` 与内部创建的 `drawerVm`）。改调用前先确认用的是哪一个。

4. **工具面是"注入前按名字排序"的。**
   `data/ai/ProviderToolOrdering.kt` 的 `stableProviderToolOrder(tools) = tools.sortedBy { it.name }` 是唯一实现，**顺序参与请求前缀**，排序是为了稳定命中提示缓存——不要随意改动注入点或去掉排序。
   另有**渐进式工具目录**（`toolcatalog/ToolCatalog.kt`：`tool_catalog_search` / `tool_catalog_list` / `tool_catalog_open` + `ToolDiscoverySession`，活跃 schema 上限 6）。注意：普通对话目前 `toolSurfaceSession == null`，该模式只在第二用户 DIRECT 会话启用。

5. **工具错误走统一装饰，不手搓 JSON 错误对象。**
   `LocalTools.getTools` 出口统一过 `addHumanErrorEnvelopes(appendTopToolExample(tool))`；工具失败请用既有具名失败码 / 错误信封（`ToolErrors` 方向），延续 223/225 的具名码思路。

6. **无人值守会话走独立通道。**
   定时任务（`CronJobWorker`）、工作流（`WorkflowEngine`）、心跳、Telegram / 微信桥是 headless 会话：**工具调用自动批准**（没有人接审批弹窗），风险靠**工具白名单收敛 + 危险命令兜底**控制。改动这条链要特别小心；Telegram 是 browser-headless 但**不**自动批准。

7. **子代理每次派发是独立会话**：配置与模型可复用、**上下文不继承**，有超时与步数上限；默认只读工作区。

8. **预设消息 id 必须换新。**
   `ChatService.effectivePresetMessages` 物化助手 preset 时会 `.map { it.copy(id = Uuid.random()) }`。`UIMessage.id` 是序列化字段且 `JsonInstant` 是 `encodeDefaults = true`，preset 的 id 会随助手设置长期保留；若沿用，同一助手 scope 下多个会话共用同一 message id，会撞 `learning_message_source_authority` 的 `(scope_kind, scope_id, message_id)` 主键 → `SOURCE_MESSAGE_IDENTITY_MISMATCH` 崩进程（2.5.19/230 根因）。**分叉会话同样重新生成消息 id**，保持同一口径。

9. **source authority 的三条降级口径**（勿改回 fail-closed）：
   `TOMBSTONE_REVIVAL` / `TIME_REGRESSION`（2026-10-12）、`IDENTITY_MISMATCH`（2026-10-13）在 `ConversationSourceAuthorityWriter.reconcileInCurrentTransaction` 里一律 **跳过该消息、保留占用者的行、不篡改、不复活证据**，其余消息继续对账。代价是受影响的那条消息不登记 Learning 证据。

10. **资源文案（`res/values*/strings.xml`）**：`values/` 是**英文默认**、`values-zh` 是中文；**单引号必须写成 `\'`**（裸单引号会让资源编译直接失败）；跨语言**占位符必须一致**。

11. **路径口径（双命名空间，2026-10-14 设备实测）**：
    `~/...` 落 **App 私有** `files/workspace/`；`/workspace/...` 落 **手机存储** `RikkaHubExchange/workspaces/<uuid>/`。
    两套命名空间**仍然并存且未合并**（E 项已知）；写路径时按此表走。注意 224 报告写的映射方向与此**相反**，以 230/231 两次实测为准。

12. **自检自动化（testTag → WindowTreeTool）有前置条件**：
    设备端要能"看见" `Modifier.testTag`，需要该助手**勾选「屏幕自动化」**（`LocalToolOption.ScreenAutomation`，一组 11 件：tap/long_press/swipe/`read_window_tree`/find_node/click_node/set_text/scroll/global_action/take_screenshot/wake_screen）**并且**「无障碍服务」已授权——`WindowTreeTool` 直接依赖 `AccessibilityNodeInfo`，只勾选项、无授权仍然拿不到。
    勾选入口：助手设置 → 本地工具 → 屏幕自动化（`AssistantLocalToolPage`）。**没有**"能力不可用就自动隐藏"的额外门控，纯粹由该开关决定。

13. **UI 控件打 `testTag`（自检自动化依赖它）。**
    `RouteActivity` 已开 `testTagsAsResourceId = true`，因此 `WindowTreeTool` 能在无障碍树里看到 `Modifier.testTag("...")`。关键控件（记忆中心各 Tab、策略审查、立即做梦、ask_user 提交……）请用 `<页>_<控件>` 的稳定名，**不要写中文、不要随文案改**。

14. **记忆叙事的命名中性化是"三层、两条口径"，且刻意不回填数据。**
    上游遗留的生造名（`斯啾伊` / `七七`）走三层处理：
    | 层 | 版本 | 做法 |
    |---|---|---|
    | 写入端 | 228 起 | 新 experience 摘要用 `用户：/助手：`；Dream `SYSTEM_CONTRACT` 名字替换 + "不得自造人名"约束句（旧 claim 可自愈） |
    | UI 渲染 | 228/229 | 三栏标题硬编码为 `关于你 / 关于我 / 关于我们`；`MemoryNarrativePolicy.normalizeMemoryNarrativeText` 读时映射成"设置里的名称" |
    | 模型侧 | 232 起 | `normalizeMemoryNarrativeTextForOutput` 把同一张别名表接到 `dream_view` 等工具输出，防止模型读到生造名再写回新 claim |
    **口径：存量数据一律不回填。** 理由：claim 的 `revision`/hash 必须保持稳定，回填会让 Learning 证据链漂移、并可能让既有 claim 被当成新 claim 重复写入；用户可见文本已由读时映射处理，数据层保留原文是**预期行为而不是漏修**。若将来真要清洗存量，必须同时重算 hash 并在同一事务里做，不能只改文本。

---

## 2 · 构建与验证（本机限制）

```bash
./gradlew :app:compileDebugKotlin      # 快验证：只编主源码
./gradlew testDebugUnitTest            # 全量单元测试
./gradlew assembleRelease              # 出包（装机验证时才做）
```

**注意**：
- 本仓库的日常编译/单测**由 CI（GitHub Actions）承担**：`unit-tests.yml` 与 `release.yml` 均为 `workflow_dispatch` 手动触发。本机若无 JDK / Android SDK，不要在本地反复试编译。
- 首次运行 Gradle/测试可能命中缓存而"看起来很快"；要确认真跑了，加 `--rerun-tasks`。
- 只改 Kotlin 逻辑时 `compileDebugKotlin` 比 `assembleRelease` 快一个量级。

---

## 3 · 提交红线（提交前必读）

- 仓库内容（代码、注释、提交信息、文案、测试数据）**不得**出现内部信息：内部路径、主机名、内网地址、个人标识、私有文档引用，以及任何凭证（token / 口令 / 私钥 / 签名文件）。
- 不提交 `.jks / .keystore / .pem / .key / .env`；签名文件与口令一律不入库。
- 本地/远端 SHA 可能不同但内容等价（本项目历史上多次如此）；**以"逐文件 blob 对比"为等价判据**，不要只看 SHA。

---

## 4 · 常见症状 → 对策

| 症状 | 多半是 | 对策 |
|---|---|---|
| 改了代码、"编译通过"但行为没变 | 编的是旧代码 | 先核对目标机器的 HEAD 是否与本地一致，再编 |
| 会话级设置"显示已生效"却无效 | 字段没落库（§1.2） | 查 Entity 列 / 映射 / 迁移 / 往返测试 |
| 工具调用返回"工具不存在" | 不在当前工具白名单 | 查助手级 / 子代理级作用域与 `ToolExposurePlan` |
| 单测"通过"但没跑新用例 | Gradle 缓存 | 加 `--rerun-tasks` 或核对测试报告 |
| 资源编译报引号错误 | `strings.xml` 裸单引号 | 写成 `\'` |
| 打开会话/切会话闪退（authority 相关） | 跨会话 message id 冲突 | 见 §1.8 / §1.9；检查 preset id 是否被复用 |
| Dream 页点了没反应/一直 PENDING | 合成链或调度 | 先看 `dream_view(view=failures)` 的具名失败码（223 起每种拒绝都有名字） |

---

## 5 · 新增一个工具的登记清单（★ 防不兼容）

**我们目前没有独立 `agent-tools` 模块**（上游有）；工具装配是 `data/ai/tools/LocalTools.kt` 的单一入口
`getTools(options, invocationContext, usageLockEnabled, voiceCallConfigured)` + `if (options.contains(...))` 条件追加。

**已核实的一条简化**：**安全描述符由能力目录派生**——`data/ai/execution/ToolSecurityDescriptor.kt` 的
`resolve()` 直接 `CapabilityCatalog.byToolName(toolName)`，所以「登记进 `CapabilityCatalog`」即等于
「拿到安全描述符」，不需要再维护一张描述符表。

**新增一个工具必须同时改（漏一处 CI 就红）**：

| # | 位置 | 说明 |
|---|---|---|
| 1 | 工具实现文件（`data/ai/tools/` 或 `tools/local/`） | `Tool(name, description, parameters, needsApproval, execute)` |
| 2 | `LocalToolOption`（`LocalTools.kt`，sealed class） | 新能力组才需要；**并必须登记进 `PRIVILEGED_IMPLEMENTED`** |
| 3 | `LocalTools.getTools` | 用 `options.contains(...)` 追加 |
| 4 | 工具名集合（如 `WORKSPACE_TOOL_NAMES`） | 该集合被 `CapabilityCatalog` 直接引用 |
| 5 | `CapabilityCatalog` | file-mutation / background / 等 surface 名集，且**能力目录 ↔ 静态基线必须集合相等** |
| 6 | `CapabilityPolicy`（`FILE_MUTATION_TOOLS` / `FILE_READ_TOOLS` 等） | 读/写语义分类 |
| 7 | `DefaultToolExecutionPolicyResolver`（`FILE_WRITE_TOOLS` / `FILE_READ_TOOLS` 等） | 运行期策略 |
| 8 | `ToolExecutionGate`（路径变更集合）/ 安全描述符登记 | 自保与安全面 |

**会被"新增工具"打破的钉死测试（改完必须同步）**：

| 测试 | 断言 |
|---|---|
| `toolcatalog/ToolCatalogTest.kt` | 目录快照 ↔ `CapabilityCatalog.allCapabilities().toolNames + InternalToolSecurityCatalog.ALL` **集合相等**；每个内置工具都有可执行安全登记 |
| `privilege/PrivilegedToolSurfaceTest.kt` | `LocalToolOption.sealedSubclasses` ↔ `PRIVILEGED_IMPLEMENTED` **集合相等** |
| `data/capability/WorkspaceAndKeyboardCapabilityCatalogTest.kt` | 工作区工具 ↔ 精确 surface 映射（**逐名精确集合**） |
| `data/capability/P2CapabilityCatalogTest.kt` | 每个已实现 option 拥有确切工具名；`Unclassified == 0` |
| `execution/ToolSecurityDescriptorResolverTest.kt`、`ToolExecutionPolicyResolverTest.kt` | 每个能力工具必须有安全描述符与运行策略 |
| `data/ai/tools/ToolSchemaArrayItemsTest.kt` | **静态扫源码**：`tools/`、`tools/local/`、`mcp/control/`、`subagent/`、`workflow/` 下任何 `put("type","array")` 必须带 `put("items", ...)` |
| `data/ai/ProviderToolOrderingTest.kt` | provider 工具名序确定（`stableProviderToolOrder`） |

**改完自检**：`grep` 新工具名在以上 8 处是否齐全；再 `grep -c` 新 string key 在 `values/` 与 `values-zh/` 是否各 1 次。

---

## 6 · 给 AI 协作者的工作方式建议

- **先读后改**：定位实现时先确认"真实现放在哪"（§1.1、§1.3），再动手。
- **改动要成体系**：加一个字段/开关/工具，通常要同时改 领域模型 → 实体 → 映射 → 迁移 → 装配点 → UI → 多语言（§1.2、§5）。只改一处就是这个项目里最常见的"半成品"。
- **验证要拿证据**：不要凭"命令返回成功"下结论——核对 diff、测试报告、产物哈希；本机不能编译时，明确说"未编译"，把验证留给 CI。
- **改动前先查钉死测试**（§5 表），它们就是"语义契约"的可执行版本。
- **大文件先看顶部注释**：本仓库有上千行的文件（`ChatService.kt` 等），改动前先看文件头说明，避免误改相邻逻辑。
- **崩溃类问题的取证口径**：authority / Room / 迁移相关的崩溃，先在 `data/authority/`、`data/db/migrations/` 找不变量，再考虑改行为；能降级就别 fail-closed（§1.9 是既有先例）。
