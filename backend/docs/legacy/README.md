# 历史资料

这里保存重构前的领域知识、星辰工作流、旧提示词、旧 SQL 与历史素材，仅供查阅，不参与当前应用运行。

- `skills/`：重构前的领域知识包；当前实现以生产源码、根 README 和最新实施记录为准。
- `xingchen-workflows/`：已退役的星辰工作流。
- `prompts/interview/`：已经被 `PromptLibrary` 中新版本替代的提示词。
- `sql/table.sql`：包含退役表的历史全量 SQL，不能作为新库初始化脚本。
- `assets/`：旧架构图及未被当前文档引用的展示素材。

当前运行资源在 `admin/src/main/resources/`（相对 backend），全新数据库初始化顺序见仓库根 README；已有数据库按对应 migration 升级。
