# 쫑팔 watchdog — 운영 노트

기존 크론 감시 2개(`canary.py`, `.jjongpal-reaper.sh`)를 하나의 컨테이너형
watchdog 로 통합한 것. 플러그인 구조라 새 감시 항목을 모듈 하나 + 등록 한 줄로
추가할 수 있다. 백엔드와 **의도적으로 분리**(백엔드가 멈춰도 감시는 살아있게)돼 있고
같은 DB(`DATABASE_URL`)에 독립 연결한다. **단일 인스턴스**로만 띄운다.

> 이 watchdog 는 원본들의 임계값·주기·복구 정책을 **그대로 이식**한 것이다. 새 정책을
> 만들지 않았다. 원본이 애매한 부분은 아래 "미해결 질문" 에 적어뒀다.


## 구조

```
pc/watchdog/
  Dockerfile            # python:3.12-slim, PYTHONUNBUFFERED=1
  requirements.txt      # asyncpg, httpx, google-auth
  NOTES.md              # 이 문서
  app/
    main.py             # 엔트리포인트(-m app.main): ctx 구성 → 스케줄러 실행
    config.py           # env 기반 설정(임계값·주기·비밀경로). 전부 env 로 덮어쓰기 가능
    db.py               # asyncpg 풀 래퍼
    state.py            # state.json 영속(전이 플래그·last_run·digest 날짜·공유 kv)
    alerter.py          # FCM 폰 푸시(+선택적 Discord 웹훅)
    scheduler.py        # 중앙 루프 + 상태 전이 알림 + digest 타이밍
    checks/
      base.py           # Check 베이스 + CheckResult/StateCondition/Notification
      registry.py       # ★ 체크 등록 '한 곳' (새 체크는 여기에 추가)
      stt_delay.py      # canary 1·3: 요약/받아쓰기 단계 지연 감지
      all_fail.py       # canary 2: 요약 전부 실패 감지
      login_expiry.py   # canary 6: 헤드리스 claude 인증 만료 감지
      auto_heal.py      # canary 4·5: 멈춘 항목 회수 + 실패 자동 재시도
      reaper.py         # reaper.sh: DB 함수 reap_stuck_processing() 호출
      digest.py         # canary --digest: 매일 아침 현황 요약
```


## 플러그인 구조 — 새 감시 항목 추가하는 법

1. `app/checks/` 에 새 모듈을 만들고 `base.Check` 를 상속한 클래스를 작성한다.
   ```python
   from .base import Check, CheckResult, StateCondition, Notification

   class MyCheck(Check):
       name = "my_check"
       async def run(self, ctx) -> CheckResult:
           res = CheckResult()
           row = await ctx.db.fetchrow_dict("select count(*) as n from ...")
           # (a) 지속 상태 → 스케줄러가 '전이 시에만' 알림
           res.states["my_bad"] = StateCondition(
               active=row["n"] > 10, title="뭔가 밀렸어", lines=["..."], level="warn")
           # (b) 매번 조건 맞으면 즉시 통지
           # res.notifications.append(Notification("한 일", ["..."], "info"))
           res.data = {"n": row["n"]}   # 로그/요약용
           return res
   ```
2. `app/checks/registry.py` 의 `build_checks()` 리스트에 인스턴스를 추가한다(주기 지정).
   ```python
   MyCheck(interval_sec=cfg.canary_interval_sec),
   ```
3. 끝. 스케줄러가 주기마다 실행하고, `states` 는 전이 알림(중복 제거), `notifications`
   는 즉시 발송을 자동 처리한다.

`ctx` 가 제공하는 것: `ctx.cfg`(설정), `ctx.db`(asyncpg 래퍼), `ctx.alerter`(직접 알림용),
`ctx.state`(체크 간 공유 kv: `ctx.state.get/set`).


## 알림 메커니즘

- **폰 FCM 푸시**(원본 canary `notify()` 그대로): `devices` 테이블에서
  `revoked_at IS NULL AND fcm_token IS NOT NULL` 인 기기의 `fcm_token` 을 읽어
  FCM HTTP v1 data-only 메시지(`{type:alert,title,body,level}`)로 푸시. 본문은 폰이
  마크다운을 못 살리므로 `**`, `` ` `` 제거 + 900자 컷. 서비스계정 JSON 으로 토큰 발급.
- **Discord 웹훅**(추가): `JJ_ALERT_WEBHOOK` 이 설정돼 있으면 같은 내용을 텍스트로도 보냄.
  가로줄/표 안 쓰고 헤더+불릿만, 1900자 컷.
  - 참고: 원본 canary 는 `JJ_ALERT_WEBHOOK` env 를 **선언만 하고 실제로는 안 썼다**
    (폰 FCM 만 사용). 과제 요구("Discord 웹훅/FCM 재사용")를 맞추려고 웹훅도 붙였다.
    웹훅을 안 쓰려면 env 를 비워두면 된다.
- **상태 전이 전용**: 지속 상태(밀림·전부실패·인증끊김)는 나쁨으로 '진입'할 때 1회,
  '해소'될 때 "<제목> — 해소됨" 1회만 알린다(canary `transition()` 로직 이식).
  중복 방지 플래그는 `state.json` 에 저장 → 재시작에도 유지.
- **일회성 통지**: `auto_heal` 의 "자동 복구했어" 는 전이가 아니라 복구가 일어난 **매 실행**
  알린다(원본과 동일).
- **일일요약**: `digest` 는 `WATCHDOG_DIGEST_TIME`(기본 09:00)에 하루 1회 발송.
- **reaper 는 알림 없음**: 원본 reaper.sh 가 폰 알림 없이 로그만 남겼으므로 동일하게
  로그에만 남긴다.


## 환경변수

필수
- `DATABASE_URL` — 쫑팔 DB 접속 문자열 (원본 canary/reaper 와 동일 DB). **<secret>**

알림/비밀 (코드에 박지 않음, env/마운트로 주입)
- `JJ_ALERT_WEBHOOK` — Discord 웹훅 URL (없으면 웹훅 발송 생략). **<secret>**
- `FIREBASE_PROJECT_ID` — 기본 `jjongpal-app`
- `FIREBASE_CREDENTIALS` — FCM 서비스계정 JSON 경로, 기본 `/secrets/service-account.json`
  (컨테이너에 읽기전용 마운트). **<secret 파일>**

주기(초) — cron 원본과 동일 기본값
- `WATCHDOG_REAPER_INTERVAL_SEC` = 300  (reaper.sh `*/5`)
- `WATCHDOG_CANARY_INTERVAL_SEC` = 900  (canary.py `*/15`; stt_delay·all_fail·auto_heal 공용)
- `WATCHDOG_AUTH_INTERVAL_SEC` = 3600  (canary `AUTH_CHECK_MIN=60분`)
- `WATCHDOG_DIGEST_TIME` = `09:00`  (cron `0 9 * * *`)
- `WATCHDOG_TICK_SEC` = 15  (스케줄러가 due 를 검사하는 간격)

canary 임계값 — 원본 상수 그대로
- `WATCHDOG_SUMMARY_STALL_H` = 3   (SUMMARY_STALL_H)
- `WATCHDOG_TRANSCRIBE_STALL_H` = 1 (TRANSCRIBE_STALL_H)
- `WATCHDOG_STUCK_MIN` = 30  (STUCK_MIN: PROCESSING 이만큼 머물면 회수)
- `WATCHDOG_RETRY_MAX` = 3   (RETRY_MAX: 실패 자동 재시도 상한)
- `WATCHDOG_RETRY_WAIT_MIN` = 30 (RETRY_WAIT_MIN: 재시도 간격)

인증 점검(login_expiry)
- `WATCHDOG_AUTH_ENABLED` = `false` (기본 꺼짐 — 아래 한계 참조)
- `CLAUDE_BIN` = `/home/weplay/.nvm/versions/node/v22.22.0/bin/claude`
- `CLAUDE_CONFIG_DIR` = `/home/weplay/.claude-jjongpal`

기타
- `WATCHDOG_STATE_FILE` = `/data/state.json` (볼륨 마운트 권장 — 재시작 후 알림 중복 방지)
- `WATCHDOG_LOG_LEVEL` = `INFO`


## 각 체크의 이식 내역 (임계값·주기·의미)

### stt_delay (canary 1·3) — 주기 15분
- summary_stall: `transcripts.summary_status='PENDING'` 대기 1건↑ **그리고** 가장 오래된
  게 `SUMMARY_STALL_H`(3)시간 초과 **그리고** 최근 10분 내 새 `summaries` 0건 → 위험(crit).
  - 쓰는 테이블/컬럼: `transcripts(summary_status, processed_at, created_at)`,
    `summaries(created_at)`.
- transcribe_stall: `audio_files.transcript_status='PENDING' AND file_path IS NOT NULL`
  대기 1건↑ **그리고** 가장 오래된 게 `TRANSCRIBE_STALL_H`(1)시간 초과 **그리고**
  최근 20분 내 새 `transcripts` 0건 → 경고(warn).
  - 테이블: `audio_files(transcript_status, file_path, processed_at, uploaded_at)`,
    `transcripts(created_at)`.
- 둘 다 상태 전이 시에만 알림. 감지만 하고 DB 는 안 건드림.

### all_fail (canary 2) — 주기 15분
- 조건: 최근 1시간 `summaries` 성공 0건 **그리고** 최근 1시간 `transcripts` FAILED 3건↑
  **그리고** `transcripts` PENDING 대기 3건↑ → 위험(crit). (대기 조건은 헛알람 방지용)
- 테이블: `summaries(created_at)`, `transcripts(summary_status, processed_at, created_at)`.

### login_expiry (canary 6) — 주기 60분
- 헤드리스 claude 를 1회 호출, 실패 시 8초 뒤 1회 재확인(연속 2회 실패해야 죽음).
  두 오류에 `auth/oauth/expired/401/refresh` 키워드가 있을 때만 '인증 죽음'으로 판정.
  상태 전이 시에만 알림. 마지막 결과는 state(`auth_ok`)에 저장 → digest 가 읽음.
- **이식 한계(중요)**: `python:3.12-slim` 컨테이너엔 claude CLI/node 가 없다. 기본값
  `WATCHDOG_AUTH_ENABLED=false` 로 **꺼져 있고**, 켜려면 호스트의 claude 바이너리 + node
  런타임 + `CLAUDE_CONFIG_DIR` 을 컨테이너에 마운트해야 한다. 바이너리가 없으면 알림 없이
  건너뛴다(로그만). "미해결 질문" 참조.

### auto_heal (canary 4·5) — 주기 15분
- 회수(4): `audio_files` PROCESSING 이 `STUCK_MIN`(30)분 초과 → PENDING(+processed_at=now);
  `transcripts` PROCESSING 이 30분 초과 → PENDING. 회수 건수 집계.
- 재시도(5): `transcripts` summary_status='FAILED' **그리고** `retry_count < RETRY_MAX`(3)
  **그리고** 마지막 처리가 `RETRY_WAIT_MIN`(30)분 초과 **그리고** `error_message` 에
  `auth` 없음 → PENDING, `retry_count+1`, error 초기화. (인증 오류는 전체 장애라 제외)
- 복구가 일어나면 "자동 복구했어"(info)를 **매 실행** 알림(전이 아님).
- 테이블: `audio_files(transcript_status, processed_at, uploaded_at)`,
  `transcripts(summary_status, retry_count, error_message, processed_at, created_at)`.

### reaper (reaper.sh) — 주기 5분
- 원본과 동일하게 DB 함수 호출:
  `SELECT now(), scope, requeued, failed FROM public.reap_stuck_processing() WHERE requeued>0 OR failed>0`.
  회수/실패 판정 로직 전체가 이 DB 함수 안에 있다(아래 주의). 활동은 로그/데이터로만 남김(알림 없음).

### digest (canary --digest) — 매일 09:00
- 오늘 올라온 통화/완료 요약 수, 요약 대기·실패·재시도소진(`retry_count>=RETRY_MAX`),
  받아쓰기 대기·실패, 인증 상태를 한 장으로 발송(dead_sum 있으면 warn, 아니면 ok).
- 테이블: `audio_files`, `summaries`, `transcripts(retry_count)`.


## 미해결 질문 / 주의 (추측하지 않고 남겨둔 것)

1. **`public.reap_stuck_processing()` 본문이 레포에 없다.** 이 DB 함수는 라이브 DB 에만
   존재하고 `pc/postgres/init/*.sql`·홈 디렉터리·bash 히스토리 어디에도 정의가 없다
   (reaper.sh 가 유일한 참조처). 그래서 내부('stuck' 기준, requeue vs fail 경계, 재시도
   상한)를 **추측 재구현하지 않고** 원본처럼 같은 함수를 그대로 호출한다. → 의미는 100%
   보존되지만, 함수 본문을 버전관리에 넣고 싶으면 라이브 DB 에서 `pg_get_functiondef` 로
   떠서 init SQL 에 추가하는 게 좋다(이번 작업 범위 밖: DB/컨테이너 불건드림 원칙).
2. **`transcripts.retry_count` 컬럼도 레포 스키마에 없다.** `01-schema.sql` 의 transcripts
   엔 `retry_count` 가 없는데 canary 가 이미 쓰고 있다(= 라이브 DB 에 마이그레이션으로
   추가됨). 이식도 그대로 `retry_count` 를 쓴다. init SQL 에 반영돼 있지 않은 점만 참고.
3. **reaper 와 auto_heal 의 '멈춘 PROCESSING 회수' 가 겹친다.** 둘 다 운영에서 돌던 거라
   원본 보존을 위해 **둘 다** 이식했다. reaper(5분, DB 함수)와 auto_heal(15분, STUCK_MIN=30)
   은 임계값/주체가 다를 수 있다. 중복이 부담되면 나중에 하나로 합치면 됨(정책 판단 필요 →
   임의로 안 없앰).
4. **login_expiry 는 컨테이너에서 기본 꺼짐.** 슬림 이미지에 claude/node 가 없어서다.
   컨테이너에서 인증 감시가 필요하면 (a) 호스트 바이너리+node+config 마운트 후
   `WATCHDOG_AUTH_ENABLED=true`, 또는 (b) 인증 점검만 호스트 크론으로 남기는 선택지가 있다.
   어느 쪽이 맞는지는 사용자 결정 필요.
5. **DATABASE_URL 출처**: 원본 canary 는 `agent-worker/.env` 에서, reaper 는 `pc/.env` 에서
   읽었고 monitor/.env 에도 DATABASE_URL 이 있다. watchdog 는 컨테이너 env 로 주입받는다
   (어느 값을 쓸지는 배포 시 지정). 세 군데 값이 같은 DB 를 가리키는지 배포 전 확인 권장.

> 배포(빌드/실행/compose 등록)는 이 작업 범위가 아니다 — 운영 반영은 트론에게.
