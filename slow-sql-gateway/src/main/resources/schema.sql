-- ============================================
-- Slow SQL Analyzer V5.0 平台数据库 DDL
-- 数据库: slow_sql_platform
-- ============================================

-- 1. 诊断记录表
CREATE TABLE IF NOT EXISTS diagnosis_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64)   NOT NULL COMMENT '任务ID(UUID)',
    session_id      VARCHAR(64)   NOT NULL COMMENT '会话ID',
    instance_id     VARCHAR(64)   NOT NULL COMMENT '目标MySQL实例ID',
    project_code    VARCHAR(64)                COMMENT '项目code',
    original_sql    TEXT          NOT NULL COMMENT '原始SQL(脱敏前)',
    clean_sql       TEXT          NOT NULL COMMENT '清洗后SQL(脱敏后)',
    table_names     VARCHAR(2000)              COMMENT '涉及表名(JSON数组)',
    report          MEDIUMTEXT                 COMMENT 'Markdown诊断报告',
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/DEAD',
    error_message   VARCHAR(1000)              COMMENT '失败原因',
    duration_ms     BIGINT        NOT NULL DEFAULT 0 COMMENT '诊断耗时(毫秒)',
    tool_call_count INT           DEFAULT 0   COMMENT 'Agent工具调用次数',
    source          VARCHAR(50)   DEFAULT 'manual' COMMENT '来源: manual/slow_log_table/slow_log_file/http_capture/performance_schema',
    fingerprint     VARCHAR(64)                COMMENT 'SQL指纹 MD5(instanceId:标准化SQL)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME                   COMMENT '最后更新时间',

    INDEX idx_task_id     (task_id),
    INDEX idx_session     (session_id),
    INDEX idx_instance    (instance_id),
    INDEX idx_status      (status),
    INDEX idx_fingerprint (fingerprint),
    INDEX idx_created     (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断记录';

-- 2. 慢SQL采集记录表
CREATE TABLE IF NOT EXISTS captured_sql (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    sql_text         MEDIUMTEXT    NOT NULL COMMENT 'SQL原文',
    database_name    VARCHAR(100)               COMMENT '目标库名',
    instance_id      VARCHAR(64)   NOT NULL COMMENT '来源MySQL实例ID',
    project_code     VARCHAR(64)                COMMENT '项目code',
    query_time_sec   DOUBLE                     COMMENT '查询耗时(秒)',
    lock_time_sec    DOUBLE                     COMMENT '锁等待(秒)',
    rows_examined    BIGINT                     COMMENT '扫描行数',
    rows_sent        BIGINT                     COMMENT '返回行数',
    fingerprint      VARCHAR(64)   NOT NULL COMMENT 'SQL指纹 MD5(instanceId:标准化SQL)',
    source           VARCHAR(50)                COMMENT '来源: slow_log_table/slow_log_file/http_capture/performance_schema',
    occurrence_count INT DEFAULT 1              COMMENT '出现次数',
    diagnosis_report MEDIUMTEXT                 COMMENT '诊断报告(缓存命中时复用)',
    severity         VARCHAR(10)                COMMENT '严重度: P0/P1/P2',
    captured_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',

    UNIQUE INDEX idx_fingerprint (fingerprint),
    INDEX idx_instance  (instance_id),
    INDEX idx_severity  (severity),
    INDEX idx_captured  (captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢SQL采集记录';

-- 3. RAG知识库文档表
CREATE TABLE IF NOT EXISTS rag_document (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(200)  NOT NULL COMMENT '文档标题',
    content       TEXT          NOT NULL COMMENT '文档内容',
    category      VARCHAR(20)   NOT NULL COMMENT '分类: 军规/事故复盘/业务规则',
    tags          VARCHAR(500)  DEFAULT '' COMMENT '标签(逗号分隔)',
    enabled       TINYINT(1)    DEFAULT 1 COMMENT '是否启用',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_category (category),
    INDEX idx_enabled  (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库文档';
