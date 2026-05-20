# jjongpal-app

쫑팔이삼촌 — 능동형 비서 안드로이드 앱 (v0.2).

폰의 알림 / 통화 데이터를 캡처해서 폰 안에서 즉시 분류하고, 통화 같은 깊은 처리는 본인 PC 에서 마무리한다. 사용자는 자기 데이터를 자기 폰에서 본다. 어드민은 웹에서 전체를 본다.

## 저장소 구조

```
jjongpal-app/
├── app/         안드로이드 앱 (Kotlin)
├── pc/          PC 파이프라인 — 도커 컴포즈 + 워커들
├── docs/        결정 로그 / 단계별 액션 / 명세
├── scripts/     운영 스크립트 (계정 생성·디바이스 폐기·디비 백업)
└── README.md    (이 파일)
```

## 문서 입구

- [docs/00_VISION.md](docs/00_VISION.md) — 정체성·원칙
- [docs/10_DECISIONS.md](docs/10_DECISIONS.md) — 결정 로그 (왜 그렇게 정했는지)
- [docs/20_PHASES.md](docs/20_PHASES.md) — 단계별 진행 (언제 / 무엇을)
- [docs/30_PC_PIPELINE.md](docs/30_PC_PIPELINE.md) — PC 측 명세
- [docs/40_APP_SPEC.md](docs/40_APP_SPEC.md) — 앱 측 명세

## 외부 에이전트 작업 시 핵심 사실

다른 에이전트 (Claude Code 등) 가 이 시스템 코드를 작성·수정할 때 알아야 할 것 — 자세한 건 `docs/10_DECISIONS.md` 의 §외부 에이전트 핸드오프 섹션 참조.

1. 사용자 식별자는 일반 ID (UUID 또는 자동 증가). 'me' / 'wife' 같은 의미 단어 어디에도 박지 않는다.
2. 사용자는 자기 데이터만 본다. 공유 모델 없다.
3. 어드민 (admin) 역할만 다른 사용자 데이터를 본다. 웹 화면에서만.
4. 한 폰 = 한 사용자.
5. 외부 LLM API (Gemini / OpenAI) 호출 X. 본인 PC 의 whisper.cpp + Claude Code 만.
6. 폰에서 직접 Anthropic API 호출 X. 폰은 PC 와만 통신.
7. 사용자 시각의 진실은 서버 시각이다.
8. 통화 음성 파일은 텍스트 변환 직후 즉시 삭제. transcript 만 보관.
9. 푸시 페이로드에 본문 박지 않는다. "갱신됨" 트리거만. 본문은 폰이 PC 에서 다시 가져온다.
10. 인증은 이메일 + 비밀번호 로그인. 빌드 시점 토큰 박기 X.
