package app.jongpal.jjongpal.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 오류 문구가 쓰이는 맥락 — 같은 HTTP 코드라도 로그인/동기화에 따라 다른 안내가 필요. */
enum class ErrorContext { LOGIN, SYNC }

/** 서버가 성공 외 상태코드를 돌려줬을 때 던지는 예외 — 중앙 매퍼가 코드로 분기하도록 코드를 보존한다. */
class ApiException(val code: Int) : Exception("HTTP $code")

/**
 * 사용자에게 보여줄 한국어 오류 문구의 단일 출처.
 * 화면·알림엔 이 문구만 내보내고, 원본 코드·스택은 Timber 로그에만 남긴다(raw 코드 노출 금지).
 */
object ErrorMessages {

    /** HTTP 상태코드 → 사용자 문구. */
    fun forCode(code: Int, context: ErrorContext): String = when {
        // 계정 열거 방지: 로그인 실패는 이메일/비번 구분 없이 한 문구로.
        context == ErrorContext.LOGIN && (code == 401 || code == 422) ->
            "이메일 또는 비밀번호가 올바르지 않습니다"
        code == 401 -> "다시 로그인해주세요"
        code == 429 -> "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요"
        code == 413 -> "파일이 너무 커요"
        code in 500..599 -> "일시적인 오류예요. 잠시 후 다시 시도해주세요"
        else -> "일시적인 오류예요. 잠시 후 다시 시도해주세요"
    }

    /** 예외(네트워크/서버 응답 등) → 사용자 문구. */
    fun friendly(t: Throwable, context: ErrorContext): String = when (t) {
        is ApiException -> forCode(t.code, context)
        is SocketTimeoutException,
        is UnknownHostException,
        is IOException -> "네트워크 연결을 확인해주세요"
        else -> "일시적인 오류예요. 잠시 후 다시 시도해주세요"
    }
}
