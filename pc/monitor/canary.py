#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""쫑팔 파이프라인 감시 (canary)
- 단계 지연 / 전부 실패 / 받아쓰기 지연 / 인증 만료 감지
- 멈춘 항목 자동 회수, 실패 항목 자동 재시도
- 상태가 바뀔 때만 디스코드 알림(중복 방지), 매일 아침 현황 요약
사용: canary.py            (15분마다 크론)
      canary.py --digest   (매일 아침 1회)
"""
import os, sys, json, time, asyncio, subprocess, urllib.request, datetime, traceback

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(HERE, "..", "agent-worker", ".env")
STATE_FILE = os.path.join(HERE, "state.json")
WEBHOOK = os.environ.get("JJ_ALERT_WEBHOOK", "")
FCM_PROJECT = os.environ.get("FIREBASE_PROJECT_ID", "jjongpal-app")
FCM_CRED = os.environ.get("FIREBASE_CREDENTIALS", "/home/weplay/work/prd/app-jjongpal/pc/fcm-pusher/service-account.json")
FCM_ENDPOINT = f"https://fcm.googleapis.com/v1/projects/{FCM_PROJECT}/messages:send"
_fcm_creds = None
_PHONE_TOKENS = []

def _fcm_access_token():
    global _fcm_creds
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request as GReq
    if _fcm_creds is None:
        _fcm_creds = service_account.Credentials.from_service_account_file(
            FCM_CRED, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    if not _fcm_creds.valid:
        _fcm_creds.refresh(GReq())
    return _fcm_creds.token
CLAUDE_BIN = "/home/weplay/.nvm/versions/node/v22.22.0/bin/claude"
CLAUDE_CFG = "/home/weplay/.claude-jjongpal"

# 임계값
SUMMARY_STALL_H   = 3     # 요약 대기가 이만큼 밀리면 위험
TRANSCRIBE_STALL_H= 1     # 받아쓰기 대기 지연
STUCK_MIN         = 30    # '처리중'에 이만큼 머물면 멈춘 것으로 보고 회수
RETRY_MAX         = 3     # 실패 자동 재시도 횟수
RETRY_WAIT_MIN    = 30    # 재시도 간격
AUTH_CHECK_MIN    = 60    # 인증 점검 주기

def load_env():
    cfg = {}
    with open(ENV_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    return cfg

def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f: return json.load(f)
    except Exception: return {}

def save_state(s):
    with open(STATE_FILE, "w", encoding="utf-8") as f: json.dump(s, f, ensure_ascii=False, indent=1)

def notify(title, lines, level="warn"):
    """쫑팔 앱이 설치된 폰으로 시스템 알림을 직접 푸시 (베젤 채널 안 거침)."""
    # 폰 알림은 마크다운을 못 살리므로 강조 기호(**, `)는 떼어낸다
    body_text = "\n".join(lines)[:900].replace("**", "").replace("`", "")
    if not _PHONE_TOKENS:
        print("[폰토큰없음]", title, "|", body_text[:80]); return
    import requests
    try:
        tok = _fcm_access_token()
    except Exception as e:
        print("FCM 토큰 발급 실패:", e); return
    for t in _PHONE_TOKENS:
        msg = {"message": {"token": t, "data": {
            "type": "alert",
            "title": f"쫑팔 · {title}",
            "body": body_text,
            "level": level,
        }}}
        try:
            r = requests.post(FCM_ENDPOINT, json=msg, timeout=15,
                headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
            if r.status_code >= 300:
                print("폰 푸시 실패", r.status_code, r.text[:200])
        except Exception as e:
            print("폰 푸시 오류:", e)

def _auth_once():
    env = dict(os.environ); env["CLAUDE_CONFIG_DIR"] = CLAUDE_CFG
    try:
        p = subprocess.run([CLAUDE_BIN, "--print", "--output-format", "json"],
                           input="hi", capture_output=True, text=True, timeout=90, env=env)
        d = json.loads(p.stdout or "{}")
        if d.get("is_error"):
            return False, str(d.get("result") or d.get("subtype") or "unknown")[:200]
        return True, ""
    except Exception as e:
        return False, str(e)[:200]

def check_auth():
    """인증 살아있는지 — 일시적 오류에 흔들리지 않게 연속 2회 실패해야 죽음으로 본다."""
    ok, err = _auth_once()
    if ok:
        return True, ""
    # 한 번 실패 → 8초 뒤 재확인 (일시적 blip 무시)
    ok2, err2 = _auth_once()
    if ok2:
        return True, ""
    # 진짜 인증 문제만 걸러냄 (auth/oauth/expired 키워드), 그 외는 일시 오류로 보고 알람 안 함
    combined = (err + " " + err2).lower()
    if any(k in combined for k in ("auth", "oauth", "expired", "401", "refresh")):
        return False, err2 or err
    return True, ""  # 일시적 실패(claude exit 등)는 인증죽음으로 안 침

async def main():
    import asyncpg
    cfg = load_env()
    conn = await asyncpg.connect(cfg["DATABASE_URL"])
    global _PHONE_TOKENS
    try:
        rows = await conn.fetch("SELECT fcm_token FROM devices WHERE revoked_at IS NULL AND fcm_token IS NOT NULL")
        _PHONE_TOKENS = [r["fcm_token"] for r in rows]
    except Exception as e:
        print("폰 토큰 조회 실패:", e)
    st = load_state()
    now = time.time()
    alerts, heals = [], []
    digest = "--digest" in sys.argv

    async def one(q, *a):
        r = await conn.fetchrow(q, *a); return dict(r) if r else {}

    # ── 1. 요약 단계 지연 (윗단계는 일했는데 아랫단계가 성공 없음)
    s = await one("""
        select count(*) as waiting,
               coalesce(extract(epoch from now()-min(coalesce(processed_at, created_at)))/3600,0) as oldest_h
        from transcripts where summary_status='PENDING'""")
    # 최근 10분 안에 요약이 하나라도 나왔으면 '밀림'이 아니라 '처리 중'으로 본다
    prog = await one("select count(*) as n from summaries where created_at > now()-interval '10 minutes'")
    summary_stall = s["waiting"] > 0 and s["oldest_h"] > SUMMARY_STALL_H and prog["n"] == 0

    # ── 2. 전부 실패 (최근 1시간 성공 0 + 실패 3건 이상)
    f = await one("""
        select (select count(*) from summaries where created_at > now()-interval '1 hour') as ok,
               (select count(*) from transcripts
                 where summary_status='FAILED' and coalesce(processed_at,created_at) > now()-interval '1 hour') as fail""")
    # 대기 중 작업이 실제로 있는데 성공이 0 + 실패가 쌓일 때만 "전부 실패"로 본다.
    # (새 통화가 없어 성공 0인데 옛 실패 몇 건 있는 상황은 헛알람이므로 제외)
    all_failing = f["ok"] == 0 and f["fail"] >= 3 and s["waiting"] >= 3

    # ── 3. 받아쓰기 지연
    t = await one("""
        select count(*) as waiting,
               coalesce(extract(epoch from now()-min(coalesce(processed_at, uploaded_at)))/3600,0) as oldest_h
        from audio_files where transcript_status='PENDING' and file_path is not null""")
    tprog = await one("select count(*) as n from transcripts where created_at > now()-interval '20 minutes'")
    transcribe_stall = t["waiting"] > 0 and t["oldest_h"] > TRANSCRIBE_STALL_H and tprog["n"] == 0

    # ── 4. 멈춘 것 자동 회수
    a_stuck = await conn.execute(f"""
        update audio_files set transcript_status='PENDING', processed_at=now()
        where transcript_status='PROCESSING' and coalesce(processed_at, uploaded_at) < now()-interval '{STUCK_MIN} minutes'""")
    t_stuck = await conn.execute(f"""
        update transcripts set summary_status='PENDING', processed_at=now()
        where summary_status='PROCESSING' and coalesce(processed_at, created_at) < now()-interval '{STUCK_MIN} minutes'""")
    n_a = int(a_stuck.split()[-1]); n_t = int(t_stuck.split()[-1])
    if n_a or n_t: heals.append(f"멈춰 있던 항목 회수 — 받아쓰기 {n_a}건 · 요약 {n_t}건")

    # ── 5. 실패 자동 재시도 (인증 오류는 제외: 전체 장애라 재시도 낭비)
    r = await conn.execute(f"""
        update transcripts
           set summary_status='PENDING', retry_count=retry_count+1, error_message=null, processed_at=now()
         where summary_status='FAILED'
           and retry_count < {RETRY_MAX}
           and coalesce(processed_at, created_at) < now()-interval '{RETRY_WAIT_MIN} minutes'
           and coalesce(error_message,'') not ilike '%auth%'""")
    n_r = int(r.split()[-1])
    if n_r: heals.append(f"실패 항목 자동 재시도 투입 — {n_r}건")

    # ── 6. 인증 점검 (1시간에 한 번)
    auth_ok, auth_err = True, ""
    if digest or now - st.get("auth_checked_at", 0) > AUTH_CHECK_MIN*60:
        auth_ok, auth_err = check_auth()
        st["auth_checked_at"] = now

    # ── 상태 전이 시에만 알림
    def transition(key, bad, title, lines, level="crit"):
        was = st.get(key, False)
        if bad and not was:
            notify(title, lines, level); st[key] = True
        elif not bad and was:
            notify(f"{title} — 해소됨", ["정상으로 돌아왔어."], "ok"); st[key] = False

    transition("auth_dead", not auth_ok, "인증이 끊겼어",
               [f"헤드리스 클로드 호출 실패 → **요약이 전부 실패**하게 돼.",
                f"오류: `{auth_err}`",
                "→ 운영서버에서 `CLAUDE_CONFIG_DIR=/home/weplay/.claude-jjongpal claude` 로 재로그인 필요"])
    transition("summary_stall", summary_stall, "요약이 밀려 있어",
               [f"대기 **{s['waiting']}건**, 가장 오래된 게 **{s['oldest_h']:.1f}시간째**.",
                "워커가 살아 있어도 결과가 안 나오는 상황일 수 있어."])
    transition("all_failing", all_failing, "요약이 전부 실패 중",
               [f"최근 1시간 성공 **0건**, 실패 **{f['fail']}건**.",
                "인증 만료·프롬프트 오류·모델 응답 문제 가능성."])
    transition("transcribe_stall", transcribe_stall, "받아쓰기가 밀려 있어",
               [f"대기 **{t['waiting']}건**, 가장 오래된 게 **{t['oldest_h']:.1f}시간째**."], "warn")

    if heals:
        notify("자동 복구했어", heals, "info")

    # ── 일일 요약
    if digest:
        d = await one("""
          select (select count(*) from audio_files where uploaded_at::date=current_date) as today_audio,
                 (select count(*) from summaries  where created_at::date=current_date) as today_sum,
                 (select count(*) from transcripts where summary_status='PENDING') as pend_sum,
                 (select count(*) from transcripts where summary_status='FAILED') as fail_sum,
                 (select count(*) from transcripts where summary_status='FAILED' and retry_count>=%d) as dead_sum,
                 (select count(*) from audio_files where transcript_status='PENDING' and file_path is not null) as pend_tr,
                 (select count(*) from audio_files where transcript_status='FAILED') as fail_tr
        """ % RETRY_MAX)
        lines = [
            f"오늘 올라온 통화 **{d['today_audio']}건** · 요약 완료 **{d['today_sum']}건**",
            f"요약 대기 {d['pend_sum']} · 실패 {d['fail_sum']} (재시도 소진 **{d['dead_sum']}**)",
            f"받아쓰기 대기 {d['pend_tr']} · 실패 {d['fail_tr']}",
            f"인증 상태: {'정상' if auth_ok else '**끊김**'}",
        ]
        notify("오늘의 현황", lines, "ok" if auth_ok and d["dead_sum"] == 0 else "warn")

    save_state(st)
    await conn.close()
    print(json.dumps({"summary_stall": summary_stall, "all_failing": all_failing,
                      "transcribe_stall": transcribe_stall, "auth_ok": auth_ok,
                      "healed_audio": n_a, "healed_summary": n_t, "retried": n_r}, ensure_ascii=False))

if __name__ == "__main__":
    try: asyncio.run(main())
    except Exception:
        traceback.print_exc()
        notify("감시 스크립트 자체가 실패했어", [f"```{traceback.format_exc()[-1200:]}```"], "crit")
        sys.exit(1)
