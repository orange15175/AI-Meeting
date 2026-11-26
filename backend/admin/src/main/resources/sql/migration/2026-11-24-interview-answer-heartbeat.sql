-- 答题执行权租约：心跳与固定截止时间。
-- 背景：原实现用 update_time + 5 分钟判断"孤儿提交"，但 PROCESSING 期间业务本来就不写这一行，
-- 该值实际度量的是"业务已经跑了多久"，导致阈值必须大于最长业务耗时（5 分钟），
-- 用户在最坏情况下要干等 5 分钟才能重试。
-- 改为存活信号：执行期间每 20 秒刷 heartbeat_at，60 秒未刷视为失联；
-- 同时用 deadline_at 给单次尝试一个硬上限，防止卡死线程靠心跳无限续命。
--
-- 兼容：两列均为 NULL 的历史行仍按 update_time + 5 分钟判定，见 InterviewSubmissionStore。

ALTER TABLE `interview_answer_submission`
    ADD COLUMN `heartbeat_at` DATETIME(3) DEFAULT NULL COMMENT '当前尝试最后一次成功心跳',
    ADD COLUMN `deadline_at` DATETIME(3) DEFAULT NULL COMMENT '当前尝试的固定截止时间';

-- 说明：
-- 1. 两列都不建索引：判定永远发生在已经用 uk_session_question 定位到的那一行上，建索引只增加写放大。
-- 2. 已有任务的行不会被自动补齐：升级后维护窗口内残留的 PROCESSING 行仍走旧 5 分钟规则。
-- 3. 回滚时保留这两列，旧版本代码不读它们。
