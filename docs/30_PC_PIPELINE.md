# 30 · PC 백엔드 명세

> 작성: 2026-05-20
> 독자: 본인 + PC 측 작업하는 에이전트 (클로드 코드 등)
> 범위: 본인 PC 위에 도는 시스템 전체. 폰 측 코드는 [40_APP_SPEC.md](40_APP_SPEC.md) 참조.

## 0. 한 줄

> 본인 PC = 24/7 가동 = 대규모 언어 모델 호스트 + 디비 + 합본 처리 허브. 두 폰에서 올라온 통화 파일 → 텍스트 변환 → 클로드 코드 정리 → 디비 저장 → 사용자 ID 기반 푸시.

## 1. 시스템 다이어그램

```
[와이프 폰: user_id=<일반 ID>]       [본인 폰: user_id=<일반 ID>]
   쫑팔이삼촌 앱                            쫑팔이삼촌 앱
       │                                     │
       │ HTTPS multipart (.m4a)               │
       │ HTTPS JSON (notifications / todos)   │
       │                                     │
       └─────────┬───────────────────────────┘
                 │
                 ▼  클라우드플레어 터널 (영구)
        ┌─────────────────────────────────┐
        │ 본인 PC (Ubuntu / WSL2 / 도커)    │
        │                                  │
        │ ┌─────────────────────────────┐ │
        │ │ Nginx                          │
        │ │  - /auth/  → auth-service     │
        │ │  - /upload/ → upload-receiver │
        │ │  - /rest/   → postgrest       │
        │ └────┬────────┬───────┬─────────┘
        │      │        │       │
        │      ▼        ▼       ▼
        │ ┌─────────┐ ┌──────────┐ ┌──────────────┐
        │ │ auth-   │ │ upload-  │ │ postgrest    │
        │ │ service │ │ receiver │ │              │
        │ │(FastAPI)│ │ (FastAPI)│ └──────┬───────┘
        │ └────┬────┘ └────┬─────┘        │
        │      │           │              │
        │      ▼           ▼              ▼
        │ ┌──────────────────────────────────┐
        │ │ Postgres                          │
        │ │  users, devices,                  │
        │ │  events, audio_files,             │
        │ │  transcripts, summaries,          │
        │ │  todos, appointments,             │
        │ │  audio_processing_log,            │
        │ │  transcript_processing_log,       │
        │ │  prompt_versions                  │
        │ └────┬─────────────────────────────┘
        │      │ polled by:                    │
        │      ├──────┐ ┌────────────┐         │
        │      ▼      ▼ ▼            ▼         │
        │ ┌────────┐ ┌─────────┐ ┌──────────┐  │
        │ │whisper-│ │ agent-  │ │ fcm-     │  │
        │ │ worker │ │ worker  │ │ pusher   │  │
        │ │(도커)  │ │(호스트  │ │(도커)    │  │
        │ │        │ │ systemd)│ │          │  │
        │ └────────┘ └─────────┘ └──────────┘  │
        │                │                      │
        │           calls `claude`              │
        │                                       │
        └───────────────────────────────────────┘
                       │ 파이어베이스
                       ▼
                  [폰들에 푸시 — 사용자 ID 의 활성 디바이스만]
```

### 에이전트 워커가 도커가 아닌 호스트 systemd 인 이유

클로드 코드 (`claude` 명령줄 도구) 는 본인 PC 호스트에 로컬 로그인된 도구. 도커 컨테이너 안에서 호출하려면 컨테이너 안에 다시 설치하고 호스트 인증 마운트해야 함. 복잡해서 그냥 호스트 systemd 로 둠. 디비 접근은 도커의 호스트 노출 포트 (`localhost:5432`) 사용.

## 2. 디렉토리 구조

```
jjongpal-app/pc/
├── docker-compose.yml
├── .env                      # 시크릿 (깃 무시)
├── postgres/
│   └── init/
│       ├── 01-schema.sql
│       └── 02-rls.sql       # 행 단위 권한 정책
├── postgrest/
│   └── postgrest.conf
├── nginx/
│   └── nginx.conf
├── auth-service/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py
├── upload-receiver/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py
├── whisper-worker/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── worker.py
│   └── models/              # whisper-large-v3
├── agent-worker/             # ← 도커 아님, 호스트 systemd
│   ├── worker.py
│   ├── prompts/
│   │   └── call_summarize.md
│   └── jjongpal-agent.service
├── fcm-pusher/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── pusher.py
│   └── service-account.json # 파이어베이스 시크릿 (깃 무시)
└── cloudflared/
    └── config.yml

~/storage/jjongpal/
├── audio/                   # 통화 파일 (텍스트 변환 후 즉시 삭제)
└── transcripts/             # 텍스트 (영구 보관)
```

## 3. 디비 스키마

### 3.1 핵심 테이블

```sql
-- 사용자 (어드민 / 일반)
CREATE TABLE users (
    id              SERIAL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    name            TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'user', -- 'admin' | 'user'
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 디바이스 (한 사용자당 N대)
CREATE TABLE devices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                 TEXT NOT NULL,
    fcm_token            TEXT,
    refresh_token_hash   TEXT,                              -- 폐기 가능
    revoked_at           TIMESTAMPTZ,                       -- NULL = 활성
    last_seen            TIMESTAMPTZ DEFAULT NOW(),
    created_at           TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_devices_user_active ON devices(user_id) WHERE revoked_at IS NULL;

-- 이벤트 (알림 / 통화 통합)
CREATE TABLE events (
    id                TEXT PRIMARY KEY,                     -- 폰에서 생성 ID
    user_id           INTEGER NOT NULL REFERENCES users(id),
    device_id         UUID REFERENCES devices(id),
    type              TEXT NOT NULL,                        -- 'notification' | 'call'
    source_package    TEXT,
    title             TEXT,
    content           TEXT,
    timestamp         TIMESTAMPTZ NOT NULL DEFAULT NOW(),   -- 서버 수신 시각 (진실)
    device_timestamp  TIMESTAMPTZ,                          -- 폰이 보낸 시각 (보조)
    metadata_json     JSONB
);

CREATE INDEX idx_events_user_time ON events(user_id, timestamp DESC);
CREATE INDEX idx_events_type ON events(type);

-- 통화 음성 파일 메타
CREATE TABLE audio_files (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id             INTEGER NOT NULL REFERENCES users(id),
    file_path           TEXT,                                -- 텍스트 변환 후 NULL
    size_bytes          BIGINT,
    duration_sec        INTEGER,
    transcript_status   TEXT DEFAULT 'PENDING',              -- PENDING|PROCESSING|DONE|FAILED
    error_message       TEXT,
    uploaded_at         TIMESTAMPTZ DEFAULT NOW(),
    processed_at        TIMESTAMPTZ
);

CREATE INDEX idx_audio_status ON audio_files(transcript_status);

-- 텍스트 변환
CREATE TABLE transcripts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audio_file_id   UUID REFERENCES audio_files(id) ON DELETE CASCADE,
    event_id        TEXT REFERENCES events(id),
    user_id         INTEGER NOT NULL REFERENCES users(id),
    text            TEXT NOT NULL,
    language        TEXT DEFAULT 'ko',
    summary_status  TEXT DEFAULT 'PENDING',                  -- PENDING|PROCESSING|DONE|FAILED
    error_message   TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_transcripts_status ON transcripts(summary_status);

-- 프롬프트 버전
CREATE TABLE prompt_versions (
    id              SERIAL PRIMARY KEY,
    name            TEXT NOT NULL,         -- 'call_summarize'
    hash            TEXT NOT NULL,         -- 파일 내용 SHA256
    body            TEXT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (name, hash)
);

-- 요약
CREATE TABLE summaries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id       UUID REFERENCES transcripts(id) ON DELETE CASCADE,
    event_id            TEXT REFERENCES events(id),
    user_id             INTEGER NOT NULL REFERENCES users(id),
    summary             TEXT,
    raw_json            JSONB,
    prompt_version_id   INTEGER REFERENCES prompt_versions(id),
    pushed              BOOLEAN DEFAULT FALSE,
    pushed_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_summaries_push ON summaries(pushed, created_at);

-- 할 일
CREATE TABLE todos (
    id                      TEXT PRIMARY KEY,
    user_id                 INTEGER NOT NULL REFERENCES users(id),
    content                 TEXT NOT NULL,
    source                  TEXT,                            -- 'manual'|'call'|'notification'|'ai_suggestion'
    source_event_id         TEXT REFERENCES events(id),
    due_at                  TIMESTAMPTZ,
    related_person          TEXT,
    status                  TEXT DEFAULT 'open',             -- 'open'|'done'|'archived'
    completion_confidence   REAL DEFAULT 0.0,
    created_at              TIMESTAMPTZ DEFAULT NOW(),
    updated_at              TIMESTAMPTZ DEFAULT NOW(),
    completed_at            TIMESTAMPTZ
);

CREATE INDEX idx_todos_user_status ON todos(user_id, status);
CREATE INDEX idx_todos_due ON todos(due_at) WHERE due_at IS NOT NULL;

-- 약속
CREATE TABLE appointments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         INTEGER NOT NULL REFERENCES users(id),
    source_event_id TEXT REFERENCES events(id),
    title           TEXT NOT NULL,
    start_at        TIMESTAMPTZ NOT NULL,
    end_at          TIMESTAMPTZ,
    location        TEXT,
    with_person     TEXT,
    confidence      REAL DEFAULT 0.5,
    confirmed       BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_appointments_user_time ON appointments(user_id, start_at);

-- 처리 이력 (종류별 분리)
CREATE TABLE audio_processing_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audio_file_id UUID NOT NULL REFERENCES audio_files(id) ON DELETE CASCADE,
    stage        TEXT NOT NULL,                              -- 'whisper'
    status       TEXT NOT NULL,                              -- 'STARTED'|'OK'|'FAILED'
    message      TEXT,
    duration_ms  INTEGER,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE transcript_processing_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    stage         TEXT NOT NULL,                             -- 'claude'
    status        TEXT NOT NULL,                             -- 'STARTED'|'OK'|'FAILED'
    message       TEXT,
    duration_ms   INTEGER,
    prompt_version_id INTEGER REFERENCES prompt_versions(id),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
```

### 3.2 행 단위 권한 (RLS)

```sql
-- 사용자별 자기 데이터만 보이게
ALTER TABLE events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audio_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE transcripts ENABLE ROW LEVEL SECURITY;
ALTER TABLE summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE todos ENABLE ROW LEVEL SECURITY;
ALTER TABLE appointments ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;

-- 일반 사용자 정책: 자기 데이터만
CREATE POLICY user_own_events ON events
    FOR ALL TO assistant
    USING (
        current_setting('app.user_role', true) = 'admin'
        OR user_id = current_setting('app.user_id', true)::integer
    );
-- 위 정책을 모든 사용자 데이터 테이블에 동일하게 적용
```

## 4. 인증 API

### 4.1 POST /auth/login

요청: `{email, password}`
응답: `{access_token, refresh_token, user: {id, name, role}}`

흐름:
1. `users.email = ?` 조회
2. `bcrypt.checkpw(password, password_hash)` 검증
3. JWT 발급 (access_token: 1시간 만료, refresh_token: 90일 만료, 모두 사용자 ID + 역할 클레임 포함)
4. `devices` 등록 또는 갱신 (디바이스 이름 + 파이어베이스 토큰 + refresh_token 의 해시)

### 4.2 POST /auth/refresh

요청: `{refresh_token}`
응답: `{access_token}`

흐름:
1. refresh_token 검증 (JWT 서명 + 만료)
2. `devices.refresh_token_hash` 일치 + `revoked_at` IS NULL 확인
3. 새 access_token 발급

### 4.3 POST /auth/logout

요청: `{refresh_token}`
응답: `{ok: true}`

흐름: 해당 디바이스의 `refresh_token_hash` NULL + `revoked_at = NOW()`.

## 5. PostgREST 설정

```
db-uri = "postgres://assistant:${POSTGRES_PASSWORD}@postgres:5432/assistant"
db-schema = "public"
db-anon-role = "assistant"
jwt-secret = "${JWT_SECRET}"
db-pre-request = "set_app_context"  -- 사용자 ID / 역할을 세션 변수에 박는 함수
```

`set_app_context` 함수:
```sql
CREATE FUNCTION set_app_context() RETURNS void AS $$
BEGIN
    PERFORM set_config('app.user_id', current_setting('request.jwt.claims', true)::jsonb->>'user_id', true);
    PERFORM set_config('app.user_role', current_setting('request.jwt.claims', true)::jsonb->>'role', true);
END;
$$ LANGUAGE plpgsql;
```

## 6. 업로드 수신기

`POST /upload/audio` (multipart: file + event_id)

흐름:
1. JWT 검증 → 사용자 ID
2. `events` 에 type='call' 행 삽입 (없으면)
3. 디스크 저장 (`~/storage/jjongpal/audio/<user_id>/<YYYY-MM-DD>/<event_id>.m4a`)
4. `audio_files` 등록 (transcript_status='PENDING')

## 7. whisper 워커

폴링: `audio_files.transcript_status = 'PENDING'` LIMIT 5

흐름:
1. status 를 PROCESSING 으로 마킹
2. `whisper.cpp` 또는 `faster-whisper` 호출 (한국어, large-v3)
3. 결과를 `transcripts` 에 INSERT (summary_status='PENDING')
4. `audio_files.transcript_status` DONE, `processed_at` 현재 시각
5. **통화 음성 파일 즉시 삭제** (`os.remove(file_path)`) + `audio_files.file_path = NULL`
6. `audio_processing_log` OK 행 INSERT

실패 시: status='FAILED' + error_message + audio_processing_log FAILED

## 8. 에이전트 워커 (호스트 systemd)

폴링: `transcripts.summary_status = 'PENDING'` LIMIT 3

흐름:
1. 프롬프트 버전 (해시) 확인 → `prompt_versions` 에 없으면 INSERT
2. status 를 PROCESSING 으로 마킹
3. `claude --print --output-format json` 호출 + 프롬프트 + transcript
4. 응답 파싱 (`{summary, todos, appointments, people, action_items}`)
5. `summaries` INSERT (prompt_version_id 같이)
6. `todos` 분해 INSERT
7. `appointments` 분해 INSERT
8. `transcripts.summary_status` DONE
9. `transcript_processing_log` OK INSERT

호출 간격: 2초 (사용량 안전)

## 9. 프롬프트

`pc/agent-worker/prompts/call_summarize.md`:

```
당신은 통화 녹음 텍스트 변환 결과를 정리하는 비서입니다.

## 입력
한국어 통화 텍스트 (화자 분리 없음). 두 명 이상의 화자가 섞여 있을 수 있습니다.

## 출력
오직 JSON 만 출력하세요. 설명 / 마크다운 코드 블록 / 추가 텍스트 금지.

JSON 스키마:
{
  "summary": "한 문단 (2-4 문장) 요약. 누가 누구와 무엇을 이야기했는지.",
  "people": ["통화 참여자 이름 또는 역할"],
  "todos": [
    {"content": "할 일 (50자 이내)", "person": "관련 사람", "due_hint": "마감 힌트"}
  ],
  "appointments": [
    {"title": "...", "start_at": "ISO 8601", "end_at": "ISO 8601", "location": "...", "with": "...", "confidence": 0.0}
  ],
  "action_items": ["통화 후 본인이 즉시 해야 할 액션"]
}

## 규칙
1. 화자 추정 시 발화 내용으로 판단. 확실치 않으면 "상대방".
2. 시간 표현은 통화 시점 (오늘) 기준 절대 시각으로 변환. 모호하면 confidence 낮춤.
3. 시간 명시 있으면 appointment, 없으면 todo.
4. 잡담 / 안부 만 있는 통화는 todos / appointments 비움.
5. 절대 입력 텍스트를 출력에 포함하지 말 것.

## 텍스트
{{TRANSCRIPT}}
```

## 10. 파이어베이스 푸셔

폴링: `summaries.pushed = FALSE` LIMIT 10

흐름:
1. 요약의 사용자 ID 의 활성 디바이스 (`SELECT fcm_token FROM devices WHERE user_id = ? AND revoked_at IS NULL`)
2. 각 디바이스에 푸시 — 페이로드는 **데이터 전용 (data-only)**:
   ```json
   { "data": { "type": "summary_ready", "summary_id": "<UUID>" } }
   ```
   `notification` 필드 없음. 안드로이드 측에서 받아서 자기 화면에서 본문 가져옴.
3. `summaries.pushed = TRUE` + `pushed_at` 현재 시각

새 할 일 / 새 약속도 동일 패턴.

## 11. 클라우드플레어 터널

```
# ~/.cloudflared/config.yml
tunnel: jjongpal
credentials-file: /home/<user>/.cloudflared/<tunnel-id>.json
ingress:
  - hostname: jjongpal.<본인도메인>.com
    service: http://localhost:8080
  - service: http_status:404
```

본인 PC 에서 systemd 로 등록 + 부팅 시 자동 시작.

## 12. 운영 스크립트

`scripts/create-user.sh`:
```bash
#!/bin/bash
# 사용자 1명 생성
EMAIL=$1
NAME=$2
ROLE=${3:-user}   # 'admin' or 'user'
PASSWORD=$(openssl rand -base64 16)
PASSWORD_HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'$PASSWORD', bcrypt.gensalt()).decode())")
docker exec -i jjongpal-postgres psql -U assistant -d assistant -c \
  "INSERT INTO users (email, password_hash, name, role) VALUES ('$EMAIL', '$PASSWORD_HASH', '$NAME', '$ROLE');"
echo "User created. Login info to share:"
echo "  email:    $EMAIL"
echo "  password: $PASSWORD"
```

`scripts/revoke-device.sh`:
```bash
#!/bin/bash
DEVICE_ID=$1
docker exec -i jjongpal-postgres psql -U assistant -d assistant -c \
  "UPDATE devices SET refresh_token_hash = NULL, revoked_at = NOW() WHERE id = '$DEVICE_ID';"
```

기타: `reset-password.sh`, `backup-db.sh`, `wipe-user.sh` ([10_DECISIONS.md](10_DECISIONS.md) §17·운영 스크립트 절 참조).

## 13. 헬스체크

```bash
docker compose ps
sudo systemctl status jjongpal-agent
sudo systemctl status cloudflared

# 큐 길이
docker exec jjongpal-postgres psql -U assistant -d assistant -c \
  "SELECT transcript_status, count(*) FROM audio_files GROUP BY transcript_status;
   SELECT summary_status, count(*) FROM transcripts GROUP BY summary_status;
   SELECT pushed, count(*) FROM summaries GROUP BY pushed;"

# 최근 실패
docker exec jjongpal-postgres psql -U assistant -d assistant -c \
  "SELECT * FROM audio_processing_log WHERE status='FAILED' ORDER BY created_at DESC LIMIT 10;
   SELECT * FROM transcript_processing_log WHERE status='FAILED' ORDER BY created_at DESC LIMIT 10;"

# 외부 연결
curl https://jjongpal.<본인도메인>.com/health
```

## 14. 알려진 미해결 사항

- 클로드 코드의 `--print --output-format json` 실제 응답 스키마. 단계 A.2 에서 확인 후 응답 파싱 함수 고정.
- whisper.cpp 의 정확한 호출 형식 / 한국어 fine-tuned 모델 사용 여부.
- 파이어베이스 프로젝트 / 서비스 계정 키 발급.
- 본인 도메인 / 서브도메인 클라우드플레어 등록.
- GPU 사용 여부 (CPU 만이면 1분 통화 → 1~2분 처리, GPU 있으면 10초).
