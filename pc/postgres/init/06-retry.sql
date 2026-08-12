-- 처리 실패 항목 조회 + 수동 재시도
-- 앱이 "서버에서 막힌 통화/알림"을 보고, 사용자가 버튼으로 재시도 트리거.
-- 규칙: FAILED 상태만 PENDING 으로 되돌림. PROCESSING(진행 중)은 절대 건드리지 않음.

-- ===== 실패 항목 뷰 =====
-- security_invoker=true → 호출자 역할의 RLS(행 격리)가 그대로 적용되어 본인 데이터만 노출.
CREATE OR REPLACE VIEW public.failed_items
WITH (security_invoker = true) AS
    -- 통화 받아쓰기 실패 (음성 파일이 아직 서버에 남아있는 경우만 — 재처리 가능)
    SELECT
        e.id                                         AS event_id,
        e.type                                       AS type,
        'transcript'::text                           AS stage,
        af.error_message                             AS error_message,
        COALESCE(NULLIF(e.title, ''), e.content)     AS label,
        COALESCE(e.device_timestamp, e.timestamp)    AS occurred_at,
        af.processed_at                              AS failed_at
    FROM events e
    JOIN audio_files af ON af.event_id = e.id
    WHERE af.transcript_status = 'FAILED' AND af.file_path IS NOT NULL
  UNION ALL
    -- 통화 정리(요약) 실패 (받아쓰기 텍스트는 있음 → 음성 없이도 재처리 가능)
    SELECT
        e.id,
        e.type,
        'summary'::text,
        t.error_message,
        COALESCE(NULLIF(e.title, ''), e.content),
        COALESCE(e.device_timestamp, e.timestamp),
        t.processed_at
    FROM events e
    JOIN transcripts t ON t.event_id = e.id
    WHERE t.summary_status = 'FAILED'
  UNION ALL
    -- 알림 분류 실패
    SELECT
        e.id,
        e.type,
        'event'::text,
        e.processing_error,
        COALESCE(NULLIF(e.title, ''), e.content),
        COALESCE(e.device_timestamp, e.timestamp),
        e.processed_at
    FROM events e
    WHERE e.type = 'notification' AND e.processing_status = 'FAILED';

GRANT SELECT ON public.failed_items TO jjongpal_user, jjongpal_admin;

-- ===== 수동 재시도 함수 =====
-- SECURITY DEFINER 라 RLS 를 우회하므로, 내부에서 current_user_id() 로 소유권을 직접 확인.
-- FAILED 행만 PENDING 으로 되돌림 (WHERE 절에 FAILED 고정) → PROCESSING 은 자동 보존.
CREATE OR REPLACE FUNCTION public.retry_failed_item(p_event_id TEXT)
RETURNS TABLE(stage TEXT, reset_count INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    uid INTEGER := current_user_id();
    n   INTEGER;
BEGIN
    IF uid IS NULL THEN
        RAISE EXCEPTION 'unauthenticated';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM events WHERE id = p_event_id AND user_id = uid) THEN
        RAISE EXCEPTION 'event not found or not owned';
    END IF;

    -- 받아쓰기 재시도 (음성 파일이 남아있어야 워커가 다시 집어감)
    UPDATE audio_files
       SET transcript_status = 'PENDING', error_message = NULL, processed_at = NULL
     WHERE event_id = p_event_id AND user_id = uid
       AND transcript_status = 'FAILED' AND file_path IS NOT NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN stage := 'transcript'; reset_count := n; RETURN NEXT; END IF;

    -- 정리(요약) 재시도
    UPDATE transcripts
       SET summary_status = 'PENDING', error_message = NULL, processed_at = NULL
     WHERE event_id = p_event_id AND user_id = uid
       AND summary_status = 'FAILED';
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN stage := 'summary'; reset_count := n; RETURN NEXT; END IF;

    -- 알림 분류 재시도
    UPDATE events
       SET processing_status = 'PENDING', processing_error = NULL, processed_at = NULL
     WHERE id = p_event_id AND user_id = uid
       AND type = 'notification' AND processing_status = 'FAILED';
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN stage := 'event'; reset_count := n; RETURN NEXT; END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION public.retry_failed_item(TEXT) TO jjongpal_user, jjongpal_admin;

SELECT 'retry schema added' AS status;
