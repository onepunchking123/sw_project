# 백엔드 연결 (개발용)

보호자 화면은 `BackendRepository`의 HTTP 응답을 표시한다. 서버 오류를 가짜 데이터로 대체하지 않는다. 서버 주소와 선택한 환자·개발 보호자 ID는 앱에 저장하며, 화면 진입과 저장 후 서버를 다시 조회한다.

## 실행

1. PostgreSQL에 `../docs/backend/schema.sql`을 적용하고 백엔드를 실행한다. DB 준비 방법은 `../fall_detection_backend/README.md`를 따른다.

```sh
cd ../fall_detection_backend
./gradlew bootRun
```

2. Android 에뮬레이터에서 앱을 실행한다. 기본 서버 주소는 `http://10.0.2.2:8080`이다.
3. 보호자 역할을 선택하고 환자 정보 → 비상 연락처 → 카메라 연결을 진행한다. 기존 환자는 하단 **서버 설정** 또는 설정 탭의 **서버 연결**에서 서버 주소를 저장한 뒤 선택할 수 있다.
4. 카메라 역할을 선택한 기기는 서버 주소를 확인한 뒤 **연결 코드 발급** 버튼을 탭한다. 화면에 표시된 8자리 연결 코드를 보호자 앱에서 같은 환자에 입력하면 카메라가 환자에 연결된다.
5. USB 실기기는 연결된 기기마다 `adb -s <serial> reverse tcp:8080 tcp:8080`을 실행한 뒤 앱 서버 주소를 `http://127.0.0.1:8080`으로 설정한다.

```sh
cd ../CareGuardCamera
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## 연결된 화면

- 환자 정보 조회·등록·수정, 비상 연락처 추가·수정·삭제
- 8자리 코드 기반 카메라 연결·목록 조회·이름 수정·연결 해제, 프라이버시 설정
- 보호자 알림 설정 저장, 긴급 상황 조회·대응 중 표시·종료
- 수동 이상상황 작성·수정·삭제와 AI 감지 조회
- AI 감지/수동 기록 각각의 대응 메모 조회·추가·본문 수정·삭제
- 서울 시간 기준 새벽·아침·오후·저녁 타임라인과 서버 집계 월별 캘린더
- 저장된 주간 리포트 조회; 데이터가 없으면 빈 상태 표시

캘린더는 AI 감지와 수동 기록의 발생 건수를 집계한다. 후속 대응 메모는 제외한다. 0건 무색, 1–2건 연함, 3–5건 중간, 6건 이상 진함이다.

실제 로그인 대신 개발용 보호자 프로필을 생성한다. 카메라 인증은 앱에 저장한 64자리 토큰으로 처리한다. 동일 LAN 개발용 영상 연결은 [실시간 MVP 안내](LIVE_VIDEO_MVP.md)를 따른다. QR 이미지 생성/스캔과 푸시 발송은 별도 구현 대상이다. 연결 해제한 카메라의 재연결은 현재 범위에서 지원하지 않는다.

카메라 연결 코드는 5분 동안 유효하다. 카메라 앱은 연결 대기 화면에서 3초마다 페어링 상태를 확인하고, 라이브 화면에서는 15초마다 heartbeat를 보낸다. 보호자 앱의 온라인 표시는 서버가 기록한 최근 heartbeat가 60초 이내일 때만 온라인으로 본다. 온라인 상태는 영상 수신 성공을 뜻하지 않는다.

## 검증

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline
cd ../fall_detection_backend
python3 scripts/test-postgres.py
# 실행 중인 Android 에뮬레이터 필요
python3 scripts/test-android-api.py
```

마지막 명령은 기존 DB를 사용하지 않고 임시 PostgreSQL과 Spring 서버를 실행한다. 에뮬레이터에서 보호자 CRUD 저장소 테스트, 카메라 앱 등록 → 코드 발급 → 보호자 코드 입력 → heartbeat → unlink 흐름, 카메라 역할 UI 스모크 흐름을 실제 HTTP로 검증한다.

카메라 페어링 단계 검증 기록 (실시간 영상 구현 이전, 2026-10-04): 백엔드 14개 테스트, Android unit 17개, lint, debug APK/test APK 빌드, 실제 에뮬레이터 instrumentation 6개가 모두 통과했다. 실제 에뮬레이터 검증은 credential 테스트 3개, 보호자 CRUD 1개, 카메라 페어링 저장소 1개, 카메라 UI 스모크 1개로 구성된다. UI 스모크는 카메라 역할에서 사용자가 **연결 코드 발급**을 탭해 코드를 발급하고, 보호자 코드 입력 후 paired 화면으로 전환되며, 거치 안내를 거쳐 live 화면에서 heartbeat가 기록되는 흐름을 확인했다. 이후 HOME으로 16초 동안 백그라운드에 있을 때 heartbeat timestamp가 변하지 않고, 같은 task를 다시 foreground로 올리면 heartbeat가 갱신되는 것까지 검증했다. 실물 2대 기기 연결은 테스트하지 않았다. 이 기록은 페어링 단계의 결과다. 이후 실시간 영상 구현/검증은 [실시간 MVP 안내](LIVE_VIDEO_MVP.md) 및 실행 기록을 따른다.
