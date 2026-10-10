-- 장애 알림함 (2026-10-10)
-- 앱 0.5.6 에서 FCM(파이어베이스 푸시)을 걷어낸 뒤, watchdog 의 장애 알림("받아쓰기 밀림" 등)이
-- 폰에 닿지 않던 문제 해결. watchdog 이 여기에 쌓고, 앱이 요약 폴링(약 3분) 때 같이 읽어 로컬 알림을 띄운다.
-- 시스템 전체 알림이라 어드민만 읽는다.

CREATE TABLE IF NOT EXISTS public.app_alerts (
    id          bigserial PRIMARY KEY,
    level       text        NOT NULL DEFAULT 'warn',   -- crit | warn | info | ok
    title       text        NOT NULL,
    body        text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_alerts_created ON public.app_alerts (created_at DESC);

ALTER TABLE public.app_alerts ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS app_alerts_admin_read ON public.app_alerts;
CREATE POLICY app_alerts_admin_read ON public.app_alerts
    FOR SELECT USING (current_user_role() = 'admin');

GRANT SELECT ON public.app_alerts TO jjongpal_user, jjongpal_admin;
