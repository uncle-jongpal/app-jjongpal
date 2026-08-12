-- 리디자인 2단계 — 할 일 선별·보류함 (멱등)
-- 적용 대상: 이미 가동 중인 운영 디비 (init/01-schema.sql 은 신규 init 에만 반영됨)
-- 내용:
--   1) 할 일 상태에 'parked'(보류 — 사용자가 나중에 보려고 미뤄둠) 추가
--   2) 할 일에 priority(중요도 점수 0~1) 컬럼 추가 — 비서가 매긴 점수, 검토 목록 정렬용
-- 안전성: 기존 행 영향 없음. 컬럼 추가는 기본값 0, 제약은 기존 값 모두 포함.

BEGIN;

-- 1) 할 일 상태 제약 확장 ('parked' 추가)
ALTER TABLE todos DROP CONSTRAINT IF EXISTS todos_status_chk;
ALTER TABLE todos ADD CONSTRAINT todos_status_chk
    CHECK (status IN ('open', 'done', 'archived', 'suggested', 'dismissed', 'parked'));

-- 2) 중요도 점수 컬럼
ALTER TABLE todos ADD COLUMN IF NOT EXISTS priority REAL NOT NULL DEFAULT 0.0;

COMMIT;
