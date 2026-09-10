FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV PYTHONUNBUFFERED=1

EXPOSE 8000

# *** 단일 인스턴스 필수: --workers 1 ***
# 워커 프로세스가 2개 이상이면 whisper/fcm 폴링 루프가 대기열을 이중 소비한다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
