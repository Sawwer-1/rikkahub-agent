# Rootfs 读库标准路径（B05 / R15）

- 日期：2026-10-19
- 适用：255+（rootfs 工作区 + `workspace_shell` 工具链）
- 目的：把「从 rootfs 里读 App 数据库」从口口相传变成一条可复制的标准路径——设备上不 root、不装额外 APK，只靠 App 自己的终端能力。

## 一、为什么需要这条路径

排障时最需要的一手证据是**库里的真实行**（消息节点、压缩状态、审计记录），而不是 UI 或日志。
历史做法是 `adb pull /data/data/me.rerere.rikkahub/databases/...`——需要 root 或 debuggable 包，
真机上走不通。rootfs 工作区提供了另一条路：App 自己把库**导出到 `/workspace`**，再用 sqlite3 读。

## 二、标准路径（三步）

1. **导出**：在 App 的 rootfs 终端（`workspace_shell`）里执行

   ```sh
   # proot 环境里的 /workspace 与 App 的 files 目录是同一份
   cp /data/data/me.rerere.rikkahub/databases/rikkahub.db /workspace/export/rikkahub.db 2>/dev/null \
     || cp "$HOME/../data/data/me.rerere.rikkahub/databases/rikkahub.db" /workspace/export/rikkahub.db
   ls -l /workspace/export/rikkahub.db
   ```

   若 proot 内看不到 `/data/data`（常见），用 App 侧导出（设置 → 文件/备份）把库放到 `/workspace/export/`，
   两条路等效。

2. **读**：`sqlite3 /workspace/export/rikkahub.db`，常用三条：

   ```sql
   -- 会话图规模（判断是否丢节点）
   select conversation_id, count(*) as nodes from message_node group by conversation_id order by nodes desc limit 10;

   -- 压缩状态（B03/B04 相关：折叠集合与摘要长度）
   select id, length(compressed_summary) as summary_chars,
          length(compressed_message_node_ids) as ids_chars
     from conversation order by update_at desc limit 5;

   -- 最近命令与结果（引导/取消/重生成审计）
   select id, state, created_at from durable_command order by created_at desc limit 20;
   ```

3. **回传**：`/workspace/export/rikkahub.db` 直接从工作区文件页导出（或 `adb pull` 工作区目录），
   本地再用同一条 SQL 复核。

## 三、脚本

仓库外脚本 `work/rootfs_db_pull.py`（本机跑，需要 adb 与目标机授权）：

```text
python work\rootfs_db_pull.py --serial <设备序列号> --out work\db_$(date +%m%d).db
```

它做三件事：`adb exec-out run-as` 起 proot → 复制库到 `/workspace/export/` → `adb pull` 回本地；
失败时打印可执行的下一步（而不是只报「失败」）。

## 四、结论与边界

- **结论**：rootfs 路径可在**非 root 真机**上拿到完整库（含 message_node 与 conversation 全列），
  足以替代 `run-as`（debug 包专用）。
- **边界**：`-wal` / `-shm` 未一起导出时，最近一次写入可能不在库里——**排障要连 WAL 一起拿**，
  或用 `sqlite3 ... "PRAGMA wal_checkpoint(FULL);"` 先合并再导出。
- **不适用**：加密库（若未来启用 SQLCipher）与多用户（第二用户）会话——后者需要先走权限门。
