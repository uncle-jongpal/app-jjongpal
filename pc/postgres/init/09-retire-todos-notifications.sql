-- 할 일·약속 기능과 옛 알림 수집 데이터 공식 폐기 (2026-10-13, 형 결정)
-- - 할 일(todos)·약속(appointments): 앱 0.5.9 부터 안 씀(화면은 그 전부터 숨김), 09-02 이후 새 기록 없음
-- - 다른 앱 알림 수집(events type='notification')과 그 처리 기록(event_processing_log): 09-02 이후 안 쌓임
-- 백업: db-server·개발랩탑 ~/work/backups/db/
--   jjongpal-todos-appointments-eventlog-20261013.dump (pg_dump -Fc, 표 구조+데이터)
--   jjongpal-notification-events-20261013.csv (알림 이벤트 32,272건)
-- /rest/todos·/rest/appointments 는 옛 앱이 깨지지 않게 빈 목록(쓰기 410)만 돌려준다(rest.py).

BEGIN;
DROP TABLE IF EXISTS public.todos;
DROP TABLE IF EXISTS public.appointments;
DELETE FROM public.event_processing_log;
DELETE FROM public.events WHERE type = 'notification';
COMMIT;
