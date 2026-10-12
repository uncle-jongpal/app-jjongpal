# 쫑팔이삼촌 통화요약 앱 — 참고사례·오픈소스·도입 후보 조사 (2026-10-13)

범례: 노력(작업량) S=반나절~1일 / M=2~5일 / L=1주+ · 우선순위 P1=바로 / P2=다음 버전 / P3=여유 있을 때
"미확인" = 이번 조사에서 1차 출처로 확인 못 한 것.

## 0. 한 줄 결론
- 정확도·환각은 "VAD(무음 자르기) + large-v3-turbo + 단어장 프롬프트"로 먼저 잡고, 화자 구분은 pyannote community-1 + "내 목소리 등록"으로 "나 / 상대방" 라벨을 붙이는 게 가장 효과 대비 비용이 좋다.
- 폰 쪽은 FCM 없이도 "업로드 = WorkManager 재시도 + 중복방지 해시", "알림 = ntfy 자체호스팅(UnifiedPush)"로 3분 폴링을 대체할 수 있다.
- 다음 기능은 상용 서비스들이 공통으로 가진 "연락처별 통화 기록 + 전문 검색 + 할 일 추출 + 말풍선 탭→해당 구간 재생"이 핵심.

## 1. 상용·소비자 서비스 참고 (사용자가 가치를 느끼는 기능)

| 서비스 | 핵심 기능 | 이 앱에 가져올 점 |
|---|---|---|
| 삼성 갤럭시 AI 통화 녹음 + 텍스트 변환(Transcript Assist, One UI 7+) | 녹음→기기 내 텍스트 변환, 요약(기본 클라우드, "기기에서만 처리" 옵션), 핵심/키워드 추출, 번역. 삼성·구글 계정 필요 | 사용자가 이미 S25 Edge에서 쓸 수 있는 기준선. 우리 앱의 차별점은 "연락처별 누적 기록·검색·할 일·인물 정리"여야 함. https://www.samsung.com/in/support/mobile-devices/how-to-use-call-transcript-feature-in-galaxy-devices/ |
| SKT 에이닷(A.) 전화 | 통화 후 자동 녹음→채팅형(말풍선) 텍스트, 키워드 검색, 말풍선 탭→그 구간 음성 재생, 통화·문자 요약, 통화 중 언급된 할 일 자동 추출→할일 등록, 2026-07 "에이전트콜"(고객센터 실시간 자막·대기 대행) | **말풍선형 대본 + 구간 재생 + 검색 + 할 일 자동 등록**은 그대로 벤치마크 대상. https://news.sktelecom.com/199235 , https://news.sktelecom.com/227633 |
| LG유플러스 익시오(ixi-O) 2.0 (2025-10) | 온디바이스 처리(개인정보), "AI 스마트 요약"=목적별 양식(메모·문의·할 일·부동산·영업 등), AI 대화 검색, 엑사원 sLM으로 한 줄/상세 요약·키워드·AI Task 제안 | **통화 유형별 요약 양식**(가족·업무·예약·민원 등)을 Claude 프롬프트에 분기로 넣기. https://ceoscoredaily.com/page/view/2025011016532711407 , https://www.inews24.com/view/1780459 |
| 애플 아이폰 통화 녹음(iOS 18.1+) | 녹음 시 상대에게 안내음, 메모 앱에 실시간 대본, 한국어 녹음·대본 지원, Apple Intelligence 한국어(iOS 18.4+)로 요약 | "상대 고지" 방식은 애플만. 메모 앱 연동처럼 **요약을 다른 앱으로 공유**하는 흐름 참고. https://designcompass.org/en/2025/04/02/apple-intelligence-korean/ |
| 구글 픽셀 Call Notes (Gemini Nano) | 기기 내 대본·요약, 통화 후 일정·할 일·리마인더 원탭 생성 제안(보도 기준) | **통화 후 "캘린더/할 일로 만들기" 원탭 버튼**. 한국 미출시(미확인). https://www.androidauthority.com/google-pixel-call-notes-3465469/ |
| Plaud Note / Note Pro (하드웨어 녹음기) | 화자 라벨, 사용자 단어장, 3,000+ 요약 템플릿, 마인드맵, 녹음 중 "하이라이트" 표시, Ask(질문하기), 자동 워크플로 | **사용자 단어장(이름·회사명)**, **"이 통화에 질문하기"**(Claude에 대본 넣고 Q&A). https://www.plaud.ai/blogs/news/plaud-note-pro-vs-plaud-note-explained |
| Otter.ai | 화자 구분, 액션아이템, 키워드 하이라이트, 검색, 요약 자동 배포 | 회의 중심이라 통화엔 일부만. 액션아이템·하이라이트 구조 참고 |
| 네이버 클로바노트 | 한국어 STT + 화자 구분 + HyperCLOVA 요약, 주요 주제·다음 할 일 자동 추출(편집 가능), 통화 녹음 파일 불러오기 | **자동 추출 결과를 사용자가 고칠 수 있게** (요약·할 일 편집). 개인용 무료 한도 존재(세부 미확인) |
| 티로(Tiro)·다글로(Daglo) | 한국어 회의록·받아쓰기 SaaS (세부 기능 이번 조사에서 1차 확인 못 함 = 미확인) | 참고만 |

사용자가 가치를 느끼는 공통 기능(우선순위 순):
1. 연락처별 통화 이력(누구와 무슨 얘기했나) 2. 대본 검색 3. 할 일/약속 자동 추출→리마인더 4. 말풍선(화자) 대본 + 구간 재생 5. 한 줄 요약 + 상세 요약 6. 개인정보(온디바이스/자체호스팅) 7. 결과 편집·공유.
한국 서비스 특징: 통신사(SKT·LGU+)가 "전화 앱 자체"에 넣어 녹음·요약·할 일을 통합, 개인정보 때문에 온디바이스 강조. 우리 앱은 자체 서버 = 개인정보 측면 강점을 살리는 방향.

법적 참고: 한국은 대화 당사자 녹음 합법(통신비밀보호법). 상대방 고지 의무화 개정안 논의 이력 있으나 통과 여부 미확인.

## 2. 한국어 STT(음성→글자) 개선

### 2-1. faster-whisper + large-v3-turbo (기본 엔진)
- 링크: https://github.com/SYSTRAN/faster-whisper (MIT, 별 약 25.8k)
- 무엇: CTranslate2 기반 Whisper. openai-whisper 대비 최대 4배 빠름, int8 양자화, Silero VAD 내장, 단어 타임스탬프, 배치 처리.
- large-v3-turbo: 디코더 32→4층, 809M 파라미터, 2~5배 빠름. FLEURS 한국어 CER 약 5.2% 수준(large-v3 약 4.9~5.3%) → 정확도 손실 거의 없음.
- 8GB 적합성: turbo int8_float16 기준 약 2~3GB 예상(미확인, 실측 필요). 여유 충분.
- 적용: 현재 openai-whisper를 쓰는 경로가 있으면 faster-whisper로 일원화. 노력 S, **P1**.

### 2-2. VAD + 환각 억제 설정 (멈춤·"감사합니다" 환각 대책)
- Silero VAD: https://github.com/snakers4/silero-vad (MIT, PyPI v6.2.x)
- 원리: 환각의 주원인은 "말 없는 구간". VAD로 말 구간만 모델에 넣으면 "시청해주셔서 감사합니다"류 대부분 사라짐.
- 권장 설정(faster-whisper `transcribe`):
  - `vad_filter=True`, `vad_parameters={min_silence_duration_ms: 500~1000, speech_pad_ms: 200~400}`
  - `condition_on_previous_text=False` (반복 루프 방지 — 가장 효과 큼)
  - `language="ko"` 고정(언어 자동감지 오류 방지)
  - `compression_ratio_threshold=2.4`, `log_prob_threshold=-1.0`, `no_speech_threshold=0.6`, `temperature=[0,0.2,0.4,...]` 폴백
  - `hallucination_silence_threshold`(word_timestamps=True 필요)로 긴 무음 속 환각 구간 건너뛰기
  - 후처리 블랙리스트: "시청해주셔서 감사합니다", "구독과 좋아요", "MBC 뉴스…" 등 + 같은 문장 3회 이상 반복 제거
- STT 멈춤(stall) 대책: 파일당 타임아웃(오디오 길이 × 계수), 실패 시 temperature 올려 재시도, 워커 프로세스 분리(모델 OOM 시 재시작). 큐 상태를 DB에 기록.
- 노력 S, **P1**. 참고: https://doc.scenemaker.solbox.com/en/blog/4 (한국어 faster-whisper 최적화 경험담)

### 2-3. initial_prompt / hotwords (연락처 이름·고유명사)
- faster-whisper는 `initial_prompt`와 `hotwords` 지원. 통화 상대 연락처 이름 + 자주 나오는 회사·지명·인물 사전(서버에 누적)을 프롬프트로.
- 주의: 프롬프트가 길거나 환각 문구를 담으면 오히려 반복 환각 유발. 224토큰 제한, 짧은 단어 나열 권장. condition_on_previous_text=False와 함께.
- 노력 S, **P1** (인물 사전은 이미 "언급된 사람" 데이터가 있으니 재활용).

### 2-4. 화자 구분: pyannote speaker-diarization-community-1 (+ WhisperX)
- pyannote community-1: https://huggingface.co/pyannote/speaker-diarization-community-1 (모델 CC-BY-4.0, 2025 공개, pyannote.audio 4.0, HF 토큰·약관 동의 필요). 3.1보다 AMI·DIHARD3·VoxConverse에서 우수. "exclusive" 모드(한 시점 한 명)로 STT 단어 타임스탬프와 정렬이 쉬움.
- WhisperX: https://github.com/m-bain/whisperX (BSD-2, 별 약 24.5k, 최신 정식 v3.8.6=2025-05, 이후 rc만 확인 → 활동 둔화). faster-whisper + wav2vec2 강제정렬 + pyannote community-1 일체형. 한국어 정렬 기본 모델 `kresnik/wav2vec2-large-xlsr-korean` 내장.
- 통화 특화 팁:
  - `num_speakers=2` 고정(통화는 거의 2명) → 정확도↑.
  - **"내 목소리 등록"**: 형 목소리 샘플 몇 개로 화자 임베딩을 만들어 두고, 두 화자 중 유사도 높은 쪽을 "나", 나머지를 연락처 이름으로 라벨. 1인용 앱이라 매우 효과적.
  - 스테레오(채널 분리) 가능하면 diarization 불필요: 삼성 기본 녹음 m4a가 모노인지 스테레오인지 **미확인** → 서버에 있는 파일로 `ffprobe -show_streams` 한 번 확인 필요. (BCR은 업/다운링크 분리 스테레오 옵션이 있으나 루팅 필요, Pixel에서만 확실)
- 8GB: STT 모델과 동시 적재 대신 순차 실행(STT → 해제 → diarization) 권장. pyannote 자체는 1~2GB 수준(미확인).
- 노력 M, **P1~P2** (말풍선 대본 뷰의 전제).

### 2-5. 한국어 파인튜닝 Whisper (Hugging Face)
- ghost613/whisper-large-v3-turbo-korean (+ faster-whisper 변환판 ghost613/faster-whisper-large-v3-turbo-korean): zeroth-korean(약 206시간, 낭독체) 학습, 테스트 CER 7.58%→2.06%. 라이선스 표기 미확인. https://huggingface.co/ghost613/whisper-large-v3-turbo-korean
- 주의: 낭독 음성(같은 분야) 기준 수치라 **전화 음질(8kHz 대역, 압축)·구어체에선 개선이 보장 안 됨**, 오히려 문장부호·띄어쓰기 습관이 바뀔 수 있음.
- 적용: 형의 실제 통화 20~30개로 "정답 대본" 소량 만들어 베이스 vs 파인튜닝 CER 비교 후 채택. 노력 M(평가셋 구축 포함), **P2**.
- 장기: 형 통화 데이터로 직접 LoRA 파인튜닝(Korean telemedicine 전화 STT 논문처럼 도메인 파인튜닝 효과 보고됨: https://scholarworks.gnu.ac.kr/item/e79f88fa-3eea-48cf-81dc-c918ff865d30 ). 노력 L, P3.

### 2-6. 대안 엔진
- **Qwen3-ASR (1.7B / 0.6B)** + Qwen3-ForcedAligner-0.6B: 2026-01-29 공개, 52개 언어, 한국어 포함, 강제정렬도 한국어 지원, vLLM·transformers(2026-06 네이티브), 스트리밍. FLEURS 한국어 CER 1.7B 약 4.6%(whisper-large-v3 약 4.9%)라는 제3자 비교 있음. 라이선스는 Apache-2.0로 알려짐(미확인). 1.7B bf16이면 8GB에 들어갈 가능성 높음(미확인). https://github.com/QwenLM/Qwen3-ASR → **P2 비교 후보** (평가셋으로 A/B). 노력 M.
- NVIDIA Parakeet-TDT-0.6B-v3 / Canary-1B-v2 (2025): 유럽 25개 언어만, **한국어 미지원** → 제외. https://arxiv.org/abs/2509.14128
- SenseVoice-Small: 매우 빠르나 한국어 CER 8.3% 수준으로 Whisper보다 낮음 → 제외.
- 네이버 클로바 계열 오픈 STT 모델: 공개 모델 확인 못 함(미확인). CLOVA Speech는 유료 API(자체호스팅 원칙과 상충).
- BuzzASR/korean: 한국어 CER 4.61% 주장, 출처·라이선스 미확인 → 참고만.

## 3. 안드로이드 쪽 (FCM 없이 안정적인 업로드·알림)

### 3-1. 새 녹음 감지
- 삼성 기본 녹음 위치: 내장저장소 `Recordings/Call/` (m4a, 첫 녹음 후 폴더 생성). One UI 7+는 녹음과 함께 삼성 대본도 생성.
- 권장: MediaStore(오디오) ContentObserver + WorkManager 주기 작업(15분)으로 "놓친 파일 스캔" 이중화. 권한은 READ_MEDIA_AUDIO(Android 13+).
- BCR(Basic Call Recorder): https://github.com/chenxiaolong/BCR (GPL-3.0, 별 약 2.9k) — 루팅/시스템앱 필요라 이 폰엔 부적합. 다만 **파일명 규칙(날짜_방향_번호_이름), 업/다운링크 스테레오** 설계는 참고. 노력 -, P3.

### 3-2. 업로드 신뢰성 (LTE 끊김)
- 1단계(권장, S, **P1**): WorkManager `OneTimeWorkRequest` + 네트워크 제약 + 지수 백오프 + `setExpedited`(즉시 시작, 쿼터 초과 시 일반 작업으로 강등) + **서버 측 해시(SHA-256) 기반 중복 방지(멱등 업로드)**. 통화 m4a는 수 MB라 이것만으로 대부분 해결.
- 2단계(긴 통화·잦은 끊김이면, M, **P2**): **tus 재개형 업로드** — 서버 tusd(Go, MIT) 또는 FastAPI용 tus 구현, 폰은 tus-android-client + tus-java-client(MIT, 2025-09 활동 확인). 끊긴 지점부터 이어 올림. https://tus.io/implementations , https://github.com/tus/tus-android-client
- 포그라운드 서비스 규칙(Android 14~16): 서비스 타입 선언 필수. dataSync는 Android 15부터 24시간 중 총 6시간 제한(onTimeout 처리 필요), shortService는 약 3분. 백그라운드에서 FGS 시작 제한 → WorkManager가 대신 처리하게 두는 게 안전. 사용자가 누른 대용량 전송은 UIDT(사용자 시작 데이터 전송 작업, Android 14+). https://developer.android.com/develop/background-work/services/fg-service-timeout , https://developer.android.com/about/versions/15/changes/datasync-migration

### 3-3. 알림(Doze 지연) — ntfy + UnifiedPush
- ntfy: https://github.com/binwiederhier/ntfy (Apache-2.0/GPLv2 듀얼, 2026-08 릴리스, 활발). 자체 서버에서 HTTP POST로 폰에 푸시.
- UnifiedPush: https://unifiedpush.org (Android connector 라이브러리 `org.unifiedpush.android:connector`). ntfy 앱이 "배포자(distributor)" 역할 → 우리 앱은 ntfy 앱을 통해 푸시 수신.
- 간단 버전(S): 서버가 요약 완료 시 ntfy 토픽으로 POST, 폰엔 ntfy 앱만 설치(우리 앱 수정 최소). 알림 누르면 우리 앱 딥링크.
- 정식 버전(M): 우리 앱에 UnifiedPush connector 넣어 "새 요약 도착" 시 앱이 바로 동기화 → 3분 폴링 제거.
- 즉시성: ntfy 앱의 "즉시 전달(instant delivery)"은 상시 연결 유지 방식 → ntfy 앱을 배터리 최적화 예외로. **P1(간단 버전)**.

### 3-4. 배터리·삼성 제약
- 삼성 "절전 앱(sleeping, 3일 미사용 시 Job·Alarm·FGS 제한)", "깊은 절전 앱(16일 미사용 시 완전 정지)". 설정 > 배터리 > 백그라운드 사용 제한 > **"절전 예외 앱(Never sleeping)"에 우리 앱·ntfy 추가**. 펌웨어 업데이트 후 다시 들어갈 수 있으니 앱이 상태 점검 화면 제공. https://dontkillmyapp.com/samsung , https://developer.samsung.com/mobile/app-management.html
- 앱 내 "건강검진" 화면: 배터리 최적화 예외 여부(`isIgnoringBatteryOptimizations`), 알림 권한, 마지막 업로드/동기화 시각, 대기 중 파일 수 표시. 노력 S, **P1**.

## 4. 자체호스팅 운영 패턴

| 항목 | 링크·라이선스 | 이 앱에서의 의미 | 노력 | 우선 |
|---|---|---|---|---|
| restic 백업 (Postgres 덤프 + 음성 파일) | https://restic.net (BSD-2) | 암호화·중복제거 백업을 다른 장비(디비/작업PC 등)로. pg_dump → restic, 매일 + 보존정책(일7·주4·월6). 복구 리허설 필수 | S | **P1** |
| borg | https://www.borgbackup.org (BSD-3) | restic 대안(SSH 대상에 borg 필요) | S | P3 |
| PGroonga (한국어 전문 검색) | https://pgroonga.github.io (PostgreSQL 라이선스), 4.0.4=2025-10, PG18 지원 | 한국어 형태소 없이도 빠른 부분일치 검색. pg_bigm보다 수~10배 빠름(공식 주장). 대본 검색에 바로 적용 | S~M | **P1~P2** |
| pg_bigm | https://github.com/pgbigm/pg_bigm (PostgreSQL 라이선스) | 2-gram LIKE 가속. 단순하지만 대용량에서 느림. 데이터 적으면 이것도 충분 | S | P2 대안 |
| mecab-ko + textsearch_ko | (유지보수 상태 미확인) | 형태소 기반이라 품질 좋지만 빌드·유지 부담 | M | P3 |
| pgvector + bge-m3 / KURE-v1 | pgvector(PostgreSQL 라이선스), bge-m3(MIT), KURE-v1(MIT, bge-m3 한국어 검색 파인튜닝, 1024차원) https://huggingface.co/nlpai-lab/KURE-v1 | "엄마랑 병원 얘기한 통화"처럼 뜻으로 찾기. 요약·대본 청크 임베딩. GPU 1~2GB 또는 CPU로도 가능(소량) | M | P2 |
| Langfuse | https://langfuse.com (MIT, ee 폴더 제외) | LLM 추적. v3는 ClickHouse+Redis+S3 필요, 최소 RAM 16GB급 → **1인 앱엔 과함** | M | P3 |
| 간단 지표(권장) | Postgres 테이블 | 단계별 시간(업로드→STT→요약), 실패 사유, 오디오 길이 대비 처리시간, 요약 토큰 수. 기존 디스코드 관측(fleet-monitor)으로 일일 보고 | S | **P1** |
| 저장 암호화 | LUKS(디스크) / pgcrypto / age(파일) | 통화 음성은 민감정보. 최소: 디스크 암호화 + 백업 암호화(restic 기본) + 음성 원본 보존기간 정책(예: 요약 후 90일 뒤 원본 삭제, 대본은 유지) | S~M | P2 |

## 5. 다음 버전 기능 아이디어

| 기능 | 내용 | 근거(참고 서비스) | 노력 | 우선 |
|---|---|---|---|---|
| 연락처별 통화 이력 | 연락처 화면에 통화 목록·요약 타임라인·"지난번 약속" | 에이닷·익시오 | M | **P1** |
| 대본 검색 | PGroonga로 전체 대본·요약 검색, 결과에서 해당 구간 바로 재생 | 에이닷 키워드 검색 | M | **P1** |
| 할 일/약속 추출 + 리마인더 | Claude가 JSON으로 {할 일, 기한, 상대} 추출 → 앱 알림/구글 캘린더 등록 원탭 | 에이닷 할 일, Pixel Call Notes | M | **P1** |
| 말풍선 대본 + 화자 라벨 | "나 / 상대(연락처명)" 말풍선 | 에이닷·클로바노트 | M (2-4 전제) | P2 |
| 오디오 재생 ↔ 대본 동기화 | 세그먼트 타임스탬프로 현재 문장 강조, 탭하면 이동 | 에이닷 말풍선 탭 재생 | M | P2 |
| 주간 다이제스트 | 매주 통화 요약·미완료 할 일·자주 통화한 사람 → 앱 또는 디스코드 | (자체) | S | P2 |
| 통화 유형별 요약 양식 | 가족/업무/예약/민원 등 분류 후 양식 다르게 | 익시오 AI 스마트 요약 | S | P2 |
| 요약 공유 | 안드로이드 공유 시트로 텍스트 공유(카톡 등) | 아이폰 메모 연동 | S | P2 |
| 요약·할 일 편집 | 틀린 요약 수정, 인물 이름 교정 → 단어장에 자동 반영 | 클로바노트 | S~M | P2 |
| "이 통화에 질문하기" | 대본 컨텍스트로 Claude Q&A | Plaud Ask | M | P3 |
| 시맨틱 검색 | pgvector + KURE-v1 | - | M | P3 |

## 6. 추천 Top 10 (적용 순서)
1. **STT 환각·멈춤 설정 정비** — faster-whisper large-v3-turbo + VAD + condition_on_previous_text=False + 반복/블랙리스트 후처리 + 파일당 타임아웃. (S, P1)
2. **단어장 프롬프트** — 연락처 이름·기존 "언급된 사람" 사전을 initial_prompt/hotwords로. (S, P1)
3. **업로드 신뢰성 1단계** — WorkManager 재시도·네트워크 제약·expedited + 서버 해시 중복방지 + 놓친 파일 주기 스캔. (S, P1)
4. **ntfy 자체호스팅 알림** — 요약 완료 시 서버→ntfy 푸시, 폰 절전 예외 등록 + 앱 내 상태 점검 화면. (S, P1)
5. **restic 백업 + 단계별 처리 지표** — DB 덤프·음성 암호화 백업, 복구 리허설, 실패율/처리시간 디스코드 보고. (S, P1)
6. **연락처별 통화 이력 + PGroonga 대본 검색**. (M, P1)
7. **할 일·약속 추출 → 리마인더/캘린더**. (M, P1)
8. **화자 구분** — 먼저 삼성 녹음 모노/스테레오 ffprobe 확인 → pyannote community-1(2명 고정) + 내 목소리 등록으로 "나/상대" 라벨 → 말풍선 대본 + 구간 재생. (M, P2)
9. **한국어 평가셋(실통화 20~30개) 만들고 엔진 A/B** — 베이스 turbo vs ghost613 파인튜닝 vs Qwen3-ASR-1.7B. 이긴 쪽 채택. (M, P2)
10. **주간 다이제스트 + 요약 공유 + 시맨틱 검색(pgvector+KURE-v1)**. (S~M, P2~P3)

## 미확인 목록
- 삼성 기본 통화녹음 m4a의 채널 수(모노/스테레오) — 서버 파일로 확인 필요
- Qwen3-ASR 라이선스·8GB 실측 메모리
- ghost613 모델 라이선스, 전화 음질에서의 실제 개선 폭
- 티로·다글로 세부 기능, 클로바 계열 오픈 STT 모델 존재 여부
- 상대방 고지 의무화 개정 여부
- 각 모델 VRAM 수치(추정치이며 실측 필요)
