-- V28: platform event bus.
--
-- The external SSE channel previously derived "events" by polling aggregate counters every three
-- seconds, so a subscriber learned that something changed but never what changed, and changes that
-- cancelled out inside one poll were lost. This table is the durable append-only log behind the
-- real event bus: every key mutation (workspace lifecycle, ingestion, extraction, governance,
-- retrieval, ACL) appends one row, giving SSE subscribers rich payloads, giving reconnecting
-- clients Last-Event-ID replay, and doubling as an activity trail.
CREATE TABLE IF NOT EXISTS t_platform_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL COMMENT '对外 Event-ID，供 SSE Last-Event-ID 断线续传定位',
    event_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '业务事件发生时间',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型，见 PlatformEventTypes',
    knowledge_base_id BIGINT NULL COMMENT '所属知识库；平台级事件可为 NULL',
    source VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' COMMENT '来源通道：UI / REST / MCP / SSE / SYSTEM',
    actor_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT '触发者类型：USER / API_KEY / SYSTEM',
    actor_id VARCHAR(64) NULL COMMENT '触发者标识：用户 ID 或 API Key 编码',
    actor_name VARCHAR(128) NULL COMMENT '触发者展示名',
    target_type VARCHAR(32) NULL COMMENT '对象类型：KNOWLEDGE_BASE / CARD / CLAIM / SOURCE',
    target_code VARCHAR(64) NULL COMMENT '对象编码',
    summary VARCHAR(500) NULL COMMENT '一句话可读摘要',
    detail_json JSON NULL COMMENT '事件详细信息，按订阅者权限过滤后下发',
    trace_id VARCHAR(64) NULL COMMENT '链路追踪 ID，可反查 query_trace 与日志',
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时间',
    UNIQUE KEY uk_platform_event_eid (event_id),
    KEY idx_platform_event_kb (knowledge_base_id, id),
    KEY idx_platform_event_type (event_type, id),
    KEY idx_platform_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台关键事件流：SSE 订阅与断线续传';
