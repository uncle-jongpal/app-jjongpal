-- 행 단위 권한 (RLS) — 일반 사용자는 자기 데이터만, 어드민은 전체

-- 사용자별 데이터 테이블에 RLS 활성화
ALTER TABLE events                     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audio_files                ENABLE ROW LEVEL SECURITY;
ALTER TABLE transcripts                ENABLE ROW LEVEL SECURITY;
ALTER TABLE summaries                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE todos                      ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointments               ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE users                      ENABLE ROW LEVEL SECURITY;

-- 정책 헬퍼: 어드민이면 모든 행, 일반 사용자면 자기 user_id 일치 행만
-- (어드민이지만 자기 데이터도 일반 사용자처럼 자기 폰에선 보이게)

CREATE POLICY users_self_or_admin ON users
    FOR SELECT
    USING (
        public.current_user_role() = 'admin'
        OR id = public.current_user_id()
    );

CREATE POLICY users_self_update ON users
    FOR UPDATE
    USING (id = public.current_user_id());

CREATE POLICY events_user_isolation ON events
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY audio_files_user_isolation ON audio_files
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY transcripts_user_isolation ON transcripts
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY summaries_user_isolation ON summaries
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY todos_user_isolation ON todos
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY appointments_user_isolation ON appointments
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());

CREATE POLICY devices_user_isolation ON devices
    FOR ALL
    USING (
        public.current_user_role() = 'admin'
        OR user_id = public.current_user_id()
    )
    WITH CHECK (user_id = public.current_user_id());
