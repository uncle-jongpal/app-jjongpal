-- 2026-08-12  보안 잠금: PostgREST 로 과도하게 노출된 민감 데이터 차단
--
-- 배경(코드 검수 발견):
--   * users 테이블에 SELECT 권한이 열려 있어, 앱 역할(jjongpal_user/admin)이
--     자기(또는 admin은 전원) password_hash 를 /rest/users 로 조회 가능했다.
--     → 앱은 /rest/users 를 전혀 사용하지 않으므로 조회 권한을 통째로 회수.
--   * devices 테이블에 SELECT/INSERT/UPDATE/DELETE 전권이 열려 있어,
--     앱 역할이 refresh_token_hash(리프레시 토큰 해시)와 fcm_token 을 조회하고
--     행을 임의 수정/삭제/삽입할 수 있었다.
--     → 앱이 실제 필요로 하는 것은 fcm_token / last_seen 갱신뿐이므로
--       refresh_token_hash 를 제외한 안전 컬럼 SELECT + (fcm_token,last_seen) UPDATE 로 축소.
--
-- 영향 없음 확인:
--   * auth-service / upload-receiver / fcm-pusher 는 슈퍼유저(jongpal)로 직접 접속 →
--     이 GRANT/REVOKE(앱 역할 대상)의 영향을 받지 않는다.
--   * 앱(PcApi)은 users 미사용, devices 는 PATCH(fcm_token,last_seen)만 사용.
--   * FK(devices.user_id→users.id) 무결성 검사는 시스템 트리거가 수행하므로
--     users SELECT 회수의 영향을 받지 않는다.
--
-- 되돌리기(문제 시):
--   GRANT SELECT ON users TO jjongpal_user, jjongpal_admin;
--   GRANT SELECT, INSERT, UPDATE, DELETE ON devices TO jjongpal_user, jjongpal_admin;

BEGIN;

-- 1) users: 앱 역할 조회 권한 회수 (비밀번호 해시 노출 차단)
REVOKE SELECT ON users FROM jjongpal_user, jjongpal_admin;

-- 2) devices: 전권 회수 후 최소 권한만 재부여
REVOKE SELECT, INSERT, UPDATE, DELETE ON devices FROM jjongpal_user, jjongpal_admin;

-- refresh_token_hash 를 제외한 안전 컬럼만 조회 허용
GRANT SELECT (id, user_id, name, fcm_token, revoked_at, last_seen, created_at)
    ON devices TO jjongpal_user, jjongpal_admin;

-- 앱이 갱신하는 컬럼만 수정 허용 (RLS 가 본인 행으로 제한)
GRANT UPDATE (fcm_token, last_seen)
    ON devices TO jjongpal_user, jjongpal_admin;

COMMIT;
