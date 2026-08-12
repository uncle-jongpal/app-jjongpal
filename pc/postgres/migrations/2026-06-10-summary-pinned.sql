-- 리디자인 3단계 — 통화 정리 보관함(핀) (멱등)
-- 적용 대상: 이미 가동 중인 운영 디비 (init/01-schema.sql 은 신규 init 에만 반영됨)
-- 내용:
--   1) summaries 에 pinned(보관함에 담음) 컬럼 추가 — 사용자가 핀 꽂은 통화 정리
-- 안전성: 기존 행 영향 없음. 기본값 FALSE.

BEGIN;

ALTER TABLE summaries ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT FALSE;

COMMIT;
