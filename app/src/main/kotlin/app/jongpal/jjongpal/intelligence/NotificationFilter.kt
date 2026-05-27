package app.jongpal.jjongpal.intelligence

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 폰 측 알림 사전 분류 (휴리스틱).
 * Gemma 분류기 (LLM, 대규모 언어 모델) 를 거치기 전 단순 룰로 명백한 잡음·민감 알림 차단.
 *
 * 분류 결과:
 *  - SEND: PC 로 보냄 (가치 있을 가능성)
 *  - SKIP_NOISE: PC 로 안 보냄 (광고·시스템·잡담)
 *  - SKIP_SENSITIVE: PC 로 안 보냄 (인증번호·금융 — 외부 송신 금지)
 *  - PENDING: 휴리스틱으론 판단 못 함 (Gemma 활성화되면 거기서 판단). 현재는 SEND 와 동일.
 */
enum class FilterDecision {
    SEND, SKIP_NOISE, SKIP_SENSITIVE, PENDING
}

data class FilterResult(val decision: FilterDecision, val reason: String?)

@Singleton
class NotificationFilter @Inject constructor() {

    fun classify(
        sourcePackage: String?,
        title: String?,
        content: String?,
    ): FilterResult {
        val pkg = sourcePackage?.lowercase().orEmpty()
        val text = listOfNotNull(title, content).joinToString(" ").trim()
        val textLower = text.lowercase()

        // 1. 시스템 / 본 앱 / 명백한 잡음 패키지
        if (pkg in SKIP_PACKAGES) {
            return FilterResult(FilterDecision.SKIP_NOISE, "system_or_self_pkg=$pkg")
        }

        // 2. 너무 짧은 알림 (8자 미만) — 의미 추출 어려움
        if (text.length < 8) {
            return FilterResult(FilterDecision.SKIP_NOISE, "too_short_len=${text.length}")
        }

        // 3. 민감 정보 키워드 — 외부 송신 금지
        if (SENSITIVE_KEYWORDS.any { textLower.contains(it) }) {
            val hit = SENSITIVE_KEYWORDS.firstOrNull { textLower.contains(it) }
            return FilterResult(FilterDecision.SKIP_SENSITIVE, "sensitive_keyword=$hit")
        }

        // 4. 광고 / 프로모션 / 마케팅 키워드
        if (AD_KEYWORDS.any { textLower.contains(it) }) {
            val hit = AD_KEYWORDS.firstOrNull { textLower.contains(it) }
            return FilterResult(FilterDecision.SKIP_NOISE, "ad_keyword=$hit")
        }

        // 5. 화이트리스트 통과 — 메신저 / 메일 / 캘린더 / SMS 류
        if (ALLOW_PACKAGES.any { pkg.startsWith(it) }) {
            return FilterResult(FilterDecision.SEND, "allowed_pkg=$pkg")
        }

        // 6. 그 외 — 휴리스틱으론 모름. 일단 SEND (Gemma 활성 시 더 정확히).
        return FilterResult(FilterDecision.PENDING, "no_rule_matched_pkg=$pkg")
    }

    companion object {
        // 시스템·본 앱·자주 잡음 만드는 앱 + 미디어 / 소셜 / 게임 (가치 0)
        private val SKIP_PACKAGES = setOf(
            // 시스템
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.server.telecom",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.samsung.android.app.notes.sync",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.bixby.service",
            "com.samsung.android.honeyboard",
            "com.samsung.android.scloud",
            "com.samsung.android.app.taskedge",
            "com.sec.android.app.launcher",
            "com.sec.android.app.samsungapps",
            "app.jongpal.jjongpal",
            // 미디어 — 재생 중 알림 등 가치 0
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.melon.musicplayer",
            "com.iloen.melon",
            "com.kakao.melon",
            "com.netflix.mediaclient",
            "com.disney.disneyplus",
            "com.coupang.mobile.play",
            // 소셜 미디어 — 잡담 / 광고 위주
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.instagram.lite",
            "com.twitter.android",
            "com.snapchat.android",
            "com.tiktok",
            "com.ss.android.ugc.trill",     // 틱톡 글로벌
            "com.zhiliaoapp.musically",     // 틱톡
            "com.linkedin.android",
            "com.pinterest",
            // 쇼핑 — 자극·광고 위주
            "com.coupang.mobile",
            "com.elevenst",
            "com.gmarket.mainapp",
            "com.ssg.serviceapp.android.egiftcertificate",
            // 게임 / 엔터테인먼트 흔한 패키지
            "com.netmarble.iv",
            "com.kabam.marvelchampions.amazon",
        )

        // 메신저 / 메일 / 캘린더 / 문자류 — 약속·할 일 추출 가치 큰 알림 보냄
        private val ALLOW_PACKAGES = setOf(
            "com.kakao.talk",                  // 카톡
            "com.kakao.talkchannel",
            "com.kakao.taxi",
            "com.kakao.parcel",
            "com.naver.android.line",
            "jp.naver.line.android",
            "com.discord",
            "com.whatsapp",
            "com.facebook.orca",
            "com.google.android.gm",            // 지메일
            "com.samsung.android.email",
            "com.microsoft.office.outlook",
            "com.naver.android.mail",
            "com.daum.app.mail",
            "com.google.android.calendar",
            "com.samsung.android.calendar",
            "com.samsung.android.messaging",    // 삼성 문자
            "com.google.android.apps.messaging", // 구글 문자
            "com.fsck.k9",
            "org.telegram.messenger",
            "com.tencent.mm",                   // 위챗
            "com.naver.mybox",
        )

        // 민감 정보 키워드 (보안). 한국어 + 영문 둘 다.
        private val SENSITIVE_KEYWORDS = listOf(
            "인증번호", "인증 번호", "otp", "one-time password", "1회용 비밀번호",
            "본인 인증", "본인인증", "결제 금액", "출금", "입금",
            "안전번호", "보안 코드", "보안코드", "보안번호",
            "card payment", "결제 완료", "신용카드", "체크카드",
            "transfer received", "verification code",
        )

        // 광고·프로모션 키워드
        private val AD_KEYWORDS = listOf(
            "이벤트", "할인", "쿠폰", "혜택", "프로모션", "광고",
            "특가", "세일", "sale", "discount", "promo", "coupon",
            "무료배송", "신상품", "리워드", "포인트 적립",
            "구독 갱신", "회원 가입", "친구 초대",
        )
    }
}
