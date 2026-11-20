-- 岗位 JD 联合出题：材料表新增岗位字段，题目表新增题目-岗位映射。
-- 仅新增可空列，历史数据保持 NULL，旧流程不传 JD 时行为不变。
-- 新库由 interview_core.sql 直接建全量列，本文件只用于已有库升级。
-- 执行前请先备份 interview_material / interview_question_item。

ALTER TABLE `interview_material`
    ADD COLUMN `jd_source`    VARCHAR(16)  DEFAULT NULL COMMENT '岗位来源 TEXT/FILE，NULL 表示无 JD' AFTER `resume_score`,
    ADD COLUMN `jd_file_url`  VARCHAR(512) DEFAULT NULL COMMENT 'JD 原件相对路径' AFTER `jd_source`,
    ADD COLUMN `jd_file_name` VARCHAR(255) DEFAULT NULL COMMENT 'JD 原文件名，仅展示用' AFTER `jd_file_url`,
    ADD COLUMN `jd_text`      MEDIUMTEXT   DEFAULT NULL COMMENT 'JD 解析文本' AFTER `jd_file_name`,
    ADD COLUMN `jd_context`   JSON         DEFAULT NULL COMMENT '岗位结构化上下文' AFTER `jd_text`;

ALTER TABLE `interview_question_item`
    ADD COLUMN `target_context` JSON DEFAULT NULL COMMENT '题目对应的岗位要求与证据，仅服务端使用' AFTER `points_json`;
