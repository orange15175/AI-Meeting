# 岗位 JD 联合出题实施记录

编写日期：2026-11-20。状态：后端与前端已完成，自动化验证通过；真实模型与浏览器验收待执行。

对应计划：`docs/岗位JD联合出题修改计划.md`。本文记录实际落地方式、对计划的修正，以及尚未验证的部分。

## 1. 完成范围

- 简历仍为必填；岗位 JD 支持「不提供 / 粘贴文字 / 上传文件」三种模式，文字与文件互斥。
- JD 文件支持 PDF、PNG、JPG、WebP、BMP；文本 PDF 本地解析，图片与扫描 PDF 走视觉模型。
- 提供 JD 时联合出题，产出岗位定向题与题目-岗位映射；不提供时保持原有简历出题语义。
- JD 独立持久化到材料表，刷新、重启与断点恢复后仍可用于评分和追问。
- 评分、追问提示词引入岗位上下文；报告展示岗位摘要与"已考察 / 待验证"要求清单。
- 清理一个既有缺陷：已进入答题的会话此前可被重新出题，导致题库与回答被重置。

## 2. 对计划的修正（按代码核实）

计划基于若干重构前的事实，本次按当前代码调整：

| 计划原文 | 实际情况 | 处理 |
| --- | --- | --- |
| §7「更新为完整出题输入键，JD 改变时不能命中仅基于简历的旧调用结果」 | P3 已移除分布式 single-flight，`interview/shared` 下无结果缓存；`buildCallKey` 只用于 guard 指标分桶，不存在"命中旧结果" | 摘要改为 `computeExtractionHash`（简历字节 + JD 文本/文件），用途仅为日志与指标；删除"避免命中旧结果"的要求 |
| §5.2 建议新增 `material_hash` 列 | `InterviewMaterialStore` 无按 hash 复用逻辑，该列没有消费方 | 未新增该列，避免死字段；如后续要做"同材料复用解析结果"再补 |
| §7「已开始答题的会话不允许换 JD 重出题」 | `InterviewSessionStatus.isActive()` 含 `IN_PROGRESS`，此限制原本不存在 | 已在 `markResumeUploading` 前置拦截，返回 `B000422`；该问题与 JD 无关，属既有缺陷 |

计划中判定准确、已按原样实现的部分：`Math.min` 页数静默裁剪（改为明确拒绝）、材料同事务重置、MyBatis `updateById` 跳过 null 导致旧 JD 残留（改为显式整列覆盖）、视觉调用未受治理层保护（JD OCR 复用同一视觉客户端，风险与简历 OCR 一致，本次未扩展治理层）。

## 3. 代码改动清单

### 3.1 数据库

- `interview_material` 新增：`jd_source`、`jd_file_url`、`jd_file_name`、`jd_text`、`jd_context`。
- `interview_question_item` 新增：`target_context`。
- 新库：`admin/src/main/resources/sql/interview_core.sql` 已含全部新列。
- 既有库：`admin/src/main/resources/sql/migration/2026-11-20-interview-jd.sql`（只加可空列；compose 的 init 目录按文件名显式挂载 01–05，本文件不会被自动执行）。

### 3.2 后端

| 文件 | 改动 |
| --- | --- |
| `llm/document/InterviewMaterialExtractor.java` | 新增：按 `RESUME` / `JOB_DESCRIPTION` 区分 OCR 提示词与长度预算（20000 / 12000）；扫描件超过 `max-image-pages` 直接拒绝，不再静默裁剪；替换并删除原 `ResumeMaterialExtractor` |
| `interview/flow/extraction/InterviewQuestionExtractionService.java` | JD 校验（互斥、10000 字、10MB、扩展名）、JD 解析与失败明确报错、联合出题、题目岗位映射解析、摘要计算 |
| `interview/flow/extraction/InterviewPreparationCommitService.java` | 命令新增 JD/岗位映射/期望版本；岗位列整列覆盖写入；题目映射回填；失败时保留成功快照，只补诊断字段；提交按请求起始版本做条件更新 |
| `interview/dao/store/InterviewMaterialStore.java` | 新增 `updateJdColumns`（显式写 null）与 `updateFailureDiagnostics` |
| `interview/dao/store/InterviewQuestionStore.java` | 新增 `updateTargetContext` |
| `interview/shared/InterviewJobContextReader.java` | 新增：岗位上下文读取、摘要生成、是否启用判断 |
| `interview/flow/answer/InterviewEvaluationService.java` | 提示词新增 `jobContext` 变量 |
| `interview/flow/answer/InterviewFollowUpService.java` | 提示词新增 `jobContext` 变量 |
| `interview/flow/report/InterviewRecordServiceImpl.java` | 报告补充 `jdSummary` 与 `jdCoverage`（已考察/待验证），只依据题目映射与作答证据，不计算匹配率 |
| `interview/flow/session/InterviewSessionFacade.java` | 出题链路传递 JD 并记录起始版本；恢复接口返回 JD 摘要 |
| `interview/api/InterviewSessionController.java` | 出题接口新增可选 `jdText`、`jdFile` |
| `interview/service/impl/InterviewSessionServiceImpl.java` | `markResumeUploading` 拒绝 `IN_PROGRESS` |
| `common/enums/InterviewErrorCodeEnum.java` | 新增 `INTERVIEW_ALREADY_STARTED`（B000422） |
| `llm/prompt/PromptLibrary.java` | 出题切 v3、评分切 v3、追问切 v2 |

### 3.3 提示词

- `prompts/interview/question-extraction.v3.md`：简历 + 岗位双材料；岗位为「（未提供）」时退回通用出题；新增 `jdContext`、`questionTargets` 输出字段。
- `prompts/interview/answer-evaluation.v3.md`：新增岗位要求段与"岗位要求不作为经历证明"的约束。
- `prompts/interview/follow-up.v2.md`：追问优先覆盖岗位关键能力，但不假定候选人做过相关项目。

### 3.4 前端

- `NewInterviewView.vue`：岗位 JD 三模式切换、字数与文件校验、切换模式清除另一模式数据、提交中禁用重复提交、进度文案区分是否启用 JD。
- `api/interview.ts`：`extractQuestions` 支持可选 `jdText` / `jdFile`，文字优先。
- `types/interview.ts`：补充出题结果、恢复结果与报告中的 JD 字段。
- `ReportView.vue`：新增「岗位要求考察」区块，展示岗位摘要与已考察/待验证清单。

## 4. 接口变更

`POST /api/ai-meeting/v1/interview/sessions/{sessionId}/interview-questions`（multipart）：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `resumePdf` | 是 | 保持历史字段名 |
| `jdText` | 否 | 岗位描述文字，去首尾空白后为空视为未提供，上限 10000 字 |
| `jdFile` | 否 | 岗位 PDF/图片，与 `jdText` 互斥，上限 10MB |

响应新增可选字段：`jdEnabled`、`jdSource`、`jdFileName`、`jdSummary`。恢复接口新增同名 JD 字段；报告新增 `jdSummary`、`jdCoverage`。

## 5. 验证结果

| 项目 | 结果 |
| --- | --- |
| 后端 `mvn clean verify` | 70 项测试通过，0 失败，0 错误 |
| 前端 `npm run typecheck` | 通过 |
| 前端 `npm test` | 14 项通过 |
| 前端 `npm run build` | 通过 |

新增测试覆盖：JD 文字与文件互斥、超长文字拒绝、JD 文本落库来源、JD 文件无文字时明确失败且不调用模型、岗位 OCR 使用专属提示词、扫描件超页数拒绝、`IN_PROGRESS` 会话拒绝重新准备。

## 6. 尚未验证（需真实环境）

1. 真实 DeepSeek 调用的出题质量：岗位定向是否明显、`questionTargets` 是否可追溯。构建通过不能替代人工核验。
2. 真实浏览器链路：三模式切换、失败后保留输入、刷新与重启后的 JD 恢复。
3. 视觉链路：当前视觉端点 `cn-beijing.maas.qianwenapi.com` 在本机 DNS 不可解析，图片/扫描版 JD 无法端到端验证。
4. 既有库执行迁移脚本：需在备份后手动执行 `migration/2026-11-20-interview-jd.sql`。

## 7. 已知限制

- 视觉 OCR 调用未纳入 `AiCallGuardService` 的超时/熔断保护，与简历 OCR 现状一致；如需治理应统一改造视觉客户端。
- 报告覆盖率只统计有题目映射的要求；模型未返回 `questionTargets` 时清单为空，不会凭空生成。
- 计划中明确不做：DOCX、岗位网址抓取、多岗位批量比较、向量库。
