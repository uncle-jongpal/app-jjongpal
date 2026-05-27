-- 사용자 활동 로그 (영구 기록)
-- 로그인 / 로그아웃 / API 호출 등 흔적 추적

CREATE TABLE IF NOT EXISTS user_activity_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      INTEGER REFERENCES users(id) ON DELETE SET NULL,
    device_id    UUID REFERENCES devices(id) ON DELETE SET NULL,
    action       TEXT NOT NULL,                     -- 'login.success'|'login.fail'|'logout'|'refresh'|'upload.audio'|'event.create'|'sync.batch' 등
    target       TEXT,                              -- 액션의 대상 (예: 이벤트 ID, 통화 파일 ID)
    ip_address   INET,
    user_agent   TEXT,
    metadata     JSONB,                             -- 액션별 추가 정보
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_activity_user_time ON user_activity_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_action ON user_activity_log(action);
CREATE INDEX IF NOT EXISTS idx_activity_recent ON user_activity_log(created_at DESC);

-- 소유권 + 권한
ALTER TABLE user_activity_log OWNER TO jjongpal;
GRANT SELECT, INSERT ON user_activity_log TO jjongpal_user, jjongpal_admin;
GRANT USAGE, SELECT ON SEQUENCE user_activity_log_id_seq TO jjongpal_user, jjongpal_admin;

-- 행 단위 권한 (사용자는 자기 활동만, 어드민은 전체)
ALTER TABLE user_activity_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_activity_log_user_isolation ON user_activity_log
    FOR SELECT
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    );
CREATE POLICY user_activity_log_insert_anyone ON user_activity_log
    FOR INSERT
    WITH CHECK (TRUE);  -- 서비스가 비인증 흐름에서도 기록 가능 (예: 로그인 실패)

SELECT 'user_activity_log created' AS status;
