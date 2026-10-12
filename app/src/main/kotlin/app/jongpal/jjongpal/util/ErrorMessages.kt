package app.jongpal.jjongpal.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    /**
     * 업로드 실패 알림용 — "무슨 일이 있었는지" 구체적으로. (2026-10-12, 0.5.8)
     * 두루뭉술한 "네트워크 확인/일시적 오류" 두 가지로는 원인을 알 수 없다는 지적 반영.
     */
    fun uploadDetail(t: Throwable): String = when (t) {
        is ApiException -> uploadDetailForCode(t.code)
        is SocketTimeoutException -> "응답 시간 초과 (전송이 너무 오래 멈춤)"
        is UnknownHostException -> "인터넷 없음 (서버 주소를 못 찾음)"
        is ConnectException -> "서버에 연결 못 함"
        is SSLException -> "보안 연결 실패"
        is IOException -> "전송 중 연결 끊김"
        else -> "앱 내부 오류 (${t.javaClass.simpleName})"
    }

    /** 업로드 응답 코드 → 구체 원인. */
    fun uploadDetailForCode(code: Int): String = when (code) {
        401 -> "로그인 만료 (다시 로그인 필요)"
        413 -> "파일이 너무 큼"
        422 -> "녹음 파일이 깨졌거나 덜 저장됨"
        in 500..599 -> "서버 오류 $code"
        else -> "서버가 거절함 ($code)"
    }
}
