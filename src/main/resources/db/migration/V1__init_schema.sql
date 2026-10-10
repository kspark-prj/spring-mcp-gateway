-- Flyway 마이그레이션 V1: MCP 게이트웨이 시스템 초기 데이터베이스 스키마 및 시드 데이터

-- UUID 확장 모듈이 없으면 활성화
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

----------------------------------------------------
-- 1. mcp_servers 테이블 (통합 관리 대상 MCP 업스트림 서버)
----------------------------------------------------
CREATE TABLE mcp_servers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    transport_type VARCHAR(20) NOT NULL,
    endpoint_url TEXT,
    command_line TEXT,
    environment_variables TEXT,
    health_status VARCHAR(20) NOT NULL DEFAULT 'HEALTHY',
    retry_count INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_servers IS '통합 관리 대상 MCP 업스트림 서버 메타데이터 테이블';
COMMENT ON COLUMN mcp_servers.id IS 'MCP 서버 고유 식별자 (UUID)';
COMMENT ON COLUMN mcp_servers.name IS '도구 네임스페이스에 사용되는 서버 고유 식별 이름';
COMMENT ON COLUMN mcp_servers.transport_type IS '전송 프로토콜 유형: HTTP 또는 STDIO';
COMMENT ON COLUMN mcp_servers.endpoint_url IS '원격 HTTP/Streamable HTTP 엔드포인트 URL';
COMMENT ON COLUMN mcp_servers.command_line IS 'STDIO 프로세스 실행 명령어 스펙';
COMMENT ON COLUMN mcp_servers.environment_variables IS '프로세스 환경 변수 JSON 키-값 객체';
COMMENT ON COLUMN mcp_servers.health_status IS '현재 헬스 상태: HEALTHY, UNHEALTHY, DOWN';
COMMENT ON COLUMN mcp_servers.retry_count IS '연속 헬스체크 실패 횟수';
COMMENT ON COLUMN mcp_servers.enabled IS '업스트림 서버의 라우팅 활성화 여부';
COMMENT ON COLUMN mcp_servers.created_at IS '서버 등록 일시';
COMMENT ON COLUMN mcp_servers.updated_at IS '서버 정보 최종 수정 일시';

----------------------------------------------------
-- 2. mcp_api_keys 테이블 (클라이언트 API Key 식별 및 해시)
----------------------------------------------------
CREATE TABLE mcp_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_name VARCHAR(100) NOT NULL,
    key_hash_sha256 VARCHAR(64) NOT NULL UNIQUE,
    master_key BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT true,
    rate_limit_per_minute INT NOT NULL DEFAULT 60,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_api_keys IS '클라이언트 API Key 메타데이터 및 인증 해시 테이블';
COMMENT ON COLUMN mcp_api_keys.id IS 'API Key 고유 식별자 (UUID)';
COMMENT ON COLUMN mcp_api_keys.key_name IS 'API Key 레이블 (사람이 읽을 수 있는 명칭)';
COMMENT ON COLUMN mcp_api_keys.key_hash_sha256 IS '원문 API Key의 SHA-256 핑거프린트 해시값';
COMMENT ON COLUMN mcp_api_keys.master_key IS '무제한 마스터 권한 소유 여부 플래그';
COMMENT ON COLUMN mcp_api_keys.enabled IS 'API Key 활성화 상태 여부';
COMMENT ON COLUMN mcp_api_keys.rate_limit_per_minute IS '분당 요청 제한 임계값';
COMMENT ON COLUMN mcp_api_keys.expires_at IS 'API Key 만료 일시 (null일 경우 무제한)';
COMMENT ON COLUMN mcp_api_keys.created_at IS 'API Key 생성 일시';
COMMENT ON COLUMN mcp_api_keys.updated_at IS 'API Key 최종 수정 일시';

----------------------------------------------------
-- 3. mcp_key_server_permissions 테이블 (API Key별 서버 및 도구 권한 매핑)
----------------------------------------------------
CREATE TABLE mcp_key_server_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_key_id UUID NOT NULL REFERENCES mcp_api_keys(id) ON DELETE CASCADE,
    server_id UUID NOT NULL REFERENCES mcp_servers(id) ON DELETE CASCADE,
    allowed_tools_json TEXT DEFAULT '["*"]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_key_server UNIQUE (api_key_id, server_id)
);

COMMENT ON TABLE mcp_key_server_permissions IS 'API Key별 업스트림 서버 및 도구 단위 인가(Authorization) 매핑 테이블';
COMMENT ON COLUMN mcp_key_server_permissions.id IS '권한 매핑 고유 식별자 (UUID)';
COMMENT ON COLUMN mcp_key_server_permissions.api_key_id IS '연관된 API Key ID';
COMMENT ON COLUMN mcp_key_server_permissions.server_id IS '연관된 업스트림 MCP 서버 ID';
COMMENT ON COLUMN mcp_key_server_permissions.allowed_tools_json IS '허용된 도구 이름 목록 JSON 배열 또는 와일드카드 ["*"]';
COMMENT ON COLUMN mcp_key_server_permissions.created_at IS '권한 부여 일시';

----------------------------------------------------
-- 4. mcp_admin_users 테이블 (대시보드 관리자 계정)
----------------------------------------------------
CREATE TABLE mcp_admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'ROLE_ADMIN',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_admin_users IS '웹 대시보드 관리자 계정 정보 테이블';
COMMENT ON COLUMN mcp_admin_users.id IS '관리자 사용자 고유 ID (UUID)';
COMMENT ON COLUMN mcp_admin_users.username IS '관리자 로그인 아이디';
COMMENT ON COLUMN mcp_admin_users.password_hash IS 'BCrypt로 암호화된 비밀번호 해시값';
COMMENT ON COLUMN mcp_admin_users.role IS '보안 인가 권한 역할';
COMMENT ON COLUMN mcp_admin_users.created_at IS '사용자 계정 생성 일시';
COMMENT ON COLUMN mcp_admin_users.updated_at IS '사용자 계정 최종 수정 일시';

----------------------------------------------------
-- 5. mcp_system_settings 테이블 (전역 시스템 설정 및 동적 토글)
----------------------------------------------------
CREATE TABLE mcp_system_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_system_settings IS '전역 시스템 설정 및 동적 기능 토글 구성 테이블';
COMMENT ON COLUMN mcp_system_settings.id IS '설정 항목 고유 ID (UUID)';
COMMENT ON COLUMN mcp_system_settings.setting_key IS '고유 설정 키 식별자';
COMMENT ON COLUMN mcp_system_settings.setting_value IS '설정 값 텍스트 또는 토글 불리언값';
COMMENT ON COLUMN mcp_system_settings.description IS '사람이 읽을 수 있는 설정 항목 설명';
COMMENT ON COLUMN mcp_system_settings.updated_at IS '설정 항목 최종 수정 일시';

----------------------------------------------------
-- 6. mcp_audit_logs 테이블 (게이트웨이 감사 로그)
----------------------------------------------------
CREATE TABLE mcp_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(64) NOT NULL,
    api_key_id UUID,
    server_id UUID,
    tool_name VARCHAR(150),
    http_method VARCHAR(10) NOT NULL,
    request_payload TEXT,
    response_status INT NOT NULL,
    execution_time_ms BIGINT NOT NULL,
    pii_masked BOOLEAN NOT NULL DEFAULT true,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE mcp_audit_logs IS '게이트웨이 요청 실행 및 보안 감사 로그 테이블';
COMMENT ON COLUMN mcp_audit_logs.id IS '감사 로그 항목 고유 ID (UUID)';
COMMENT ON COLUMN mcp_audit_logs.request_id IS '트레이스 추적용 요청 고유 식별자 (Request ID)';
COMMENT ON COLUMN mcp_audit_logs.api_key_id IS '요청에 사용된 API Key ID';
COMMENT ON COLUMN mcp_audit_logs.server_id IS '대상 업스트림 서버 ID';
COMMENT ON COLUMN mcp_audit_logs.tool_name IS '호출 대상 MCP 도구 이름 (네임스페이스 포함)';
COMMENT ON COLUMN mcp_audit_logs.http_method IS '요청의 HTTP 메서드';
COMMENT ON COLUMN mcp_audit_logs.request_payload IS '정화 및 PII/비밀값 마스킹 처리된 JSON 페이로드';
COMMENT ON COLUMN mcp_audit_logs.response_status IS '응답의 HTTP 상태 코드';
COMMENT ON COLUMN mcp_audit_logs.execution_time_ms IS '실행 소요 시간 (밀리초)';
COMMENT ON COLUMN mcp_audit_logs.pii_masked IS '페이로드에 PII 마스킹 적용 여부';
COMMENT ON COLUMN mcp_audit_logs.error_message IS '실행 실패 시 오류 메시지';
COMMENT ON COLUMN mcp_audit_logs.created_at IS '로그 기록 일시';

-- 성능 향상을 위한 인덱스 생성
CREATE INDEX idx_audit_created_at ON mcp_audit_logs(created_at DESC);
CREATE INDEX idx_audit_api_key ON mcp_audit_logs(api_key_id);
CREATE INDEX idx_audit_server ON mcp_audit_logs(server_id);
CREATE INDEX idx_api_keys_hash ON mcp_api_keys(key_hash_sha256);

----------------------------------------------------
-- 초기 시드 데이터 (Initial Seed Data)
----------------------------------------------------
-- 1. 초기 관리자 계정 (아이디: admin, 비밀번호: admin1 - 검증된 BCrypt 암호화 해시)
INSERT INTO mcp_admin_users (id, username, password_hash, role)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'admin',
    '$2a$10$THsagKBBsV7zgaQFBI2/LOuwZOtlCByx14j/2pbKErQpmdy8Y1YhO',
    'ROLE_ADMIN'
) ON CONFLICT (username) DO UPDATE SET password_hash = '$2a$10$THsagKBBsV7zgaQFBI2/LOuwZOtlCByx14j/2pbKErQpmdy8Y1YhO';

-- 2. 초기 마스터 API Key (원문 키: mcp-gateway-master-key-2026, SHA-256 해시: b3fdafe948dae92812d73cfa0f2f3e52916f2a679b6c14619743e8f739292b97)
INSERT INTO mcp_api_keys (id, key_name, key_hash_sha256, master_key, enabled, rate_limit_per_minute)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'Master Gateway Key',
    'b3fdafe948dae92812d73cfa0f2f3e52916f2a679b6c14619743e8f739292b97',
    true,
    true,
    1000
) ON CONFLICT (key_hash_sha256) DO NOTHING;

-- 3. 초기 전역 시스템 설정 데이터
INSERT INTO mcp_system_settings (setting_key, setting_value, description)
VALUES 
    ('pii_masking_enabled', 'true', '일반 PII 마스킹(이메일, 전화번호) 동적 토글. 인증 비밀값은 토글과 상관없이 항상 강제 마스킹 처리됨.'),
    ('default_read_timeout_ms', '30000', '업스트림 요청의 기본 읽기 타임아웃(밀리초)')
ON CONFLICT (setting_key) DO NOTHING;
