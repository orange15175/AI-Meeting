-- 重构后全新部署所需的基础表 DDL（MySQL 8.0+，utf8mb4）。
-- 只包含当前仍被业务读写的 4 张基础表；面试/聊天的 6 张核心表见 interview_core.sql。
-- 已退役的 agent_file_asset / agent_properties / agent_tag / ai_message_media
-- 不再建表，历史归档见 table.sql（仅作备份参考，不用于新库初始化）。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS admin_permission
(
    id          bigint auto_increment comment 'ID'
        primary key,
    user_id     bigint               not null comment '用户ID',
    username    varchar(256)         not null comment '用户名',
    is_admin    tinyint(1) default 0 null comment '是否管理员 0：普通用户 1：管理员',
    create_time datetime             null comment '创建时间',
    update_time datetime             null comment '修改时间',
    del_flag    tinyint(1) default 0 null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_user_id
        unique (user_id)
)
    comment '管理员权限表';

CREATE INDEX idx_admin_permission_is_admin ON admin_permission (is_admin);
CREATE INDEX idx_admin_permission_username ON admin_permission (username);

CREATE TABLE IF NOT EXISTS ai_properties
(
    id                     bigint auto_increment comment 'ID'
        primary key,
    ai_name                varchar(256)               not null comment 'AI名称',
    ai_type                varchar(64)                not null comment 'AI类型（当前仅 deepseek）',
    api_key                varchar(512)               not null comment 'API密钥（占位，实际密钥由环境变量注入）',
    api_secret             varchar(512)               null comment 'API密钥（部分AI需要）',
    api_url                varchar(512)               null comment 'API地址',
    model_name             varchar(256)               null comment '模型名称',
    max_tokens             int           default 4096 null comment '最大token数',
    temperature            decimal(3, 2) default 0.70 null comment '温度参数',
    system_prompt          text                       null comment '系统提示词',
    is_enabled             tinyint(1)    default 1    null comment '是否启用 0：禁用 1：启用',
    enable_thinking        tinyint(1)    default 0    null comment '是否开启思考模式（DeepSeek专用）',
    thinking_budget_tokens int                        null comment '思考模式预算Token数',
    create_time            datetime                   null comment '创建时间',
    update_time            datetime                   null comment '修改时间',
    del_flag               tinyint(1)    default 0    null comment '删除标识 0：未删除 1：已删除',
    project_id             varchar(255)               null comment '项目ID',
    organization_id        varchar(255)               null comment '组织ID'
)
    comment 'AI配置表';

CREATE INDEX idx_ai_properties_ai_type ON ai_properties (ai_type);

CREATE TABLE IF NOT EXISTS interview_record
(
    id                    bigint auto_increment comment '主键ID'
        primary key,
    user_id               bigint                             not null comment '用户ID',
    session_id            varchar(64)                        not null comment '会话ID',
    interview_score       int                                null comment '面试得分',
    resume_score          int                                null comment '简历得分',
    interview_status      varchar(32)                        null comment '面试状态: INIT/IN_PROGRESS/FINISHED/EVALUATED',
    question_count        int                                null comment '面试题数量',
    interviewer_agent_id  bigint                             null comment '面试官Agent ID（重构后为 NULL，保留兼容列）',
    interview_suggestions text                               null comment '面试建议',
    interview_direction   varchar(128)                       null comment '面试方向',
    start_time            datetime                           null comment '开始时间',
    end_time              datetime                           null comment '结束时间',
    duration_seconds      int                                null comment '面试时长(秒)',
    session_snapshot_json longtext                           null comment '会话快照JSON',
    create_time           datetime default CURRENT_TIMESTAMP null comment '创建时间',
    update_time           datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    del_flag              tinyint  default 0                 not null comment '删除标记: 0未删除 1已删除'
)
    comment '面试记录表' collate = utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uk_interview_record_user_session_del
    ON interview_record (user_id, session_id, del_flag);
CREATE INDEX idx_interview_record_session_id ON interview_record (session_id);

CREATE TABLE IF NOT EXISTS t_user
(
    id            bigint auto_increment comment 'ID'
        primary key,
    username      varchar(256) null comment '用户名',
    password      varchar(512) null comment '密码',
    real_name     varchar(256) null comment '真实姓名',
    phone         varchar(128) null comment '手机号',
    mail          varchar(512) null comment '邮箱',
    deletion_time bigint       null comment '注销时间戳',
    create_time   datetime     null comment '创建时间',
    update_time   datetime     null comment '修改时间',
    del_flag      tinyint(1)   null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_username
        unique (username)
);
