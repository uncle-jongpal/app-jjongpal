-- 07-live-additions.sql
-- 운영 DB에만 있던(저장소 마이그레이션에 빠졌던) 정의를 2026-09-10 재현성 위해 기록.
-- 자비스가 live DB에서 pg_get_functiondef / information_schema 로 덤프. (이 파일 적용은 멱등)
-- 주의: 컬럼 DEFAULT는 best-effort(실DB에서 확인된 타입 기준). 이미 존재하면 ADD는 무시됨.

-- ── reaper/파이프라인 재시도용 컬럼 (audio_files, transcripts) ──
ALTER TABLE audio_files  ADD COLUMN IF NOT EXISTS attempt_count  integer DEFAULT 0;
ALTER TABLE audio_files  ADD COLUMN IF NOT EXISTS error_message  text;
ALTER TABLE audio_files  ADD COLUMN IF NOT EXISTS processing_at  timestamptz;
ALTER TABLE audio_files  ADD COLUMN IF NOT EXISTS processed_at   timestamptz;
ALTER TABLE transcripts  ADD COLUMN IF NOT EXISTS attempt_count  integer DEFAULT 0;
ALTER TABLE transcripts  ADD COLUMN IF NOT EXISTS retry_count    integer DEFAULT 0;
ALTER TABLE transcripts  ADD COLUMN IF NOT EXISTS error_message  text;
ALTER TABLE transcripts  ADD COLUMN IF NOT EXISTS processing_at  timestamptz;
ALTER TABLE transcripts  ADD COLUMN IF NOT EXISTS processed_at   timestamptz;

-- ── 멈춘 작업 회수 함수 (reaper / .jjongpal-reaper.sh 와 watchdog reaper 체크가 호출) ──
-- 받아쓰기: PROCESSING 120분 초과 → 시도<3 재큐 / 시도>=3 실패확정, FAILED+파일+시도<3+30분 → 재큐
-- 요약:   PROCESSING 15분 초과 → 동일, FAILED+시도<3+30분 → 재큐
CREATE OR REPLACE FUNCTION public.reap_stuck_processing()
 RETURNS TABLE(scope text, requeued integer, failed integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE rq INT; fl INT; rr INT;
BEGIN
  UPDATE audio_files SET transcript_status='FAILED', error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE transcript_status='PROCESSING' AND processing_at < now()-interval '120 minutes' AND attempt_count>=3;
  GET DIAGNOSTICS fl=ROW_COUNT;
  UPDATE audio_files SET transcript_status='PENDING', attempt_count=attempt_count+1, processing_at=NULL
   WHERE transcript_status='PROCESSING' AND processing_at < now()-interval '120 minutes' AND attempt_count<3;
  GET DIAGNOSTICS rq=ROW_COUNT;
  UPDATE audio_files SET transcript_status='PENDING', attempt_count=attempt_count+1, error_message=NULL
   WHERE transcript_status='FAILED' AND file_path IS NOT NULL AND attempt_count<3
     AND processed_at < now()-interval '30 minutes';
  GET DIAGNOSTICS rr=ROW_COUNT;
  scope:='transcript'; requeued:=rq+rr; failed:=fl; RETURN NEXT;

  UPDATE transcripts SET summary_status='FAILED', error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE summary_status='PROCESSING' AND processing_at < now()-interval '15 minutes' AND attempt_count>=3;
  GET DIAGNOSTICS fl=ROW_COUNT;
  UPDATE transcripts SET summary_status='PENDING', attempt_count=attempt_count+1, processing_at=NULL
   WHERE summary_status='PROCESSING' AND processing_at < now()-interval '15 minutes' AND attempt_count<3;
  GET DIAGNOSTICS rq=ROW_COUNT;
  UPDATE transcripts SET summary_status='PENDING', attempt_count=attempt_count+1, error_message=NULL
   WHERE summary_status='FAILED' AND attempt_count<3 AND processed_at < now()-interval '30 minutes';
  GET DIAGNOSTICS rr=ROW_COUNT;
  scope:='summary'; requeued:=rq+rr; failed:=fl; RETURN NEXT;
END $function$;
