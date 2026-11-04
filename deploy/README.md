# AWS 1차 배포 안내

EC2 한 대에 Docker Compose 로 PostgreSQL 16 과 Spring 백엔드를 올린다. 영상 중계용 coturn 은 준비만 되어 있다.
AI 모델은 서버에 올리지 않는다 ([MODELS.md](MODELS.md)).

## 구성

```
deploy/
  docker-compose.yml   db + backend (+ coturn, --profile turn)
  .env.example         → .env 로 복사해 값 입력 (git 제외)
  coturn/turnserver.conf
fall_detection_backend/Dockerfile      Gradle 빌드 → JRE 17 이미지
docs/backend/schema.sql                첫 실행 시 자동 적용
docs/backend/migrations/               기존 DB 에만 수동 적용
```

## 1. EC2 준비

- Ubuntu 22.04 이상, 메모리 2GB 이상 권장 (t3.small). 1GB 인스턴스는 Gradle 빌드 중 메모리가 부족할 수 있다. 그 경우 아래 "로컬에서 빌드" 를 쓴다.
- 보안 그룹 인바운드

| 포트 | 프로토콜 | 용도 |
|---|---|---|
| 22 | TCP | SSH (내 IP 만) |
| 8080 | TCP | 백엔드 API (`BACKEND_PORT`) |
| 3478 | TCP+UDP | TURN (profile turn 사용 시) |
| 49160–49200 | UDP | TURN 중계 (profile turn 사용 시) |

- 탄력적 IP 를 붙여 두면 앱 서버 주소를 매번 바꾸지 않아도 된다.

```sh
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER   # 다시 로그인
```

## 2. 코드 올리기

현재 git 저장소에는 `fall_detection_backend/` 와 `CareGuardCamera/` 가 커밋되어 있지 않다. 커밋 후 `git clone` 하거나, 아래처럼 필요한 폴더만 복사한다.

```sh
# 내 PC 에서
cd fall-detection
rsync -az --delete \
  --exclude '.gradle' --exclude 'build' --exclude '.local' --exclude 'bin' \
  --exclude 'application-local.properties' \
  deploy docs fall_detection_backend \
  ubuntu@<EC2-IP>:~/fall-detection/
```

## 3. 설정과 실행

```sh
# EC2 에서
cd ~/fall-detection/deploy
cp .env.example .env
nano .env            # DB_PASSWORD 필수. 영상 기능을 쓰면 LIVE 두 값 true
docker compose up -d --build
docker compose ps    # backend 가 healthy 가 될 때까지 1~2분
curl "http://127.0.0.1:8080/api/patients?offset=0&limit=1"   # [] 가 나오면 정상
```

처음 뜰 때 빈 DB 에 `docs/backend/schema.sql` 이 자동 적용된다. 이미 데이터가 있는 DB 를 옮겨 오는 경우에는 `docs/backend/migrations/*.sql` 을 순서대로 수동 적용한다.

```sh
docker compose exec -T db psql -U careguard -d fall_detection < ../docs/backend/migrations/20261004-live-privacy-revision.sql
```

## 4. 앱 연결

보호자 앱과 카메라 앱의 서버 설정에서 `http://<EC2-IP>:8080` 을 저장한다. 앱 기본값은 아직 에뮬레이터용(`http://10.0.2.2:8080`) 이므로 릴리스 빌드를 만들 때 기본값을 바꾼다.

## 5. 운영 명령

```sh
docker compose logs -f backend          # 로그
docker compose up -d --build backend    # 코드 갱신 후 재배포
docker compose exec db pg_dump -U careguard fall_detection | gzip > backup-$(date +%F).sql.gz   # 백업
docker compose down                     # 중지 (DB 볼륨 유지)
docker compose down -v                  # DB 까지 삭제. 되돌릴 수 없음
```

## 로컬에서 빌드해서 올리기 (저사양 인스턴스)

```sh
# 내 PC
cd fall_detection_backend && ./gradlew bootJar -x test
docker build -t careguard-backend .     # Dockerfile 이 다시 빌드하므로 메모리가 넉넉한 PC 에서 수행
docker save careguard-backend | gzip | ssh ubuntu@<EC2-IP> 'gunzip | docker load'
```
그 다음 EC2 의 `docker-compose.yml` 에서 `backend.build` 를 `image: careguard-backend` 로 바꾼다.

## 영상 중계 (TURN)

같은 Wi-Fi 가 아닌 폰끼리 WebRTC 영상이 붙으려면 TURN 서버가 필요하다. `.env` 에 `TURN_PASSWORD`, `PUBLIC_IP` 를 채우고 `docker compose --profile turn up -d` 로 켠다.
단, 앱은 아직 ICE 서버 목록을 비워 둔 채 연결한다 (`WebRtcPeer.kt`). 앱이 서버에서 TURN 주소와 자격을 받아 쓰도록 하는 작업이 선행되어야 실제로 동작한다.

## 아직 안 된 것 (배포 전에 결정 필요)

1. 인증이 없다. 지금 API 는 누구나 호출할 수 있고 환자 데이터가 들어 있다. 공개 인터넷에 열기 전에 최소한 공유 비밀 헤더나 보호자 로그인이 필요하다. 1차 시험에서는 보안 그룹의 8080 인바운드를 시험 참가자의 통신사 대역이 아닌 "내 IP" 로 좁히는 것이 그나마 안전하지만 휴대폰 데이터망에서는 쓸 수 없다.
2. HTTPS 가 없다. 도메인이 있으면 Caddy 나 nginx 로 Let's Encrypt 인증서를 앞단에 둔다. 앱은 현재 평문 HTTP 를 허용하도록 설정되어 있다.
3. 라이브 API 의 LAN 전용 제한은 `CAREGUARD_LIVE_ALLOW_REMOTE=true` 로 풀어야 EC2 에서 동작한다. 보호자 UUID allowlist 를 함께 쓰는 것을 권장한다.
4. 앱 쪽: 서버 주소 기본값, 릴리스 서명, APK 크기(약 160MB) 정리, TURN 연동.
5. 푸시 알림(FCM) 과 심각 이벤트의 긴급 상황 자동 생성은 아직 구현되지 않았다.
