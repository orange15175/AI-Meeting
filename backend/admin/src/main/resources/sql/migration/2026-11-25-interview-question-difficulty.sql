-- 出题分类与难度落库：为复盘与报告展示保留"这题是什么类型、多难"。
-- 背景：v4 出题模板已经让模型逐题产出 category / difficulty，但此前只在内存里用于校验，
-- 题目表虽有 category 列却从未写入，difficulty 列不存在；结果是报告无法回答"本次面试难度构成"，
-- 历史会话也无法回溯。两列都是短枚举字符串，写入成本远低于事后补数据。
--
-- 兼容：两列均可空，历史行保持 NULL；旧版本代码不读这两列，回滚时保留即可。
-- 新库由 interview_core.sql 直接建全量列，本文件只用于已有库升级。
-- 执行前请先备份 interview_question_item。

ALTER TABLE `interview_question_item`
    ADD COLUMN `difficulty` VARCHAR(16) DEFAULT NULL COMMENT '题目难度 EASY/MEDIUM/HARD' AFTER `category`;

-- 说明：
-- 1. category 列在初始建表时已存在，这里不重复添加；若目标库缺失该列，请先手工补
--    `ALTER TABLE interview_question_item ADD COLUMN category VARCHAR(64) DEFAULT NULL AFTER content;`
-- 2. 两列都不建索引：当前没有按分类/难度筛选题目的查询，加了只会增加写入成本。
