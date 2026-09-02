FROM python:3.12-slim

# 전달 전용 워커: 위스퍼 모델을 직접 돌리지 않으므로 ffmpeg/faster-whisper 불필요.
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY worker.py .

ENV PYTHONUNBUFFERED=1

CMD ["python", "worker.py"]
