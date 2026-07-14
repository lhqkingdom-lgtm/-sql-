CREATE DATABASE IF NOT EXISTS slow_sql_platform DEFAULT CHARACTER SET utf8mb4;
USE slow_sql_platform;

CREATE TABLE IF NOT EXISTS captured_sql (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sql_text TEXT NOT NULL,
    database_name VARCHAR(64),
    instance_id VARCHAR(64),
    project_code VARCHAR(64),
    query_time_sec DOUBLE DEFAULT 0,
    lock_time_sec DOUBLE DEFAULT 0,
    rows_examined BIGINT DEFAULT 0,
    rows_sent BIGINT DEFAULT 0,
    fingerprint VARCHAR(64),
    source VARCHAR(32),
    severity VARCHAR(8) DEFAULT 'P2',
    diagnosis_report TEXT,
    occurrence_count INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_fingerprint (fingerprint),
    INDEX idx_project (project_code),
    INDEX idx_source (source),
    INDEX idx_severity (severity),
    INDEX idx_created (created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS diagnosis_record (
    task_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64),
    instance_id VARCHAR(64),
    project_code VARCHAR(64),
    original_sql TEXT,
    clean_sql TEXT,
    report TEXT,
    status VARCHAR(16) DEFAULT 'running',
    error_message TEXT,
    duration_ms BIGINT DEFAULT 0,
    tool_call_count INT DEFAULT 0,
    source VARCHAR(32),
    fingerprint VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project (project_code),
    INDEX idx_status (status),
    INDEX idx_source (source),
    INDEX idx_created (created_at),
    INDEX idx_fingerprint (fingerprint)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(64),
    tags VARCHAR(500),
    enabled TINYINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 测试表（Performance Schema 采集需要真实的表）
CREATE DATABASE IF NOT EXISTS test_sql DEFAULT CHARACTER SET utf8mb4;
USE test_sql;
CREATE TABLE IF NOT EXISTS t_perf_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',
    user_id BIGINT NOT NULL,
    amount DECIMAL(10,2) DEFAULT 0.00,
    remark VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB;
