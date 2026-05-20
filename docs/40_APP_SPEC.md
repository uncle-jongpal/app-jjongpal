# 40 · 안드로이드 앱 명세

> 작성: 2026-05-20
> 독자: 본인 + 앱 측 작업하는 에이전트 (클로드 코드 등)
> 범위: 안드로이드 앱 (`app/` 하위). PC 백엔드는 [30_PC_PIPELINE.md](30_PC_PIPELINE.md) 참조.

## 0. 한 줄

> 폰 안에서 알림 / 통화를 자동 캡처하고 폰 안 Gemma 로 짧은 일은 즉시 분류, 통화 같은 깊은 처리는 PC 로 업로드. 사용자는 자기 데이터만 본다.

## 1. 기본 정보

- 패키지명: `app.jongpal.jjongpal`
- 언어: Kotlin (코틀린)
- 화면 도구: Jetpack Compose (제트팩 컴포즈)
- 최소 안드로이드: 13 (API 33)
- 디비: Room (안드로이드 로컬 디비) v1 부터
- 인증 토큰 저장: EncryptedSharedPreferences (안드로이드 기본 암호화 키-값 저장소)
- 의존성 주입: Hilt (안드로이드 의존성 주입)
- 백그라운드 작업: WorkManager (안드로이드 작업 스케줄러)
- HTTP: Retrofit + OkHttp
- 로깅: Timber (안드로이드 로깅 라이브러리)
- 푸시: 파이어베이스 메시징 (FCM, 파이어베이스 클라우드 푸시, 구글 모바일 푸시 서비스)
- 로컬 대규모 언어 모델: Gemma 4 E2B + MediaPipe LLM (구글 온디바이스 추론 도구)

## 2. 모듈 구성

```
app/
├── auth/              로그인 / 토큰 매니저 / 인터셉터
├── capture/           알림 / 통화 캡처 서비스
│   ├── notification/
│   └── call/
├── data/
│   ├── local/         Room 디비 (이벤트 / 할 일)
│   ├── remote/        PC 와의 통신 (Retrofit 인터페이스)
│   └── repository/    저장소 패턴
├── intelligence/      폰 안 Gemma 추론 (알림 분류 / 할 일 추출)
├── sync/              PC 로 업로드 / 푸시 수신
├── ui/
│   ├── login/
│   ├── main/          내 할 일 / 통화 정리 / 권한 / 설정
│   └── components/
└── di/                Hilt 모듈
```

## 3. 화면 흐름

### 3.1 첫 실행

1. 로그인 화면 (이메일 + 비밀번호 입력)
2. 로그인 성공 → 토큰 저장 + 메인 화면 진입
3. 권한 안내 (알림 접근·통화 녹음 디렉토리 접근·배터리 최적화 제외) — 단계별 진행

### 3.2 메인 화면 탭

- **내 할 일** — 오늘 / 이번 주 / 전체 필터. 새 항목 빠른 추가.
- **통화 정리** — 통화 요약 카드 + 추출된 할 일 + 약속
- **권한** — 권한 상태 / 다시 요청
- **설정** — 로그아웃 / PC 주소 (테스트용) / 동기화 / 사용자 정보

### 3.3 푸시 수신 흐름

1. 파이어베이스 메시지 도착 (페이로드 = 데이터 전용, 본문 없음)
2. `RemoteMessage.data` 에서 `type` 과 `summary_id` 읽음
3. PC 의 `/rest/summaries?id=eq.<summary_id>` 호출 → 본문 가져옴
4. Room 디비에 저장 + 메인 화면 갱신
5. 로컬 알림 띄움 (제목 + 짧은 요약). 알림 누르면 해당 화면.

## 4. 데이터 모델 (Room)

```kotlin
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,          // UUID
    val type: String,                    // 'notification' | 'call'
    val sourcePackage: String?,
    val title: String?,
    val content: String?,
    val timestamp: Long,                 // 폰 시각 (보조)
    val deviceTimestamp: Long,
    val syncStatus: String,              // 'PENDING' | 'SYNCING' | 'SYNCED' | 'FAILED'
    val metadataJson: String?,
    val retryCount: Int = 0,
    val lastSyncAttempt: Long? = null
)

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,
    val content: String,
    val source: String,                  // 'manual' | 'call' | 'notification' | 'ai_suggestion'
    val sourceEventId: String?,
    val dueAt: Long?,
    val relatedPerson: String?,
    val status: String,                  // 'open' | 'done' | 'archived'
    val completionConfidence: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val syncStatus: String               // PC 와 동기화 상태
)

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val id: String,          // 서버 UUID
    val transcriptText: String?,         // PC 에서 가져온 텍스트
    val summary: String,
    val rawJson: String,                 // {todos, appointments, ...}
    val pushedAt: Long,
    val viewed: Boolean = false
)
```

## 5. 인증 모듈

### 5.1 로그인 화면

이메일 + 비밀번호 + 로그인 버튼. 실패 시 에러 메시지.

### 5.2 토큰 매니저

- access_token, refresh_token 을 EncryptedSharedPreferences 에 저장
- 만료 시간도 같이 저장 (재발급 판단용)
- 사용자 ID + 이름 + 역할도 캐시 (자기 데이터 표시용)

### 5.3 인증 인터셉터 (OkHttp Interceptor)

- 모든 PC 요청에 Authorization 헤더 자동 첨부
- 응답 401 받으면 refresh_token 으로 자동 재발급 → 원래 요청 재시도
- refresh 도 실패하면 로그아웃 처리 (저장된 토큰 다 지우고 로그인 화면)

## 6. 캡처 모듈

### 6.1 알림 캡처

- `NotificationListenerService` 상속 — 활성 알림 모두 가로채기
- 필터 (시스템 / 본 앱 알림 제외)
- Room 디비에 EventEntity (type='notification') 로 저장
- 폰 안 Gemma 가 즉시 분류 (할 일성 / 일반 알림)
- 할 일성으로 분류되면 ToDoSuggestion 알림 띄워 사용자 액션 받음

### 6.2 통화 녹음 캡처

- 라이프싱크 의 통화 녹음 감지 모듈 이식 + 정리
- 제조사별 통화 녹음 디렉토리 7종 감시 (본인 폰 경로 우선)
- 새 통화 파일 감지 → EventEntity (type='call', metadataJson에 파일 경로) 생성
- 동기화 워커가 PC 로 업로드

### 6.3 사용자 거부 옵션

- 통화 종료 후 사용자 명시적 승인 알림: "이 통화 PC 로 보내기 [예 / 아니오]"
- 기본 동작 — 폰 화면 켜져 있고 통화 종료 직후 3분 안만 알림. 그 외는 자동 송신 (사용자가 무시한 경우 자동 진행)
- 발신자 번호 블랙리스트 룰 — 의료기관 / 변호사 / 거래처 번호 자동 스킵

## 7. 폰 안 추론

### 7.1 Gemma 4 E2B (MediaPipe LLM)

- 알림이 들어올 때 즉시 분류
- 사용 모델: Gemma 4 E2B
- 모델 파일 경로 — assistant v0.1 의 `ModelManager` 패턴 그대로 이식
- 추론 시간 가드 (3초 초과 시 중단)
- 배터리 가드 (배터리 15% 이하 시 추론 일시 중지)
- 발열 가드 (CPU 온도 임계치 초과 시 일시 중지)

### 7.2 분류 출력

```kotlin
data class NotificationClassification(
    val isToDoLike: Boolean,
    val confidence: Float,
    val suggestedTodo: String?,
    val relatedPerson: String?
)
```

폰 안에서 끝남. 외부 송신 없음.

## 8. 동기화

### 8.1 폰 → PC 업로드

- WorkManager 주기 작업 (`Constraints.NetworkType.CONNECTED`)
- 알림 / 할 일 / 통화 메타 → REST API (`POST /rest/events`, `/rest/todos`)
- 통화 파일 → multipart 업로드 (`POST /upload/audio`)
- 실패 시 폰 로컬 큐 (Room) 에 쌓임. 다음 트리거 때 재시도.

### 8.2 PC → 폰 푸시 수신

- 파이어베이스 메시징 서비스 (FCM) 등록
- 푸시 받으면 PC 의 자동 REST 입구 (PostgREST) 호출 → 본문 가져옴
- Room 에 저장 + 화면 반영 + 로컬 알림

## 9. 권한

- `BIND_NOTIFICATION_LISTENER_SERVICE` — 알림 캡처 (설정 → 특수 권한 → 알림 액세스)
- `MANAGE_EXTERNAL_STORAGE` — 통화 녹음 디렉토리 접근 (안드로이드 11 이후 제약 있음)
- `READ_CALL_LOG` — 발신자 번호 블랙리스트 룰 (선택)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — 통화 파일 업로드용 (안드로이드 14 이후 필수)
- `POST_NOTIFICATIONS` — 로컬 알림 띄우기 (안드로이드 13 이후 필수)
- `RECEIVE_BOOT_COMPLETED` — 부팅 후 캡처 서비스 재시작

## 10. 빌드 가이드

### 10.1 local.properties

```properties
PC_BASE_URL=https://jjongpal.<본인도메인>.com
FIREBASE_PROJECT_ID=<프로젝트 ID>
```

### 10.2 google-services.json

파이어베이스 콘솔에서 다운로드 → `app/` 에 배치. `.gitignore` 됨.

### 10.3 빌드 명령

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 10.4 키스토어

서명 키스토어 (release) 는 `app/keystore/` 에 두고 `.gitignore`. 백업은 별도 안전한 위치에.

## 11. 테스트 우선

- 저장소 (Repository) 단위 테스트 — Room 인메모리
- 워커 (Worker) 단위 테스트 — WorkManager 테스트 환경
- 인증 인터셉터 단위 테스트 — MockWebServer
- 알림 분류 (Gemma) — 모델 호출은 fake 로 두고 분기 로직만 테스트

테스트 파일은 `app/src/test/` (단위) + `app/src/androidTest/` (통합) 분리.

## 12. v0.1 (assistant) 에서 이식할 것

- 정체성 / 원칙 문서 (00_VISION) → v0.2 의 [00_VISION.md](00_VISION.md) 로 재구성
- `NotificationCaptureService` → `app/capture/notification/`
- `ModelManager` (Gemma 로드 / 추론) → `app/intelligence/`
- `IntelligenceWorker` (배경 추론 / 가드) → `app/intelligence/`
- ToDoListScreen 의 일부 디자인 토큰 → `app/ui/components/`

## 13. 라이프싱크에서 이식할 것

- `CallRecordingObserver` (통화 녹음 디렉토리 7종 감시 + 파일 감지) → `app/capture/call/`
- `FileObserverService` 패턴 — 전면 서비스 (Foreground Service, 항상 작동 표시되는 서비스) + dataSync 타입
- 부팅 후 재시작 리시버 패턴

## 14. 외부 에이전트 작업 시 핵심 사실

(추가는 [10_DECISIONS.md](10_DECISIONS.md) §18 외부 에이전트 핸드오프 참조)

- 사용자 식별자는 일반 ID (서버 응답의 user.id). 의미 단어 비교 X.
- 토큰은 EncryptedSharedPreferences 에만. 로그·디버그 출력 X.
- 통화 파일은 업로드 성공 후 폰 측에서도 삭제 (디스크 누적 방지)
- 푸시 페이로드에 본문 없음. 본문은 PC 에서 다시 가져옴.
- UI 한국어. 코드 주석 한국어 OK.
- 로깅은 Timber. 운영 빌드 (`BuildConfig.DEBUG == false`) 에서는 로그 억제.
