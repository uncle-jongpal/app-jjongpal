# PC 백엔드 (도커 컴포즈)

쫑팔이삼촌 — PC 측 시스템.

## 구성

- **postgres** — 데이터 저장 (디비)
- **postgrest** — 폰 ↔ PC 자동 REST 입구 (행 단위 권한 적용)
- **auth-service** — 로그인 / 토큰 재발급 / 로그아웃 (FastAPI)
- **upload-receiver** — 통화 파일 받기 (FastAPI)
- **whisper-worker** — 음성 → 텍스트 (faster-whisper, 한국어, large-v3). 처리 후 통화 음성 파일 즉시 삭제.
- **fcm-pusher** — 새 요약 생기면 파이어베이스 푸시 (트리거만, 본문 X)
- **nginx** — 외부 입구 라우팅
- **에이전트 워커** (`agent-worker/`) — 호스트 systemd 로 실행. 클로드 코드 헤드리스 호출.
- **클라우드플레어 터널** (`cloudflared/`) — 외부 통로

## 설치 / 가동 (요약)

### 0. 사전 준비

- 본인 PC 에 도커 + 도커 컴포즈 설치됨
- 클로드 코드 CLI 본인 계정으로 로그인됨 (`claude login`)
- 클라우드플레어 도메인 등록 + 서브도메인 결정
- 파이어베이스 프로젝트 + 서비스 계정 키 발급 → `pc/fcm-pusher/service-account.json` 으로 저장

### 1. 환경변수

```bash
cd pc/
cp .env.example .env
# 안의 값들 본인 환경으로 수정
#  - POSTGRES_PASSWORD: 랜덤 32자
#  - JWT_SECRET: 랜덤 64자 (openssl rand -hex 32 등)
#  - PUBLIC_BASE_URL: 본인 서브도메인 (예: https://samchon.uncle-jongpal.com)
#  - STORAGE_ROOT: 통화 파일 저장 디렉토리 (예: $HOME/storage/jjongpal)
#  - FIREBASE_PROJECT_ID: 파이어베이스 프로젝트 ID
```

### 2. whisper 모델 준비

처음 가동 시 faster-whisper 가 자동으로 large-v3 모델 (~3GB) 을 다운로드. `pc/whisper-worker/models/` 로 떨어짐. 미리 받아두려면:

```bash
cd pc/whisper-worker
mkdir -p models
docker run --rm -v $(pwd)/models:/models python:3.12-slim \
    sh -c "pip install -q huggingface-hub && \
           python -c \"from huggingface_hub import snapshot_download; \
                       snapshot_download('Systran/faster-whisper-large-v3', local_dir='/models/Systran--faster-whisper-large-v3')\""
```

### 3. 컴포즈 가동

```bash
docker compose up -d
docker compose ps
docker compose logs -f
```

처음 가동 시 `postgres/init/*.sql` 이 자동 실행되어 스키마 / 행 단위 권한 정책 / 함수 셋업됨.

### 4. 첫 사용자 (어드민) 생성

```bash
cd ..  # jjongpal-app/
./scripts/create-user.sh "your-email@example.com" "김정석" admin
# → 출력에 이메일 / 임시 비밀번호 표시. 본인 폰에서 로그인.
```

### 5. 에이전트 워커 (호스트 systemd)

```bash
cd pc/agent-worker/
./install.sh
# 안내 따라 sudo 명령 실행
```

### 6. 클라우드플레어 터널

`pc/cloudflared/README.md` 참조.

### 7. 외부 헬스 확인

```bash
curl https://<본인 서브도메인>/health
# "ok" 떠야 정상
```

## 검증 시나리오 (전체 흐름)

```bash
# 1) 로그인 → 액세스 토큰
TOKEN=$(curl -s https://<도메인>/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<email>","password":"<password>","device_name":"my-galaxy-s24"}' \
  | jq -r .access_token)

# 2) 더미 통화 파일 업로드 (작은 m4a 한 개 준비)
curl -X POST https://<도메인>/upload/audio \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./test.m4a" \
  -F "event_id=test-$(date +%s)"

# 3) 디비 상태 확인
docker compose exec postgres psql -U jjongpal -d jjongpal -c \
  "SELECT id, transcript_status FROM audio_files ORDER BY uploaded_at DESC LIMIT 5;
   SELECT id, summary_status, length(text) FROM transcripts ORDER BY created_at DESC LIMIT 5;
   SELECT id, length(summary), pushed FROM summaries ORDER BY created_at DESC LIMIT 5;"

# 4) 처리 이력
docker compose exec postgres psql -U jjongpal -d jjongpal -c \
  "SELECT * FROM audio_processing_log ORDER BY created_at DESC LIMIT 5;
   SELECT * FROM transcript_processing_log ORDER BY created_at DESC LIMIT 5;"
```

성공 시: 1~2분 안에 PENDING → PROCESSING → DONE 흐름 + summaries 생성 + 파이어베이스 푸시 전송 (디바이스 토큰이 있을 때).

## 운영 명령

```bash
# 컨테이너 상태
docker compose ps

# 로그
docker compose logs -f --tail 200

# 에이전트 워커 (호스트)
sudo systemctl status jjongpal-agent@$USER
sudo journalctl -u jjongpal-agent@$USER -f

# 큐 길이
docker compose exec postgres psql -U jjongpal -d jjongpal -c \
  "SELECT transcript_status, count(*) FROM audio_files GROUP BY 1;
   SELECT summary_status, count(*) FROM transcripts GROUP BY 1;
   SELECT pushed, count(*) FROM summaries GROUP BY 1;"

# 최근 실패
docker compose exec postgres psql -U jjongpal -d jjongpal -c \
  "SELECT * FROM audio_processing_log WHERE status='FAILED' ORDER BY created_at DESC LIMIT 10;
   SELECT * FROM transcript_processing_log WHERE status='FAILED' ORDER BY created_at DESC LIMIT 10;"

# 백업
../scripts/backup-db.sh
```

## 문제 발생 시

- 컨테이너 안 뜸 → `docker compose logs <서비스>`
- 디비 초기 셋업 실패 → `postgres/data/` 디렉토리 삭제하고 다시 `docker compose up -d`
- whisper 가 너무 느림 → `.env` 의 `WHISPER_DEVICE=cuda` (GPU 있으면) 또는 `WHISPER_COMPUTE_TYPE=int8` 유지
- 에이전트 워커가 클로드 코드 못 찾음 → `jjongpal-agent.service` 의 `PATH` 에 nvm 의 node 경로 잡혀있는지 확인
- 파이어베이스 푸시 안 됨 → `fcm-pusher/service-account.json` 존재 여부, `FIREBASE_PROJECT_ID` 일치 확인
