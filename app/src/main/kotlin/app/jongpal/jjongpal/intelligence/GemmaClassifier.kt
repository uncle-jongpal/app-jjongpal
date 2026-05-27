package app.jongpal.jjongpal.intelligence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemma E2B (구글 온디바이스 LLM, 대규모 언어 모델) + MediaPipe (구글 온디바이스 추론 도구) 기반 분류기.
 *
 * 현재 상태: 스텁 (stub, 자리표시 구현). 의도적으로 비활성.
 * - 본인이 휴리스틱 (NotificationFilter) 결과 보고 LLM 필요성 결정 후 활성화 예정
 * - 활성화하려면 ENABLED = true 로 바꾸고 assistant v0.1 의 ModelManager + MediaPipe 의존성 추가
 *
 * 인터페이스만 잡아둔 상태. 활성화 시점에 호출 측 (NotificationCaptureService) 변경 없음.
 */
@Singleton
class GemmaClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 폰 안 분류. 활성화 전엔 항상 PENDING 반환 (휴리스틱 결정 그대로 사용).
     */
    suspend fun classify(
        sourcePackage: String?,
        title: String?,
        content: String?,
    ): FilterResult {
        if (!ENABLED) {
            return FilterResult(FilterDecision.PENDING, "gemma_disabled")
        }
        // TODO: 활성화 시 assistant v0.1 의 ModelManager + MediaPipe LLM Inference 이식
        // 1. context.assets 또는 외부 모델 디렉토리에서 Gemma 4 E2B 로드 (~3기가)
        // 2. 프롬프트: "이 알림에서 할 일 / 약속 가능성. JSON {actionable: bool, reason: ...}"
        // 3. 결과 → FilterResult
        return FilterResult(FilterDecision.PENDING, "gemma_not_implemented")
    }

    companion object {
        // Gemma 분류기 활성 여부. true 로 바꾸면 NotificationCaptureService 가 호출.
        const val ENABLED = false
    }
}
