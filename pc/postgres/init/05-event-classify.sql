-- 알림 / 통화 이벤트 자체에 대한 처리 상태 컬럼 추가
-- (transcripts 의 summary_status 와 별개 — 알림은 transcript 없이 events 에 본문 있음)

ALTER TABLE events ADD COLUMN IF NOT EXISTS processing_status TEXT NOT NULL DEFAULT 'PENDING';
-- 'PENDING' | 'PROCESSING' | 'DONE' | 'SKIPPED' | 'FAILED'

ALTER TABLE events ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ;
ALTER TABLE events ADD COLUMN IF NOT EXISTS processing_error TEXT;

CREATE INDEX IF NOT EXISTS idx_events_processing ON events(processing_status, type)
    WHERE processing_status IN ('PENDING', 'PROCESSING');

-- 이벤트 처리 이력 (알림 분류 등)
CREATE TABLE IF NOT EXISTS event_processing_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    stage               TEXT NOT NULL,         -- 'claude_classify'
    status              TEXT NOT NULL,         -- 'STARTED' | 'OK' | 'SKIPPED' | 'FAILED'
    message             TEXT,
    duration_ms         INTEGER,
    prompt_version_id   INTEGER REFERENCES prompt_versions(id),
    todos_extracted     INTEGER DEFAULT 0,
    appointments_extracted INTEGER DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT event_log_status_chk CHECK (status IN ('STARTED', 'OK', 'SKIPPED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_event_log_event ON event_processing_log(event_id);
CREATE INDEX IF NOT EXISTS idx_event_log_recent ON event_processing_log(created_at DESC);

ALTER TABLE event_processing_log OWNER TO jjongpal;
GRANT SELECT, INSERT ON event_processing_log TO jjongpal_user, jjongpal_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO jjongpal_user, jjongpal_admin;

SELECT 'event_classify schema added' AS status;
