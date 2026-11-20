# 项目改名记录：xunzhi → ai-meeting

日期：2026-11-19
状态：已完成，全量验证通过

## 1. 改名范围

按使用者要求，把项目代号 `xunzhi` 统一改为 `ai-meeting`。Java 包名因不允许连字符，采用 `aimeeting`：

| 层面 | 旧 | 新 |
| --- | --- | --- |
| Java 根包名 | `com.hewei.hzyjy.xunzhi` | `com.hewei.hzyjy.aimeeting` |
| Maven artifactId | `xunzhi` / `xunzhi-admin` | `ai-meeting` / `ai-meeting-admin` |
| 启动类 | `XunZhiAdminApplication` | `AiMeetingAdminApplication` |
| 应用名 | `xunzhi-agent-admin` | `ai-meeting-admin` |
| 配置前缀 | `xunzhi-agent.*` | `ai-meeting.*` |
| 环境变量 | `XUNZHI_*` | `AI_MEETING_*` |
| API 路径 | `/api/xunzhi/v1/**` | `/api/ai-meeting/v1/**`（前后端同步） |
| Redis 键前缀 | `xunzhi:` / `xunzhi-agent:` | `ai-meeting:` / `ai-meeting:` |
| 线程名前缀 | `xunzhi-*` | `ai-meeting-*` |
| 本地存储目录 | `~/.xunzhi-agent` | `~/.ai-meeting` |
| Docker 容器/卷 | `xunzhi-*` | `ai-meeting-*` |
| 前端 localStorage token 键 | `xunzhi-agent:token` | `ai-meeting:token` |

`docs/legacy/` 下的历史归档文档**保留原貌**（共 43 个文件仍含 xunzhi 字样），以便反映当时架构。

## 2. 实施统计

- Java 包名：236 个文件、759 处 import/包声明引用，物理目录 `xunzhi` → `aimeeting`
- 后端配置前缀/环境变量/内部标识：33 文件、约 83 处
- 前端 API 路径：4 文件、22 处（`auth.ts` / `interview.ts` / `chat.ts` / `http.spec.ts`）
- 文档/配置：14 文件（README、实施计划、P0~P6、剩余清单、视觉记录、架构图 SVG、.env.example、LICENSE、skills/README、ai_properties.sql）
- 特殊修复：启动类重命名 + pom `mainClass` + `AdminPermissionMapper.xml` 命名空间（这两处漏改会导致打包后无法启动 / Mapper 绑定失败）

## 3. 密钥处理（重要）

- `admin/application-local.yaml` 里的 DeepSeek key 值**原样保留**（已脱敏校验仍是 sk-667…），仅把外层前缀从 `xunzhi-agent:` 改为 `ai-meeting:`，因此新配置前缀 `ai-meeting.llm.*` 能正常读到。
- 改名后本地浏览器旧 token（`xunzhi-agent:token`）失效，需**重新登录一次**，属预期行为。

## 4. 验证结果

| 项 | 结果 |
| --- | --- |
| 后端 `mvn clean verify`（含 spotless + 全量测试） | ✅ 62 项，0 失败 / 0 错误 / 0 跳过 |
| 前端 `typecheck` / `test` / `build` | ✅ 0 错误 / 14 项通过 / 构建通过 |
| 打包产物 | ✅ `ai-meeting-admin-1.0-SNAPSHOT.jar` |
| `docker compose config` | ✅ 通过 |
| 当前生效区 xunzhi 残留 | ✅ 0 |
| legacy 归档保留 | ✅ 43 文件原貌 |

> 备注：首次 `verify` 因脚本写入触发 spotless 行尾规则失败，执行 `spotless:apply` 规范化后复验通过。
