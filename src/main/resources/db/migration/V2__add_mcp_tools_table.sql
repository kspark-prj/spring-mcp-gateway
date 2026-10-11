-- Flyway 마이그레이션 V2: 업스트림 서버별 도구 레지스트리 테이블 추가
-- 해시 기반 Bulk Upsert, is_enabled 독립 토글, 무기한 Caffeine 캐시 연동 기반

----------------------------------------------------
-- mcp_tools 테이블 (서버별 도구 메타데이터 영속 레지스트리)
----------------------------------------------------
CREATE TABLE mcp_tools (
    id              BIGINT PRIMARY KEY,
    server_id       BIGINT NOT NULL REFERENCES mcp_servers(id) ON DELETE CASCADE,
    server_name     VARCHAR(100) NOT NULL,
    tool_name       VARCHAR(200) NOT NULL,           -- 원본 tool name (namespace 미포함)
    namespaced_name VARCHAR(300) NOT NULL UNIQUE,    -- {server_name}__{tool_name}
    description     TEXT,
    input_schema    TEXT,                            -- JSON 직렬화 inputSchema
    tool_hash       VARCHAR(64) NOT NULL,            -- SHA-256(tool_name + description + inputSchema)
    is_enabled      BOOLEAN NOT NULL DEFAULT TRUE,   -- 관리자 수동 토글 (업서트 시 초기화되지 않음)
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_tools IS '업스트림 MCP 서버별 도구 메타데이터 영속 레지스트리 (해시 기반 Bulk Upsert 및 is_enabled 독립 토글 관리)';
COMMENT ON COLUMN mcp_tools.id IS '도구 레지스트리 항목 고유 ID (TSID Long)';
COMMENT ON COLUMN mcp_tools.server_id IS '연관된 업스트림 MCP 서버 ID (FK → mcp_servers.id)';
COMMENT ON COLUMN mcp_tools.server_name IS '네임스페이스용 서버 이름 (비정규화 캐시)';
COMMENT ON COLUMN mcp_tools.tool_name IS '업스트림 원본 도구 이름 (네임스페이스 미포함)';
COMMENT ON COLUMN mcp_tools.namespaced_name IS '{server_name}__{tool_name} 형태의 전역 고유 도구 식별자';
COMMENT ON COLUMN mcp_tools.description IS '도구 설명 텍스트';
COMMENT ON COLUMN mcp_tools.input_schema IS 'JSON Schema 직렬화된 inputSchema';
COMMENT ON COLUMN mcp_tools.tool_hash IS 'SHA-256(tool_name || description || inputSchema) 변경 감지 해시값';
COMMENT ON COLUMN mcp_tools.is_enabled IS '관리자 수동 활성/비활성 토글 (동기화 시 초기화되지 않음, 신규 등록 시 기본값 true)';
COMMENT ON COLUMN mcp_tools.created_at IS '최초 도구 등록 일시';
COMMENT ON COLUMN mcp_tools.updated_at IS '도구 메타데이터 최종 갱신 일시';

-- 조회 성능 최적화 인덱스
CREATE INDEX idx_mcp_tools_server_id ON mcp_tools(server_id);
CREATE INDEX idx_mcp_tools_is_enabled ON mcp_tools(is_enabled);
CREATE INDEX idx_mcp_tools_server_name ON mcp_tools(server_name);

-- 서버별 도구 목록 해시 집계 테이블 (서버 단위 변경 감지용 최신 해시 관리)
CREATE TABLE mcp_server_tool_hash (
    server_id   BIGINT PRIMARY KEY REFERENCES mcp_servers(id) ON DELETE CASCADE,
    list_hash   VARCHAR(64) NOT NULL,    -- SHA-256(전체 도구 목록 직렬화값)
    tool_count  INT NOT NULL DEFAULT 0,
    synced_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_server_tool_hash IS '서버별 전체 도구 목록의 SHA-256 해시값 관리 테이블 (변경 감지 및 Bulk Upsert 부하 차단용)';
COMMENT ON COLUMN mcp_server_tool_hash.server_id IS '연관된 업스트림 MCP 서버 ID (PK = FK)';
COMMENT ON COLUMN mcp_server_tool_hash.list_hash IS '도구 전체 목록의 SHA-256 해시값 (변경 미감지 시 Upsert 스킵)';
COMMENT ON COLUMN mcp_server_tool_hash.tool_count IS '마지막 동기화 시 등록된 도구 수';
COMMENT ON COLUMN mcp_server_tool_hash.synced_at IS '마지막 성공적 동기화 일시';
