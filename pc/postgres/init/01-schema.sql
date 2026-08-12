-- 쫑팔이삼촌 — 디비 스키마
-- 일반 관계형 설계. 사용자 ID 일반화. 의미 단어 (me/wife 등) 박지 않음.

-- ===== 익명 역할 =====
-- PostgREST 가 비인증 요청에서 사용. 인증 후엔 JWT 의 role 클레임으로 전환.
CREATE ROLE jjongpal_anon NOLOGIN;
CREATE ROLE jjongpal_user NOLOGIN;
CREATE ROLE jjongpal_admin NOLOGIN;

-- ===== 확장 =====
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- gen_random_uuid()

-- ===== 사용자 =====
CREATE TABLE users (
    id              SERIAL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    name            TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'user',
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT users_role_chk CHECK (role IN ('admin', 'user'))
);

CREATE INDEX idx_users_email ON users(email);

-- ===== 디바이스 =====
CREATE TABLE devices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                 TEXT NOT NULL,
    fcm_token            TEXT,
    refresh_token_hash   TEXT,
    revoked_at           TIMESTAMPTZ,
    last_seen            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_user_active ON devices(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_devices_refresh_hash ON devices(refresh_token_hash) WHERE refresh_token_hash IS NOT NULL;

-- ===== 이벤트 (알림 / 통화 통합) =====
CREATE TABLE events (
    id                TEXT PRIMARY KEY,
    user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id         UUID REFERENCES devices(id) ON DELETE SET NULL,
    type              TEXT NOT NULL,
    source_package    TEXT,
    title             TEXT,
    content           TEXT,
    timestamp         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    device_timestamp  TIMESTAMPTZ,
    metadata_json     JSONB,
    CONSTRAINT events_type_chk CHECK (type IN ('notification', 'call'))
);

CREATE INDEX idx_events_user_time ON events(user_id, timestamp DESC);
CREATE INDEX idx_events_type ON events(type);

-- ===== 통화 음성 파일 메타 =====
CREATE TABLE audio_files (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id             INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_path           TEXT,
    size_bytes          BIGINT,
    duration_sec        INTEGER,
    transcript_status   TEXT NOT NULL DEFAULT 'PENDING',
    error_message       TEXT,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ,
    CONSTRAINT audio_files_status_chk CHECK (transcript_status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_audio_status ON audio_files(transcript_status) WHERE transcript_status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_audio_user ON audio_files(user_id);

-- ===== 텍스트 변환 =====
CREATE TABLE transcripts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audio_file_id   UUID REFERENCES audio_files(id) ON DELETE CASCADE,
    event_id        TEXT REFERENCES events(id) ON DELETE CASCADE,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text            TEXT NOT NULL,
    language        TEXT NOT NULL DEFAULT 'ko',
    summary_status  TEXT NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT transcripts_status_chk CHECK (summary_status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_transcripts_status ON transcripts(summary_status) WHERE summary_status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_transcripts_user ON transcripts(user_id);

-- ===== 프롬프트 버전 =====
CREATE TABLE prompt_versions (
    id           SERIAL PRIMARY KEY,
    name         TEXT NOT NULL,
    hash         TEXT NOT NULL,
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (name, hash)
);

CREATE INDEX idx_prompt_versions_name ON prompt_versions(name);

-- ===== 요약 =====
CREATE TABLE summaries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id       UUID REFERENCES transcripts(id) ON DELETE CASCADE,
    event_id            TEXT REFERENCES events(id) ON DELETE CASCADE,
    user_id             INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    summary             TEXT,
    raw_json            JSONB,
    prompt_version_id   INTEGER REFERENCES prompt_versions(id),
    pushed              BOOLEAN NOT NULL DEFAULT FALSE,
    pushed_at           TIMESTAMPTZ,
    pinned              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_summaries_push ON summaries(pushed, created_at) WHERE pushed = FALSE;
CREATE INDEX idx_summaries_user ON summaries(user_id);

-- ===== 할 일 =====
CREATE TABLE todos (
    id                      TEXT PRIMARY KEY,
    user_id                 INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content                 TEXT NOT NULL,
    source                  TEXT,
    source_event_id         TEXT REFERENCES events(id) ON DELETE SET NULL,
    source_excerpt          TEXT,
    due_at                  TIMESTAMPTZ,
    related_person          TEXT,
    status                  TEXT NOT NULL DEFAULT 'open',
    priority                REAL NOT NULL DEFAULT 0.0,
    completion_confidence   REAL NOT NULL DEFAULT 0.0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    -- 'suggested' = 삼촌이 찾았지만 사용자 확인 대기, 'parked' = 사용자가 보류, 'dismissed' = 사용자가 넘김
    CONSTRAINT todos_status_chk CHECK (status IN ('open', 'done', 'archived', 'suggested', 'dismissed', 'parked'))
);

CREATE INDEX idx_todos_user_status ON todos(user_id, status);
CREATE INDEX idx_todos_due ON todos(due_at) WHERE due_at IS NOT NULL;

-- ===== 약속 =====
CREATE TABLE appointments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_event_id TEXT REFERENCES events(id) ON DELETE SET NULL,
    source_excerpt  TEXT,
    title           TEXT NOT NULL,
    start_at        TIMESTAMPTZ NOT NULL,
    end_at          TIMESTAMPTZ,
    location        TEXT,
    with_person     TEXT,
    confidence      REAL NOT NULL DEFAULT 0.5,
    confirmed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_appointments_user_time ON appointments(user_id, start_at);

-- ===== 처리 이력 (종류별 분리) =====
CREATE TABLE audio_processing_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audio_file_id UUID NOT NULL REFERENCES audio_files(id) ON DELETE CASCADE,
    stage         TEXT NOT NULL,
    status        TEXT NOT NULL,
    message       TEXT,
    duration_ms   INTEGER,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT audio_log_status_chk CHECK (status IN ('STARTED', 'OK', 'FAILED'))
);

CREATE INDEX idx_audio_log_file ON audio_processing_log(audio_file_id);
CREATE INDEX idx_audio_log_recent ON audio_processing_log(created_at DESC);

CREATE TABLE transcript_processing_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id       UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    stage               TEXT NOT NULL,
    status              TEXT NOT NULL,
    message             TEXT,
    duration_ms         INTEGER,
    prompt_version_id   INTEGER REFERENCES prompt_versions(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT transcript_log_status_chk CHECK (status IN ('STARTED', 'OK', 'FAILED'))
);

CREATE INDEX idx_transcript_log_t ON transcript_processing_log(transcript_id);
CREATE INDEX idx_transcript_log_recent ON transcript_processing_log(created_at DESC);

-- ===== 권한 부여 =====
GRANT USAGE ON SCHEMA public TO jjongpal_anon, jjongpal_user, jjongpal_admin;

-- 일반 사용자 / 어드민이 데이터 테이블 접근 가능 (RLS 가 행 단위 격리)
GRANT SELECT, INSERT, UPDATE, DELETE ON
    events, audio_files, transcripts, summaries, todos, appointments, devices
TO jjongpal_user, jjongpal_admin;

GRANT SELECT ON users TO jjongpal_user, jjongpal_admin;
GRANT SELECT ON prompt_versions TO jjongpal_admin;
GRANT SELECT ON audio_processing_log, transcript_processing_log TO jjongpal_admin;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO jjongpal_user, jjongpal_admin;

-- anon 역할은 인증 엔드포인트 외 디비 접근 X (auth-service / upload-receiver 는 디비 직접 접속)
