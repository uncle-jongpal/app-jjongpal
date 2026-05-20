#!/usr/bin/env bash
# 쫑팔이삼촌 — 에이전트 워커 설치 (호스트 systemd)
#
# 동작:
#  1. 가상환경 생성 + 의존성 설치
#  2. .env 파일 안내 (수동 생성 필요)
#  3. systemd 단위 파일을 jjongpal-agent@<사용자>.service 로 활성화

set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -d .venv ]]; then
    echo "[1/3] 파이썬 가상환경 생성"
    python3 -m venv .venv
fi

echo "[2/3] 의존성 설치"
.venv/bin/pip install -q -r requirements.txt

if [[ ! -f .env ]]; then
    cat > .env <<'EOF'
# 에이전트 워커 환경변수 — 컴포즈의 디비를 호스트 노출 포트로 접근
DATABASE_URL=postgres://jjongpal:REPLACE_ME@127.0.0.1:5432/jjongpal
PROMPT_DIR=/home/REPLACE_USER/work/dev/jjongpal-app/pc/agent-worker/prompts
CLAUDE_BIN=claude
AGENT_POLL_INTERVAL_SEC=10
AGENT_BATCH_SIZE=3
AGENT_CALL_GAP_SEC=2.0
CLAUDE_TIMEOUT_SEC=300
EOF
    echo ".env 템플릿 생성됨. 직접 편집하세요: $(pwd)/.env"
fi

# 클로드 코드 명령 위치 확인
if ! command -v claude >/dev/null 2>&1; then
    echo "경고: 'claude' 명령을 PATH 에서 못 찾음. nvm 경로 (~/.nvm/versions/node/<version>/bin) 가 systemd 단위 PATH 에 잡혀있는지 확인."
fi

# systemd 등록 (사용자가 'sudo' 권한 있어야 함)
USER_NAME="${USER:-$(whoami)}"
SERVICE_INSTANCE="jjongpal-agent@${USER_NAME}.service"
SOURCE_UNIT="$(pwd)/jjongpal-agent.service"
TARGET_UNIT="/etc/systemd/system/jjongpal-agent@.service"

echo "[3/3] systemd 등록"
echo "  sudo cp $SOURCE_UNIT $TARGET_UNIT"
echo "  sudo systemctl daemon-reload"
echo "  sudo systemctl enable $SERVICE_INSTANCE"
echo "  sudo systemctl start  $SERVICE_INSTANCE"
echo "  sudo systemctl status $SERVICE_INSTANCE"
echo ""
echo "설치 완료. 위 sudo 명령은 본인이 직접 실행."
