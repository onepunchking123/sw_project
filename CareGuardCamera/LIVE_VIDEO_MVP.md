# 실시간 영상 · 낙상 감지 개발 MVP

## 범위와 상태

카메라 앱이 화면에 열린 동안 한 개 CameraX 소스로 촬영하고, 휴대폰의 YOLO pose + GRU 모델이 분석한다. 보호자 영상 연결 여부와 감지는 독립적이다. 영상은 WebRTC로 전달하고, 서버는 연결 제어와 감지 이벤트 메타데이터를 처리한다. 실시간 영상은 녹화하지 않는다. 예외는 아래 [낙상 긴급 상황과 현장 클립](#낙상-긴급-상황과-현장-클립)의 10초 클립 하나다.

로컬 MVP 구현 및 자동 검증을 완료했다. Android 단위27개, 모델/WebRTC 에뮬레이터6개, live API 통합2개, 카메라 UI1개, backend20개, Python 모델9개가 통과했다. Android lint/APK 및 backend bootJar도 성공했다. 상세 결과는 `../.omx/reviews/live-video-execution.md`에 기록했다. 에뮬레이터 검증은 실제 휴대폰 두 대의 성능·발열·네트워크 검증을 대체하지 않는다.

## 개발 실행

1. 기존 DB 설정은 백엔드 README를 따른다. 기존 DB에는 `../docs/backend/migrations/20261004-live-privacy-revision.sql`을 먼저 적용한다(개인정보 설정 버전 열 추가). 이 작업에서는 기존 DB에 자동 적용하지 않았다. 신규 임시 DB 검증에는 새 스키마를 적용했다. 개발 서버의 live API는 기본 비활성이다. 동일한 신뢰할 수 있는 LAN에서만 명시적으로 활성화한다.

```sh
cd ../fall_detection_backend
CAREGUARD_LIVE_ENABLED=true SERVER_ADDRESS=0.0.0.0 ./gradlew bootRun
```

2. 두 앱의 서버 주소를 같은 서버 PC의 LAN 주소(예: `http://192.168.0.10:8080`)로 설정한다. 에뮬레이터만 사용할 때는 `http://10.0.2.2:8080`이다.
3. 보호자 역할에서 환자와 카메라를 기존 연결 코드 흐름으로 등록한다. 카메라 역할에서 카메라 권한을 허용한 뒤 라이브 화면을 유지한다.
4. 보호자 역할에서 연결된 카메라의 라이브 화면을 연다. 최초 연결과 재접속 상태를 확인한다. 카메라가 백그라운드로 가면 촬영과 송출이 종료된다.
5. 감지 이벤트가 만들어지면 보호자 타임라인에서 확인한다. 쓰러진 채 3초가 지난 낙상(R1)은 긴급 상황으로 이어진다(아래 절).

이 개발 서버는 정식 보호자 로그인을 제공하지 않는다. 인터넷에 공개하지 않는다. 외부 네트워크 지원은 인증, HTTPS, TURN 배포를 포함한 후속 작업이다.

## 낙상 긴급 상황과 현장 클립

1. 카메라 폰이 R1(넘어진 뒤 3초 이상 쓰러져 있음) 이벤트를 올리면 서버가 긴급 상황을 자동으로 연다. 카운트다운 종료 시각은 서버 기준 10초 뒤다. 순간 낙상(FALL/MODEL)만으로는 열지 않는다.
2. 카메라 폰은 사이렌과 함께 10초를 센다. 환자가 **괜찮아요**를 누르면 `POST /api/dev/live/cameras/{cameraId}/events/{clientEventId}/patient-ok`로 긴급 상황을 `PATIENT_OK`로 닫는다. 이 경우 클립을 만들지도, 올리지도 않는다. 서버도 이후 업로드를 409로 거부한다.
3. 취소가 없으면 낙상 전 5초와 후 5초(초당 8장)를 H.264 MP4로 만들어 `PUT .../events/{clientEventId}/clip`(본문 `video/mp4`, 카메라 토큰)으로 올린다. 서버는 `clips` 행을 만들고 감지 이벤트의 `clip_id`에 연결한다.
4. 보호자 앱은 화면에 있는 동안 5초마다 긴급 상황을 확인한다. 카운트다운이 끝났고 아직 닫히지 않은 긴급 상황이 있으면 긴급 화면을 띄우고 `GET /api/dev/live/patients/{patientId}/events/{detectionEventId}/clip?guardianUserId=`로 클립을 반복 재생한다. 클립이 아직 없으면 3초 간격으로 다시 받는다.

- 클립은 카메라 개인정보 설정을 따른다. 원본 모드는 원본, 골격 모드는 골격을 입힌 영상이고, 골격 모드에서 관절이 없는 프레임은 넣지 않는다. 골격만 모드는 클립을 만들지 않는다.
- 저장 위치는 `careguard.storage.dir`(기본 `./data`) 아래 `clips/{patientId}/{detectionEventId}.mp4`이다. 크기 상한은 `careguard.clips.max-bytes`(기본 20MB), 보관 기간은 `careguard.clips.retention-days`(기본 7일)이다. 보관 기간이 지나면 보호자 API가 410을 돌려준다. 파일을 실제로 지우는 정리 작업은 아직 없다.
- 클립 조회는 환자와 연결된 보호자만 할 수 있다. 다만 개발 서버에는 정식 로그인이 없으므로 `guardianUserId`만으로 구분한다. 인터넷에 공개하지 않는다.
- 푸시 알림(FCM)은 아직 없다. 보호자 앱이 꺼져 있으면 경보를 받지 못한다.

## 에뮬레이터 두 대로 실시간 영상 확인

에뮬레이터는 각자 독립된 가상 네트워크(`10.0.2.15`)에 있어서 서로 직접 연결되지 않는다. Mac에 TURN 중계를 띄우고 디버그 빌드에만 주소를 넣는다. 실제 폰 두 대가 같은 Wi-Fi에 있으면 필요 없다.

1. `brew install coturn` 후 `local.properties`(git 제외)에 다음을 넣는다. 주소는 Mac의 LAN IP이다.

```properties
careguard.devTurn.url=turn:192.168.0.23:3478
careguard.devTurn.username=careguard-dev
careguard.devTurn.password=<임의의 값>
```

2. `scripts/dev-turn.sh`로 TURN을 띄우고 디버그 APK를 다시 빌드한다. 값이 비어 있으면 TURN 없이 같은 LAN 직접 연결만 쓴다. 릴리스 빌드에는 넣지 않는다.
3. 카메라 에뮬레이터는 후면 카메라를 `webcam0`으로 두고, 카메라 권한을 받을 수 있는 터미널 앱이나 Android Studio에서 직접 실행한다.

## 개인정보 모드

- `NO_OVERLAY`: 원본 영상 송출.
- `VIDEO_SKELETON`: 실제 모델 관절을 표시한 영상 송출. 유효한 분석 결과가 없을 때 원본으로 대체하지 않는다.
- `SKELETON_ONLY`: 이번 MVP에서는 보호자 영상 보기를 제한하고 카메라의 감지는 유지한다.

설정 변경·세션 폐기·서버 제어 연결 단절 시 영상은 닫혀야 한다. 이 동작은 개인정보 검증의 필수 항목이다.

## 자동 검증

기존 DB를 보존하는 임시 PostgreSQL 검증:

```sh
cd ../fall_detection_backend
python3 scripts/test-postgres.py
python3 scripts/test-android-api.py
# live API/WebRTC 통합만 별도 실행
CAREGUARD_TEST_CLASS=com.example.careguardcamera.LiveSessionIntegrationTest python3 scripts/test-android-api.py
```

Android 빌드 및 정적 검사:

```sh
cd ../CareGuardCamera
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-parallel
```

모델 검사(준비된 Python 환경):

```sh
cd ..
./venv_aihub/bin/python tests/test_live_model_parity.py
./venv_aihub/bin/python scripts/live_model_parity.py --probe-onnx --probe-ort --golden
```

## 실제 휴대폰 검증 기록

폰 두 대가 연결되면 아래 항목을 수행하고 `.omx/verification/`에 결과를 남긴다.

- 기기 모델/OS, 서버 주소와 네트워크, 모델 해시, 앱 빌드, 해상도를 기록한다.
- 보호자 없이 5분간 분석이 계속되는지 확인한다.
- 보호자 연결/종료/재접속 3회, 각 재접속 5초 이내 목표.
- 영상 지연 표본 30개, p95 1초 이내 목표.
- 동시 송출·분석 20분: 분석 10fps 이상, 추론 p95 1초 이하, 심한 발열·ANR·crash 없음 목표.
- 개인정보 3모드, 활성 중 설정 변경, 서버 단절, 카메라 HOME/종료를 시험한다.
- 이벤트 10개가 저장 후 5초 내 한 번씩 타임라인/캘린더에 반영되는지 확인한다.
- 응답 유실 후 동일 이벤트 재전송이 중복 저장되지 않는지 확인한다.

위 수치는 시연 목표이며 아직 실기기로 달성했다고 주장하지 않는다. 휴대폰 성능 실패가 실제로 확인되고 기록될 때만 서버 PC 판정 방식으로 전환을 검토한다.

## 레거시 분석 화면

기존 비공개 `MainActivity` 분석 화면은 보존되어 있다. 런처에서 사용하는 새 카메라 라이브 화면은 카메라 권한만 요청하고 WebRTC 오디오 트랙을 만들지 않는다. 기존 manifest의 음성·알림 권한은 레거시 기능용이며 이번 라이브 전송에는 사용하지 않는다.

## 현재 16KB 페이지 호환성 제약

Android 17 / 16KB 페이지 에뮬레이터는 현재 CameraX·ONNX Runtime·WebRTC 네이티브 라이브러리에 대해 `Android App Compatibility` 안내를 표시하고 호환 모드로 실행한다. 자동 UI 시험은 해당 안내의 OK만 눌러 이번 실행을 계속한다. 경고를 숨기거나 16KB 네이티브 지원이 완료되었다고 처리하지 않는다. 실제 시연 기기의 OS/페이지 크기를 확인하고, 16KB 정식 지원이 필요하면 라이브러리·빌드 도구 호환성을 별도 검증해야 한다.
