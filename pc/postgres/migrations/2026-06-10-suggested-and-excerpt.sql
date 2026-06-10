-- 리디자인 1단계 — 라이브 디비 마이그레이션 (멱등)
-- 적용 대상: 이미 가동 중인 운영 디비 (init/01-schema.sql 은 신규 init 에만 반영됨)
-- 내용:
--   1) 할 일 상태에 'suggested'(삼촌이 찾음·확인 대기), 'dismissed'(사용자가 넘김) 추가
--   2) 할 일·약속에 source_excerpt(근거 발췌 한 구절) 컬럼 추가
-- 안전성: 기존 행 영향 없음. 컬럼 추가는 NULL 허용, 제약은 기존 값 모두 포함.

BEGIN;

-- 1) 할 일 상태 제약 확장
ALTER TABLE todos DROP CONSTRAINT IF EXISTS todos_status_chk;
ALTER TABLE todos ADD CONSTRAINT todos_status_chk
    CHECK (status IN ('open', 'done', 'archived', 'suggested', 'dismissed'));

-- 2) 근거 발췌 컬럼
ALTER TABLE todos        ADD COLUMN IF NOT EXISTS source_excerpt TEXT;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS source_excerpt TEXT;

COMMIT;
