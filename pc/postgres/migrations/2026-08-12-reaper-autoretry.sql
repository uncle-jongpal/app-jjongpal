-- 2026-08-12  리퍼 확장: 일시적 실패(FAILED) 자동 재시도 추가
--
-- 기존 reap_stuck_processing() 는 "멈춘 PROCESSING"만 되살렸다.
-- 여기에 "일시적 실패로 FAILED 된 항목"의 자동 재시도를 더한다:
--   받아쓰기: FAILED + 파일있음 + attempt_count<3 + 실패 후 30분 경과 → PENDING(attempt++)
--   요약:     FAILED + attempt_count<3 + 실패 후 30분 경과 → PENDING(attempt++)
-- 상한(3회) 넘으면 그대로 FAILED 로 두어 사용자 수동 확인(무한 재시도 방지).
-- (2026-08-12-stuck-processing-reaper.sql 이후 적용)

CREATE OR REPLACE FUNCTION public.reap_stuck_processing()
RETURNS TABLE(scope TEXT, requeued INT, failed INT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $fn$
DECLARE rq INT; fl INT; rr INT;
BEGIN
  -- (A) 받아쓰기 멈춤(120분) 회수 / 상한초과 실패확정
  UPDATE audio_files SET transcript_status='FAILED', error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE transcript_status='PROCESSING' AND processing_at < now()-interval '120 minutes' AND attempt_count>=3;
  GET DIAGNOSTICS fl=ROW_COUNT;
  UPDATE audio_files SET transcript_status='PENDING', attempt_count=attempt_count+1, processing_at=NULL
   WHERE transcript_status='PROCESSING' AND processing_at < now()-interval '120 minutes' AND attempt_count<3;
  GET DIAGNOSTICS rq=ROW_COUNT;
  -- (A') 받아쓰기 일시적 실패 자동 재시도
  UPDATE audio_files SET transcript_status='PENDING', attempt_count=attempt_count+1, error_message=NULL
   WHERE transcript_status='FAILED' AND file_path IS NOT NULL AND attempt_count<3
     AND processed_at < now()-interval '30 minutes';
  GET DIAGNOSTICS rr=ROW_COUNT;
  scope:='transcript'; requeued:=rq+rr; failed:=fl; RETURN NEXT;

  -- (B) 요약 멈춤(15분) 회수 / 상한초과 실패확정
  UPDATE transcripts SET summary_status='FAILED', error_message='stuck in PROCESSING (reaped)', processed_at=now()
   WHERE summary_status='PROCESSING' AND processing_at < now()-interval '15 minutes' AND attempt_count>=3;
  GET DIAGNOSTICS fl=ROW_COUNT;
  UPDATE transcripts SET summary_status='PENDING', attempt_count=attempt_count+1, processing_at=NULL
   WHERE summary_status='PROCESSING' AND processing_at < now()-interval '15 minutes' AND attempt_count<3;
  GET DIAGNOSTICS rq=ROW_COUNT;
  -- (B') 요약 일시적 실패 자동 재시도
  UPDATE transcripts SET summary_status='PENDING', attempt_count=attempt_count+1, error_message=NULL
   WHERE summary_status='FAILED' AND attempt_count<3 AND processed_at < now()-interval '30 minutes';
  GET DIAGNOSTICS rr=ROW_COUNT;
  scope:='summary'; requeued:=rq+rr; failed:=fl; RETURN NEXT;
END $fn$;

GRANT EXECUTE ON FUNCTION public.reap_stuck_processing() TO jjongpal, jjongpal_admin;
