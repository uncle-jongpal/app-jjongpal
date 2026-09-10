# 쫑팔이삼촌 통합 백엔드 (pc/backend) — 운영 노트

auth-service + upload-receiver + whisper-worker + fcm-pusher + PostgREST 를 하나의
FastAPI(ASGI) 앱으로 합친 것. **추가(additive)만** 했고 기존 서비스/컴포즈/엔진엑스/DB 는
건드리지 않았다. 이 디렉터리만으로 빌드/기동 가능.

## 파일 구조
```
pc/backend/
  Dockerfile            python:3.12-slim, 전 서비스 의존성 합집합, uvicorn --workers 1
  requirements.txt      버전 고정(기존 서비스 값)
  NOTES.md              이 문서
  app/
    __init__.py
    config.py           모든 환경 변수 + 상수
    db.py               공유 asyncpg 풀 + rls_connection() (보안 핵심)
    jwt_utils.py        JWT 발급/검증 (클레임 이름 동일)
    common.py           client_ip, log_activity (auth/upload 공용)
    auth.py             /auth/* 라우터 (verbatim 포팅)
    upload.py           /upload/* 라우터 (verbatim 포팅, moov 검증)
    rest.py             /rest/* 라우터 (PostgREST 대체, 6개 경로)
    workers.py          whisper 전달 루프 + fcm 푸시 루프
    main.py             앱 조립 + lifespan(풀/워커) + /health + /static
```

## 엔드포인트
- `GET  /health` → `ok` (평문)
- `GET  /auth/health`
- `POST /auth/login`   (비인증) {email, password, device_name, fcm_token?}
- `POST /auth/refresh` (비인증) {refresh_token}
- `POST /auth/logout`  {refresh_token}
- `GET  /upload/health`
- `POST /upload/audio` multipart(file, event_id, device_timestamp?, device_id?, duration_sec?) — `Authorization: Bearer <access>`
- `POST  /rest/events`             이벤트 배열 upsert (ON CONFLICT id)
- `GET   /rest/todos`              `?order=updated_at.desc&limit=200`
- `POST  /rest/todos`              할 일 배열 upsert (ON CONFLICT id)
- `PATCH /rest/todos?id=eq.<id>`   {status?, content?, due_at?, completed_at?, updated_at?}
- `GET   /rest/summaries`          `?order=created_at.desc&limit=100`
- `GET   /rest/appointments`       `?order=start_at.asc&start_at=gte.<iso>&limit=100`
- `PATCH /rest/devices?id=eq.<id>` {fcm_token?, last_seen?}
- `GET  /static/<file>` 정적 파일(APK 등)

> failed_items/retry RPC 는 오너 결정에 따라 **구현 안 함**.

PostgREST 쿼리 파라미터 파싱: `order=col.desc|asc`(테이블별 컬럼 화이트리스트),
`limit=N`(1~1000 클램프), `id=eq.<val>`, `start_at=gte.<iso>`.

## 보안 — 사용자 격리(RLS) 보존 방식 **(가장 중요)**
- 이 앱의 공유 풀은 `DATABASE_URL` 사용자(= 테이블 소유자 `jjongpal`)로 접속한다.
  PostgreSQL 에서 **테이블 소유자는 RLS 를 우회**한다. 그래서 `/rest/*` 를 소유자 그대로
  돌리면 03-rls.sql 격리가 안 걸려 **다른 사용자 데이터가 샌다.**
- PostgREST 가 쓰던 방식을 그대로 재현(`app/db.py` `rls_connection`):
  매 `/rest/*` 요청을 트랜잭션으로 감싸고
  1. `SET LOCAL ROLE "<pg_role>"` — JWT `role` 클레임의 PG 역할명. 소유자 탈출 → RLS 적용.
     역할명은 화이트리스트(`jjongpal_user`/`jjongpal_admin`)로만 허용(주입 방지).
  2. `set_config('request.jwt.claims', <원본 클레임 JSON>, true)`
  3. `SELECT public.set_app_context()` — 기존 함수 그대로 호출 → `app.user_id`/`app.user_role` 설정
  `SET LOCAL` / `set_config(..., is_local=true)` 는 트랜잭션 종료 시 자동 복원되므로
  커넥션이 풀로 반환돼도 다음 요청은 소유자 컨텍스트로 깨끗이 돌아간다.
- `/rest/*` SQL 에는 `user_id` 필터를 **넣지 않는다** — RLS 가 책임진다(이중 필터 아님).
- 참고(동작 충실성): 기존 `set_app_context()` 는 `app.user_role` 에 JWT `role`(=PG 역할명,
  `jjongpal_admin` 등)을 넣는다. 03-rls.sql 의 어드민 분기는 `current_user_role()='admin'`
  을 보는데 값이 `jjongpal_admin` 이라 **실제로는 매칭되지 않는다.** 즉 어드민도 자기 행만
  본다. 이 동작을 "고치지 않고" 그대로 재현했다(충실 포팅). `app_role` 클레임은 토큰에
  남아 있으나 RLS 경로에선 사용되지 않는다.
- auth/upload/workers 는 기존처럼 소유자 커넥션 + 코드에서 user_id 수동 처리(RLS 미경유).

## 백그라운드 워커 게이팅
- `RUN_WORKERS`(기본 true). `false`/`0`/`no`/`off` 면 whisper/fcm 루프를 **띄우지 않는다.**
  스테이징에서 운영 대기열을 이중 소비하지 않도록 끄는 용도.
- lifespan 에서 `asyncio.create_task` 로 두 루프 기동, 종료 시 cancel.
- whisper 루프는 `WHISPER_BACKEND_URL` 없으면 비활성(앱은 계속 뜸). fcm 루프는
  `FIREBASE_PROJECT_ID` 없으면 비활성. (기존엔 둘 다 미설정 시 프로세스 종료였으나,
  통합 앱은 전체를 죽이지 않고 해당 루프만 끈다 — 나머지 엔드포인트는 계속 서비스.)
- **단일 인스턴스 필수**: `uvicorn --workers 1`(Dockerfile CMD 에 박아둠). 2개 이상이면
  각 프로세스가 PENDING/pushed=FALSE 를 동시에 집어 이중 소비한다.

## 소비하는 환경 변수 (값은 여기 안 적음)
| 변수 | 기본값 | 용도 |
|---|---|---|
| `DATABASE_URL` | (필수) | asyncpg 풀. 소유자 `jjongpal` 로 접속 |
| `JWT_SECRET` | (필수) | HS256 서명/검증 |
| `ACCESS_TOKEN_TTL_SEC` | 3600 | access 만료 |
| `REFRESH_TOKEN_TTL_SEC` | 7776000(90일) | refresh 만료 |
| `STORAGE_ROOT` | `/storage` | 통화 파일 루트. `audio/<user_id>/<YYYY-MM-DD>/<event_id><ext>` |
| `STATIC_ROOT` | `/static` | `/static/*` 서빙 디렉터리 (APK 등) |
| `RUN_WORKERS` | true | 백그라운드 루프 on/off |
| `WHISPER_LANGUAGE` | ko | 받아쓰기 언어 |
| `WHISPER_BACKEND_URL` | (빈값) | 원격 GPU 받아쓰기 서버. 없으면 whisper 루프 off |
| `WHISPER_POLL_INTERVAL_SEC` | 5 | PENDING 폴링 주기 |
| `WHISPER_BATCH_SIZE` | 3 | 한 번에 집는 건수 |
| `WHISPER_TIMEOUT_SEC` | 600 | 원격 전송 타임아웃 |
| `WHISPER_BACKEND_BACKOFF_SEC` | 15 | 일시 오류 시 재시도 대기 |
| `FIREBASE_PROJECT_ID` | (빈값) | FCM v1 프로젝트. 없으면 fcm 루프 off |
| `FIREBASE_CREDENTIALS` | `/secrets/service-account.json` | 서비스 계정 키 마운트 경로 |
| `FCM_POLL_INTERVAL_SEC` | 5 | summaries 폴링 주기 |
| `FCM_BATCH_SIZE` | 10 | 한 번에 집는 요약 수 |
| `DB_POOL_MIN` / `DB_POOL_MAX` | 2 / 10 | 공유 풀 크기 |

### 바인딩 포트
- 컨테이너 내부: `0.0.0.0:8000` (Dockerfile EXPOSE 8000 / uvicorn).
- 호스트 바인딩/터널 연결은 **이번 범위 밖**(기존 docker-compose.yml·nginx.conf 를 건드리지
  않음). 통합 반영 단계에서 nginx 가 `/auth /upload /rest /static /health` 를
  `http://backend:8000` 으로 프록시하도록 바꾸거나, cloudflared 가 직접
  `127.0.0.1:<slot>`(포트 정책 슬롯 20025~20029, 현재 20026 은 nginx 운영 백엔드 사용 중)
  에 붙이면 된다. 구체 배선은 트론(운영)에게 위임 권장.

### /static 매핑
- nginx 는 `./nginx/static` 을 `/static/` 으로 alias 했다. 통합 앱은 `STATIC_ROOT`(기본
  `/static`) 를 `StaticFiles` 로 `/static` 에 마운트. APK MIME 은
  `application/vnd.android.package-archive` 로 등록해 브라우저가 설치기로 넘기게 함.
  컨테이너에 같은 정적 디렉터리를 `STATIC_ROOT` 로 마운트하면 동일 동작.

## 타임스탬프/직렬화 충실성
- GET(todos/summaries/appointments)은 `json_agg` 로 **PostgreSQL 이 직접 JSON 생성** →
  PostgREST 와 동일한 timestamptz ISO(+00:00) / jsonb 객체 직렬화를 재현(수동 포맷 안 함).
- POST/PATCH 의 ISO 문자열은 `$::timestamptz` 캐스팅으로 Postgres 가 파싱(앱이 보내는
  `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`, 'Z' 포함 모두 허용).
- events 의 `metadata_json` 은 `$::jsonb` 로 저장.

## 응답 코드
- POST /rest/events, POST /rest/todos → 201
- PATCH /rest/todos, PATCH /rest/devices → 204
- GET /rest/* → 200 + JSON 배열(빈 결과는 `[]`)
- 업로드/인증은 기존 응답 본문/코드 그대로.
(안드로이드는 GET 만 본문 파싱, POST/PATCH 는 `Response<Unit>` 의 2xx 여부만 확인 → 호환.)

## 열린 질문 / 가정
1. **`events` upsert 컬럼**: 안드로이드 EventDto(id, user_id, device_id, type,
   source_package, title, content, timestamp, device_timestamp, metadata_json)만 upsert.
   `processing_status` 등은 건드리지 않음(기본값/기존값 유지). ON CONFLICT 시 위 컬럼만 덮어씀.
   PostgREST 의 merge-duplicates 와 동일한 "보낸 컬럼 전체 덮어쓰기" 가정.
2. **`SET LOCAL ROLE` 권한**: 풀 접속 유저(`jjongpal`)가 `jjongpal_user/admin` 으로 SET ROLE
   할 권한(멤버십)이 있어야 한다. PostgREST 가 같은 DB_URI 로 이 전환을 해왔으므로 이미
   부여돼 있다고 가정. (미부여면 SET ROLE 에서 오류 — 배포 전 실 DB 로 스모크 테스트 권장.)
3. **todos POST 의 `created_at`**: 앱이 항상 created_at 을 보냄(TodoDto) → INSERT 에 사용,
   충돌 시 created_at 은 갱신 안 함(원본 보존). priority/completion_confidence 중 priority 는
   DTO 에 없어 DB 기본값 사용.
4. **appointments gte 필터**: `start_at=gte.<iso>` 만 지원(앱 계약). 다른 연산자 미지원.
5. **리프레시 동시성/회전**: auth 로직은 verbatim — refresh_token 해시 비교/회전 그대로.
6. **포트/터널/compose/nginx 배선**: 미변경(범위 밖). NOTES 의 바인딩 포트 항목 참고.
7. **워커 미설정 시 정책 차이**: 기존 개별 서비스는 URL/프로젝트ID 없으면 프로세스 종료였으나,
   통합 앱은 해당 루프만 비활성(나머지 API 는 유지). 의도된 완화 — 필요시 강한 종료로 바꿀 수 있음.
```
