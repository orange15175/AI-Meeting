-- 面试核心表（P3，MySQL 权威化）
-- 目标库：mainshi_agent，MySQL 8.0+
-- 幂等脚本：可重复执行，不 DROP 任何表。
-- 设计要点：
--   1) 会话表持有流程游标、版本号与操作代次，条件更新负责拒绝迟到的旧执行者；
--   2) 回答提交表以 (session_id, question_no) 唯一，保证"每题至多一个有效提交"；
--      重试复用同一行并递增 attempt_token，(session_id, request_id) 索引支撑幂等回放；
--   3) 长文本用 LONGTEXT，结构字段用 JSON（写入方保证合法 JSON 或 NULL）。

CREATE TABLE IF NOT EXISTS `interview_session`
(
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`           VARCHAR(64)     NOT NULL COMMENT '业务会话ID',
    `user_id`              BIGINT          NOT NULL COMMENT '归属用户',
    `status`               VARCHAR(32)     NOT NULL COMMENT '会话状态 DRAFT/RESUME_UPLOADING/READY/IN_PROGRESS/FINISHED/ABANDONED',
    `flow_status`          VARCHAR(32)     NOT NULL DEFAULT 'INIT' COMMENT '流程状态 INIT/ASKING/EVALUATING/FOLLOW_UP/COMPLETED',
    `conversation_title`   VARCHAR(255)             DEFAULT NULL COMMENT '会话标题',
    `interview_type`       VARCHAR(64)              DEFAULT NULL COMMENT '面试方向',
    `resume_file_url`      VARCHAR(512)             DEFAULT NULL COMMENT '简历相对路径',
    `current_question_no`  VARCHAR(16)              DEFAULT NULL COMMENT '当前题号，如 3 或 3-F1',
    `total_questions`      INT             NOT NULL DEFAULT 0 COMMENT '主问题总数',
    `follow_up_count`      INT             NOT NULL DEFAULT 0 COMMENT '当前主问题已追问次数',
    `max_follow_up`        INT             NOT NULL DEFAULT 2 COMMENT '每个主问题最多追问次数',
    `version`              INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `operation_epoch`      BIGINT          NOT NULL DEFAULT 0 COMMENT '操作代次，随每次流程推进递增',
    `active_submission_id` VARCHAR(64)              DEFAULT NULL COMMENT '处理中的提交ID',
    `start_time`           DATETIME                 DEFAULT NULL COMMENT '开始答题时间',
    `end_time`             DATETIME                 DEFAULT NULL COMMENT '结束时间',
    `create_time`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`             TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_status_update` (`user_id`, `del_flag`, `status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '面试会话';

CREATE TABLE IF NOT EXISTS `interview_material`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(64)     NOT NULL COMMENT '业务会话ID',
    `parse_status`      VARCHAR(32)     NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
    `parse_version`     INT             NOT NULL DEFAULT 1 COMMENT '解析版本，重试出题时递增',
    `interview_type`    VARCHAR(64)              DEFAULT NULL COMMENT '面试方向',
    `resume_file_url`   VARCHAR(512)             DEFAULT NULL COMMENT '简历相对路径',
    `resume_text`       LONGTEXT                 DEFAULT NULL COMMENT 'PDF 抽取文本，用于重试出题不重复解析',
    `resume_context`    JSON                     DEFAULT NULL COMMENT '简历结构化上下文',
    `suggestions_json`  JSON                     DEFAULT NULL COMMENT '候选人可见的改进建议',
    `resume_score`      INT                      DEFAULT NULL COMMENT '简历分，NULL 表示未评分',
    `jd_source`         VARCHAR(16)              DEFAULT NULL COMMENT '岗位来源 TEXT/FILE，NULL 表示无 JD',
    `jd_file_url`       VARCHAR(512)             DEFAULT NULL COMMENT 'JD 原件相对路径',
    `jd_file_name`      VARCHAR(255)             DEFAULT NULL COMMENT 'JD 原文件名，仅展示用',
    `jd_text`           MEDIUMTEXT               DEFAULT NULL COMMENT 'JD 解析文本',
    `jd_context`        JSON                     DEFAULT NULL COMMENT '岗位结构化上下文',
    `model_name`        VARCHAR(64)              DEFAULT NULL COMMENT '产出该结果的模型',
    `prompt_version`    VARCHAR(64)              DEFAULT NULL COMMENT '产出该结果的提示词版本',
    `error_message`     VARCHAR(512)             DEFAULT NULL COMMENT '失败原因',
    `response_time`     INT                      DEFAULT NULL COMMENT '模型响应耗时(ms)',
    `token_count`       INT                      DEFAULT NULL COMMENT 'token 用量',
    `raw_response`      LONGTEXT                 DEFAULT NULL COMMENT '模型原始输出，排障用',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_material_session` (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '面试材料（简历解析与出题结果）';

CREATE TABLE IF NOT EXISTS `interview_question_item`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`      VARCHAR(64)     NOT NULL COMMENT '业务会话ID',
    `question_no`     VARCHAR(16)     NOT NULL COMMENT '题号：主问题 1..N，追问 1-F1',
    `kind`            VARCHAR(16)     NOT NULL COMMENT 'MAIN/FOLLOW_UP',
    `main_index`      INT             NOT NULL COMMENT '所属主问题序号，从 1 开始',
    `follow_up_index` INT             NOT NULL DEFAULT 0 COMMENT '追问序号，主问题为 0',
    `parent_id`       BIGINT UNSIGNED          DEFAULT NULL COMMENT '追问所属主问题行ID',
    `content`         TEXT            NOT NULL COMMENT '题干',
    `category`        VARCHAR(64)              DEFAULT NULL COMMENT '考察分类',
    `points_json`     JSON                     DEFAULT NULL COMMENT '评分要点，仅服务端使用',
    `target_context`  JSON                     DEFAULT NULL COMMENT '题目对应的岗位要求与证据，仅服务端使用',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`        TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_no` (`session_id`, `question_no`),
    KEY `idx_session_kind_index` (`session_id`, `kind`, `main_index`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '面试题目';

CREATE TABLE IF NOT EXISTS `interview_answer_submission`
(
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `submission_id`       VARCHAR(64)     NOT NULL COMMENT '提交ID',
    `session_id`          VARCHAR(64)     NOT NULL COMMENT '业务会话ID',
    `question_no`         VARCHAR(16)     NOT NULL COMMENT '题号',
    `request_id`          VARCHAR(96)     NOT NULL COMMENT '请求ID，同次重试复用',
    `input_hash`          CHAR(64)                 DEFAULT NULL COMMENT '输入指纹',
    `answer_content`      LONGTEXT                 DEFAULT NULL COMMENT '原始回答',
    `skipped`             TINYINT         NOT NULL DEFAULT 0 COMMENT '是否跳过',
    `status`              VARCHAR(16)     NOT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED',
    `stage`               VARCHAR(32)              DEFAULT NULL COMMENT '已完成阶段',
    `attempt_token`       VARCHAR(64)              DEFAULT NULL COMMENT '本次尝试令牌，用于拒绝迟到结果',
    `attempt_count`       INT             NOT NULL DEFAULT 1 COMMENT '尝试次数',
    `score`               INT                      DEFAULT NULL COMMENT '得分，NULL 表示未评分',
    `feedback`            TEXT                     DEFAULT NULL COMMENT '评语',
    `missing_points`      JSON                     DEFAULT NULL COMMENT '缺失要点',
    `response_json`       JSON                     DEFAULT NULL COMMENT '可回放的响应快照',
    `error_message`       VARCHAR(512)             DEFAULT NULL COMMENT '失败原因',
    `finished`            TINYINT         NOT NULL DEFAULT 0 COMMENT '该题是否为最后一题',
    `duration_ms`         INT                      DEFAULT NULL COMMENT '模型耗时(ms)',
    `create_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`            TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_submission_id` (`submission_id`),
    UNIQUE KEY `uk_session_question` (`session_id`, `question_no`),
    KEY `idx_session_request` (`session_id`, `request_id`),
    KEY `idx_session_status` (`session_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '面试回答提交';

CREATE TABLE IF NOT EXISTS `ai_conversation`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(64)     NOT NULL COMMENT '会话ID',
    `user_id`           BIGINT                   DEFAULT NULL COMMENT '归属用户',
    `username`          VARCHAR(64)              DEFAULT NULL COMMENT '用户名',
    `title`             VARCHAR(255)             DEFAULT NULL COMMENT '标题',
    `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '1-进行中 2-已结束',
    `message_count`     INT             NOT NULL DEFAULT 0 COMMENT '消息数',
    `version`           INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `active_request_id` VARCHAR(96)              DEFAULT NULL COMMENT '处理中的轮次',
    `last_message_time` DATETIME                 DEFAULT NULL COMMENT '最后一条消息时间',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_session` (`session_id`),
    KEY `idx_user_update` (`user_id`, `del_flag`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '普通聊天会话';

CREATE TABLE IF NOT EXISTS `ai_message`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(64)     NOT NULL COMMENT '会话ID',
    `request_id`        VARCHAR(96)     NOT NULL COMMENT '轮次ID',
    `message_seq`       INT             NOT NULL COMMENT '会话内顺序',
    `message_type`      TINYINT         NOT NULL COMMENT '1-用户 2-AI',
    `status`            VARCHAR(16)     NOT NULL DEFAULT 'SUCCEEDED' COMMENT 'PROCESSING/SUCCEEDED/FAILED',
    `message_content`   LONGTEXT                 DEFAULT NULL COMMENT '正文',
    `reasoning_content` LONGTEXT                 DEFAULT NULL COMMENT '推理内容',
    `parent_msg_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '父消息ID',
    `model_name`        VARCHAR(64)              DEFAULT NULL COMMENT '模型名',
    `token_count`       INT                      DEFAULT NULL COMMENT 'token 用量',
    `response_time`     INT                      DEFAULT NULL COMMENT '响应耗时(ms)',
    `error_message`     VARCHAR(512)             DEFAULT NULL COMMENT '失败原因',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `del_flag`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_seq` (`session_id`, `message_seq`),
    UNIQUE KEY `uk_message_turn_role` (`session_id`, `request_id`, `message_type`),
    KEY `idx_session_seq` (`session_id`, `message_seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '普通聊天消息';
