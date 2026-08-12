-- 2026-08-12  신뢰성: 업로드 멱등성 하드 가드 — 한 통화(event_id)당 audio_files 하나
--
-- 배경: upload-receiver 가 재업로드 시 audio_files 를 중복 INSERT 하던 것을
--   (a) 코드에서 "없을 때만 INSERT"(WHERE NOT EXISTS) 로 막고
--   (b) DB 레벨 유니크 인덱스로 이중 방어.
-- 주의: 인덱스 추가 전 기존 중복 오디오/요약을 먼저 정리해야 함(정리 후 적용).
--   정리 규칙: 통화별로 가장 완전한(요약 있는·고정된·가장 이른) 행 1개만 남기고 삭제.
-- 되돌리기: DROP INDEX uq_audio_files_event_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_audio_files_event_id ON audio_files(event_id);
