FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY app /app/app

# 상태 파일 기본 경로(볼륨 마운트 권장) / FCM 비밀키 마운트 경로
#   - 상태: WATCHDOG_STATE_FILE (기본 /data/state.json)
#   - FCM : FIREBASE_CREDENTIALS (기본 /secrets/service-account.json)
VOLUME ["/data"]

CMD ["python", "-m", "app.main"]
