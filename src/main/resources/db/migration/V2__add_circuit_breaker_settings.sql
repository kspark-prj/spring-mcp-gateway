-- Flyway 마이그레이션 V2: mcp_servers 서킷브레이커 동적 설정 컬럼 추가

ALTER TABLE mcp_servers ADD COLUMN IF NOT EXISTS failure_rate_threshold FLOAT NOT NULL DEFAULT 50.0;
ALTER TABLE mcp_servers ADD COLUMN IF NOT EXISTS sliding_window_size INT NOT NULL DEFAULT 10;
ALTER TABLE mcp_servers ADD COLUMN IF NOT EXISTS minimum_number_of_calls INT NOT NULL DEFAULT 5;
ALTER TABLE mcp_servers ADD COLUMN IF NOT EXISTS wait_duration_in_open_state INT NOT NULL DEFAULT 10;

COMMENT ON COLUMN mcp_servers.failure_rate_threshold IS '서킷브레이커 실패율 임계값 (백분율)';
COMMENT ON COLUMN mcp_servers.sliding_window_size IS '서킷브레이커 슬라이딩 윈도우 크기';
COMMENT ON COLUMN mcp_servers.minimum_number_of_calls IS '서킷브레이커 최소 호출 수';
COMMENT ON COLUMN mcp_servers.wait_duration_in_open_state IS '서킷브레이커 열림 상태 대기 시간 (초)';
