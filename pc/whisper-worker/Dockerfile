FROM python:3.12-slim

# faster-whisper 가 CTranslate2 (변환 도구) 백엔드. ffmpeg 필요 (오디오 디코딩).
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY worker.py .

ENV PYTHONUNBUFFERED=1 \
    HF_HOME=/models/huggingface

CMD ["python", "worker.py"]
