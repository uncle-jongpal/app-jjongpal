-- PostgREST 가 매 요청 직전에 호출하는 컨텍스트 설정 함수
-- JWT 클레임에서 사용자 ID / 역할 추출 → 세션 변수에 박음
-- RLS 정책이 이 값으로 행 단위 격리 검사

CREATE OR REPLACE FUNCTION public.set_app_context() RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    claims JSONB;
    uid_text TEXT;
    role_text TEXT;
BEGIN
    -- JWT 클레임 가져오기
    BEGIN
        claims := current_setting('request.jwt.claims', true)::JSONB;
    EXCEPTION WHEN OTHERS THEN
        claims := NULL;
    END;

    IF claims IS NULL THEN
        -- 비인증 요청 — 세션 변수 비우기
        PERFORM set_config('app.user_id', '', true);
        PERFORM set_config('app.user_role', '', true);
        RETURN;
    END IF;

    uid_text  := claims ->> 'user_id';
    role_text := COALESCE(claims ->> 'role', 'user');

    PERFORM set_config('app.user_id', COALESCE(uid_text, ''), true);
    PERFORM set_config('app.user_role', role_text, true);
END;
$$;

GRANT EXECUTE ON FUNCTION public.set_app_context() TO jjongpal_anon, jjongpal_user, jjongpal_admin;

-- 도우미: 현재 사용자 ID 가져오기 (RLS 정책에서 사용)
CREATE OR REPLACE FUNCTION public.current_user_id() RETURNS INTEGER
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    uid_text TEXT;
BEGIN
    uid_text := current_setting('app.user_id', true);
    IF uid_text IS NULL OR uid_text = '' THEN
        RETURN NULL;
    END IF;
    RETURN uid_text::INTEGER;
END;
$$;

CREATE OR REPLACE FUNCTION public.current_user_role() RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN COALESCE(NULLIF(current_setting('app.user_role', true), ''), 'anon');
END;
$$;

GRANT EXECUTE ON FUNCTION public.current_user_id() TO jjongpal_anon, jjongpal_user, jjongpal_admin;
GRANT EXECUTE ON FUNCTION public.current_user_role() TO jjongpal_anon, jjongpal_user, jjongpal_admin;
