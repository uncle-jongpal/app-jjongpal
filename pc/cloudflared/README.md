# 클라우드플레어 터널 (Cloudflare Tunnel)

외부 (폰들) ↔ 본인 PC 의 영구 보안 통로.

## 사전 조건

- 클라우드플레어에 등록된 도메인 (예: `uncle-jongpal.com`)
- 본인 PC 에 `cloudflared` 설치 (Debian/Ubuntu: `sudo apt install cloudflared` 또는 공식 deb)

## 셋업 절차

```bash
# 1. 클라우드플레어 계정 로그인
cloudflared tunnel login

# 2. 터널 생성 (한 번만)
cloudflared tunnel create jjongpal
# → 출력에 터널 ID 가 나오고 자격증명 JSON 이 ~/.cloudflared/<ID>.json 에 생성

# 3. DNS 라우트 추가 — 본인이 결정한 서브도메인
#    (예: samchon.uncle-jongpal.com 또는 app.uncle-jongpal.com)
cloudflared tunnel route dns jjongpal samchon.uncle-jongpal.com

# 4. 설정 파일 작성 (이 디렉토리의 config.example.yml 참조)
cp config.example.yml ~/.cloudflared/config.yml
# → 안의 ${USER} / ${TUNNEL_ID} / ${DOMAIN} 부분을 본인 값으로 수정

# 5. systemd 등록 + 부팅 시 자동 시작
sudo cloudflared service install
sudo systemctl enable cloudflared
sudo systemctl start  cloudflared
sudo systemctl status cloudflared

# 6. 외부에서 헬스 확인
curl https://samchon.uncle-jongpal.com/health
# → "ok" 가 떠야 정상
```

## 동작

- 클라우드플레어가 인터넷 측에서 HTTPS (보안 웹 통신) 종단
- 터널이 본인 PC 의 nginx (`127.0.0.1:8080`) 로 트래픽 전달
- 외부에 본인 PC 의 IP 노출 X. 방화벽에 포트 열 필요 X.

## 운영 명령

```bash
# 상태
sudo systemctl status cloudflared

# 로그
sudo journalctl -u cloudflared -f

# 재시작
sudo systemctl restart cloudflared

# 터널 목록
cloudflared tunnel list

# DNS 라우트 목록
cloudflared tunnel route ip show
```
