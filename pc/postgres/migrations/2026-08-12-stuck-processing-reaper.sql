-- 2026-08-12  신뢰성: "처리 중(PROCESSING)에 갇혀 사라지는 통화" 되살리기
--
-- 배경(코드 검수 발견):
--   워커가 audio_files/transcripts 를 PROCESSING 으로 찍은 직후 크래시/재시작되면,
--   그 행은 PENDING 이 아니라 PROCESSING 에 갇혀 다시는 폴링되지 않는다.
--   (폴링은 PENDING 만, 수동 retry_failed_item 은 FAILED 만 되돌림)
--   → 누락 방지가 생명선인 시스템의 가장 큰 구멍.
--
-- 해법(워커 코드/컨테이너 무수정, DB + 크론만):
--   1) processing_at / attempt_count 컬럼
--   2) 상태가 PROCESSING 으로 바뀌는 순간 processing_at 자동 기록 트리거 (테이블별 분리 함수)
--   3) 오래 갇힌 PROCESSING 을 PENDING 으로 되살리는 함수(시도 상한 넘으면 FAILED)
--      호출: 운영서버 크론 5분 주기 → SELECT public.reap_stuck_processing()
--      (/home/weplay/.jjongpal-reaper.sh, docker 일회성 psql, 배포 .env 사용)
--
-- 타임아웃: 받아쓰기 120분(느린 워커·긴 통화 대비 — 실측 30분 통화 처리에 22분 소요),
--           요약 15분(Claude 300s 하드타임아웃 한참 초과).
-- 트리거 주의: audio_files/transcripts 는 컬럼명이 달라(transcript_status vs summary_status)
--   단일 함수로 두면 ELSIF 가 없는 컬럼을 참조해 오류난다 → 반드시 테이블별 분리 함수.
-- 되돌리기: DROP FUNCTION reap_stuck_processing; DROP TRIGGER trg_*; (컬럼은 남겨도 무해)

BEGIN;

ALTER TABLE audio_files ADD COLUMN IF NOT EXISTS processing_at TIMESTAMPTZ;
ALTER TABLE audio_files ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE transcripts ADD COLUMN IF NOT EXISTS processing_at TIMESTAMPTZ;
ALTER TABLE transcripts ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;

-- 이미 PROCESSING 이던 기존 행은 now() 로 시작시각 부여(업로드시각으로 backfill 하면
-- 방금 처리 시작한 행을 "오래 갇힘"으로 오판하므로 절대 금지 — now() 로 한 텀 유예).
UPDATE audio_files SET processing_at = now() WHERE transcript_status = 'PROCESSING';
UPDATE transcripts SET processing_at = now() WHERE summary_status   = 'PROCESSING';

-- PROCESSING 진입 시각 자동 기록 (테이블별 분리 함수)
CREATE OR REPLACE FUNCTION public.mark_audio_processing_at() RETURNS trigger
LANGUAGE plpgsql AS $fn$
BEGIN
  IF NEW.transcript_status = 'PROCESSING'
     AND NEW.transcript_status IS DISTINCT FROM OLD.transcript_status THEN
    NEW.processing_at := now();
  END IF;
  RETURN NEW;
END $fn$;

CREATE OR REPLACE FUNCTION public.mark_transcript_processing_at() RETURNS trigger
LANGUAGE plpgsql AS $fn$
BEGIN
  IF NEW.summary_status = 'PROCESSING'
     AND NEW.summary_status IS DISTINCT FROM OLD.summary_status THEN
    NEW.processing_at := now();
  END IF;
  RETURN NEW;
END $fn$;

DROP TRIGGER IF EXISTS trg_audio_processing_at ON audio_files;
CREATE TRIGGER trg_audio_processing_at BEFORE UPDATE ON audio_files
  FOR EACH ROW EXECUTE FUNCTION public.mark_audio_processing_at();

DROP TRIGGER IF EXISTS trg_transcript_processing_at ON transcripts;
CREATE TRIGGER trg_transcript_processing_at BEFORE UPDATE ON transcripts
  FOR EACH ROW EXECUTE FUNCTION public.mark_transcript_processing_at();

-- 멈춘 PROCESSING 되살리기 (SECURITY DEFINER: 소유자 권한으로 실행)
CREATE OR REPLACE FUNCTION public.reap_stuck_processing()
RETURNS TABLE(scope TEXT, requeued INT, failed INT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE rq INT; fl INT;
BEGIN
  -- 받아쓰기: 120분 초과
  UPDATE audio_files SET transcript_status='FAILED',
         error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE transcript_status='PROCESSING'
     AND processing_at < now() - interval '120 minutes' AND attempt_count >= 3;
  GET DIAGNOSTICS fl = ROW_COUNT;
  UPDATE audio_files SET transcript_status='PENDING',
         attempt_count=attempt_count+1, processing_at=NULL
   WHERE transcript_status='PROCESSING'
     AND processing_at < now() - interval '120 minutes' AND attempt_count < 3;
  GET DIAGNOSTICS rq = ROW_COUNT;
  scope:='transcript'; requeued:=rq; failed:=fl; RETURN NEXT;

  -- 요약: 15분 초과
  UPDATE transcripts SET summary_status='FAILED',
         error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE summary_status='PROCESSING'
     AND processing_at < now() - interval '15 minutes' AND attempt_count >= 3;
  GET DIAGNOSTICS fl = ROW_COUNT;
  UPDATE transcripts SET summary_status='PENDING',
         attempt_count=attempt_count+1, processing_at=NULL
   WHERE summary_status='PROCESSING'
     AND processing_at < now() - interval '15 minutes' AND attempt_count < 3;
  GET DIAGNOSTICS rq = ROW_COUNT;
  scope:='summary'; requeued:=rq; failed:=fl; RETURN NEXT;
END $fn$;

GRANT EXECUTE ON FUNCTION public.reap_stuck_processing() TO jjongpal, jjongpal_admin;

COMMIT;
