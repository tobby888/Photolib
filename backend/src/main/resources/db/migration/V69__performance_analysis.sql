-- 管理员面板「性能分析」。
--
-- perf_latency_hourly：每个功能（接口「方法 + 路由模板」、图片处理 THUMBNAIL / COMPRESS）每小时一行
-- 耗时直方图，用来算最近一周的 p50 / p90 / p95 / p99。buckets 只存非零格（"桶号:计数,…"），
-- 桶边界由 scheme 决定（LatencyHistogram.SCHEME），换分桶方式必须换 scheme 名。
CREATE TABLE perf_latency_hourly (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    hour_start DATETIME NOT NULL,
    kind VARCHAR(16) NOT NULL,
    op_key VARCHAR(255) NOT NULL,
    label VARCHAR(128) NULL,
    module VARCHAR(64) NULL,
    scheme VARCHAR(16) NOT NULL,
    sample_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    total_micros BIGINT NOT NULL,
    max_micros BIGINT NOT NULL,
    buckets TEXT NOT NULL,
    CONSTRAINT uk_perf_latency_hourly UNIQUE (hour_start, kind, op_key, scheme)
);
CREATE INDEX idx_perf_latency_hourly_hour ON perf_latency_hourly(hour_start);

-- 一次性能快照（管理员手动开启，最长 72 小时）。记录期间的明细落在下面两张表里，
-- 结束后导出成 CSV 存进对象存储，随即删掉明细；这一行永久保留，用来列出和下载 CSV。
-- status：RECORDING → EXPORTING → COMPLETED；导出失败为 FAILED（明细保留，可重新导出）。
-- stop_reason：MANUAL（管理员停止）/ TIMEOUT（到达计划时长）/ LIMIT（事件数到上限）。
CREATE TABLE perf_snapshot_session (
    id VARCHAR(32) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(200) NULL,
    started_by BIGINT NULL,
    started_by_name VARCHAR(100) NULL,
    started_at DATETIME(3) NOT NULL,
    planned_end_at DATETIME(3) NOT NULL,
    ended_at DATETIME(3) NULL,
    stop_reason VARCHAR(16) NULL,
    stopped_by BIGINT NULL,
    stopped_by_name VARCHAR(100) NULL,
    sample_interval_seconds INT NOT NULL,
    event_count BIGINT NOT NULL DEFAULT 0,
    sample_count BIGINT NOT NULL DEFAULT 0,
    dropped_count BIGINT NOT NULL DEFAULT 0,
    events_object_key VARCHAR(512) NULL,
    events_size BIGINT NULL,
    samples_object_key VARCHAR(512) NULL,
    samples_size BIGINT NULL,
    exported_at DATETIME(3) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_perf_snapshot_session_status ON perf_snapshot_session(status);

-- 快照明细：每次 HTTP 请求、SQL、对象存储操作、图片处理各一行。target 是 SQL 原文（只有 ? 占位符，
-- 不含参数值）/ 对象 key / 路由模板；amount 是行数 / 字节数 / HTTP 状态码。
-- session_id 刻意不建外键：导出后按会话整批删除，不需要级联。
CREATE TABLE perf_snapshot_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    category VARCHAR(16) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    duration_micros BIGINT NOT NULL,
    success BOOLEAN NOT NULL,
    target TEXT NULL,
    amount BIGINT NULL,
    context VARCHAR(300) NULL,
    request_id VARCHAR(64) NULL,
    thread_name VARCHAR(100) NULL,
    error VARCHAR(500) NULL
);
CREATE INDEX idx_perf_snapshot_event_session ON perf_snapshot_event(session_id, id);

-- 快照期间按固定间隔采集的系统指标。
CREATE TABLE perf_snapshot_sample (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(32) NOT NULL,
    sampled_at DATETIME(3) NOT NULL,
    process_cpu DOUBLE NULL,
    system_cpu DOUBLE NULL,
    load_average DOUBLE NULL,
    processors INT NOT NULL,
    heap_used BIGINT NOT NULL,
    heap_committed BIGINT NOT NULL,
    heap_max BIGINT NOT NULL,
    non_heap_used BIGINT NOT NULL,
    memory_total BIGINT NULL,
    memory_free BIGINT NULL,
    threads INT NOT NULL,
    gc_count BIGINT NOT NULL,
    gc_time_ms BIGINT NOT NULL,
    db_active INT NULL,
    db_idle INT NULL,
    db_waiting INT NULL,
    db_total INT NULL,
    image_queue INT NULL,
    image_active INT NULL,
    disk_usable BIGINT NOT NULL
);
CREATE INDEX idx_perf_snapshot_sample_session ON perf_snapshot_sample(session_id, id);
