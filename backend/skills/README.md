# skills/ 目录说明

本目录已清空。原有 9 个业务知识 Skill（`ai-meeting-*`）描述的是**重构前**架构：MongoDB 权威存储、星辰 Agent 工作流、讯飞语音/ASR/TTS、神态分析、WebSocket 推送、single-flight 与模型结果缓存、通用 Agent CRUD。这些能力在本轮重构中已全部移除或改写，继续按那些 Skill 开发会给出错误指引。

按 `docs/文字面试平台重构实施计划.md` §11 P6 的要求「清除已废弃技能/文档中对运行必需星辰/讯飞/Mongo 的指引；历史架构文档可归档并标记过时」，这批知识包已整体归档到：

`docs/legacy/skills/`

归档内容仅作历史参考，**不得作为当前实现的依据**。重构后的权威资料是：

- `docs/文字面试平台重构实施计划.md`（rev.6）
- `docs/P0-基线报告.md` 与 `docs/P1~P6-实施记录.md`
- `docs/剩余工作量清单.md`
- `README.md`（技术栈与部署现状）
- `admin/src/main/java` 下的实际源码
