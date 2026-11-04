# CareGuard 로컬 CRUD API

Java 17 / Spring Boot / PostgreSQL. 앱 화면에 필요한 개발용 데이터 API이며 Android 보호자 화면의 기본 CRUD와 연결되어 있다.

## 이 개발 환경의 전용 로컬 DB

PostgreSQL 16을 Docker 없이 `127.0.0.1:5433`에서 실행한다. DB는 `fall_detection`, 사용자는 `careguard`다. 비밀번호는 Git에서 제외한 `application-local.properties`에 저장되어 있으며 백엔드가 자동으로 읽는다. 기존 layer Docker DB의 5432 포트와 분리되어 있다.

```sh
cd fall_detection_backend
python3 scripts/local-db.py status
python3 scripts/local-db.py start
./gradlew bootRun
```

DB를 중지하려면 `python3 scripts/local-db.py stop`을 실행한다. 재부팅 후에는 `start`가 필요하다. 데이터는 `.local/postgres`에 보존되므로 이 디렉터리를 삭제하지 않는다. `init`은 새 개발 환경에서 최초 한 번만 사용하며, 이미 생성된 클러스터에는 스키마를 다시 적용하지 않는다. 기존 로컬 설정은 `.local/application-local.properties.before-init`에 백업된다.

Docker 전환 시 PostgreSQL 16 버전을 맞추고 `pg_dump`/`pg_restore`로 데이터를 이전한다. 배포 접속 정보는 `DB_URL`, `DB_USER`, `DB_PASSWORD`로 전달한다. 이후 스키마 변경은 버전별 마이그레이션으로 관리하고, 배포 DB의 데이터 볼륨과 백업을 별도로 구성한다. 현재 `schema.sql`은 빈 DB의 초기화용이다.

## 실행

빈 PostgreSQL DB를 만들고 저장소 루트에서 스키마를 적용한다. 기존 데이터베이스에 초기 스키마를 재실행하지 않는다.

```sh
createdb fall_detection
psql -d fall_detection -v ON_ERROR_STOP=1 -f docs/backend/schema.sql
cd fall_detection_backend
DB_USER="$(whoami)" ./gradlew bootRun
```

`DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`로 접속 정보를 지정한다. 기본 주소는 `127.0.0.1:8080`. Hibernate는 스키마를 검증하며 생성/변경하지 않는다.

현재 인증·권한 검증 계층은 없으므로 로컬 개발 데이터로만 사용한다. 요청의 `authorUserId`는 인증된 사용자 정보가 아니다. `/api/users`는 로그인 불가능한 프로필을 만드는 개발용 부트스트랩이며 비밀번호/기기 토큰을 응답하지 않는다. 실제 배포 전 인증 및 환자별 접근 권한 구현이 필요하다.

## 화면별 API

| 화면 | 경로 | 메서드 |
|---|---|---|
| 개발용 작성자 프로필 | `/api/users`, `/api/users/{id}` | POST, GET(단건) |
| 환자 정보 | `/api/patients[/{id}]` | POST, GET, PUT, DELETE |
| 비상 연락처 | `/api/patients/{patientId}/contacts[/{id}]` | POST, GET, PUT, DELETE |
| 가입 보호자·알림 설정 | `/api/patients/{patientId}/caregivers[/{id}]` | POST, GET, PUT, DELETE |
| 카메라 관리 | `/api/patients/{patientId}/cameras[/{id}]` | POST, GET, PUT, DELETE(연결 해제) |
| 직접 작성한 이상상황 | `/api/patients/{patientId}/records[/{id}]` | POST, GET, PUT, DELETE |
| AI 감지 데이터 | `/api/patients/{patientId}/events[/{id}]` | POST, GET, PUT, DELETE |
| 대응 메모 | `/api/patients/{patientId}/notes[/{id}]` | POST, GET, PUT, DELETE |
| 일별 타임라인 | `/api/patients/{patientId}/timeline?date=2026-10-02` | GET |
| 월별 캘린더 | `/api/patients/{patientId}/calendar?month=2026-10` | GET |
| 긴급 상황 목록·등록 | `/api/patients/{patientId}/incidents` | GET, POST |
| 긴급 상황 상세 | `/api/incidents/{id}` | GET, PUT, DELETE |
| 긴급 종료 | `/api/incidents/{id}/resolution` | PATCH |
| 보호자 대응 상태 | `/api/incidents/{id}/responses[/{responseId}]` | GET, POST, PUT |
| 저장된 주간 리포트 | `/api/patients/{patientId}/weekly-reports` | GET |

PUT은 전체 입력 필드를 교체한다. 생성은 201, 삭제는 204, 입력 오류는 400, 없는/다른 환자의 대상은 404, DB 참조·중복 충돌은 409다.

온보딩/설정 목록은 `offset=0&limit=200`으로 조회하며 최대 200개다. 기록/감지/메모 목록도 `offset`, `limit`을 지원하며 최대 200개다. 타임라인과 캘린더는 지정한 날짜·월 전체 원본 데이터를 집계한다. 메모 목록은 `detectionEventId` 또는 `careRecordId` 하나로 필터링할 수 있다.

연락처는 `name`, `relation`, `phone`, `sortOrder`를 받는다. 비가입 연락처와 `caregivers`의 로그인 계정 연결을 구분한다. 연락처 순서는 `sortOrder`, 동일 순서에서는 ID로 정렬한다. 보호자 관계 값은 `CHILD/SPOUSE/SIBLING/RELATIVE/ACQUAINTANCE/OTHER`다.

환자 성별은 `M/F` 또는 null, 나이는 `0..130`. 프론트의 한글 표시값은 API 연동 시 변환한다. 카메라 입력은 `name`, `model`, `privacyMode`, `sensitivity(1..3)`이며 실제 코드 페어링·온라인 판정은 수행하지 않는다. 카메라 연결 해제는 감지 이력을 보존한다.

## 요청 예시

```json
{"name":"홍길동","age":85,"sex":"M","address":"창원시","dementiaStage":null}
```

수동 이상상황 (`authorUserId`는 먼저 만든 `/api/users` ID):

```json
{"authorUserId":"사용자 UUID","recordedAt":"2026-10-02T09:00:00+09:00","category":"SYMPTOM","condition":"DIZZY","title":"어지럼증 호소","memo":"아침 식사 후 어지러움"}
```

수동 기록 대응 메모:

```json
{"careRecordId":"기록 UUID","detectionEventId":null,"authorUserId":"사용자 UUID","kind":"CALL","patientState":"FINE","memo":"통화로 상태 확인"}
```

`EventNote`는 감지 또는 수동 기록 중 정확히 한 곳에 연결한다. `kind`: CALL/VISIT/HOSPITAL/OBSERVE/ETC, `patientState`: FINE/PAIN/SLEEPY/UNSURE 또는 null.

시간은 오프셋을 포함한 ISO 8601로 보낸다. 타임라인·캘린더 날짜는 Asia/Seoul 기준이며, 새벽 00–06 / 아침 06–12 / 오후 12–18 / 저녁 18–24로 나눈다. 타임라인 응답은 최신 구간부터 `{period,count,entries}` 목록이며 빈 구간도 포함한다. 원본 AI 감지와 수동 기록을 각각 1건으로 집계하며 후속 메모는 제외한다. 캘린더는 0건 NONE, 1–2 LOW, 3–5 MEDIUM, 6건 이상 HIGH다. 의학적 위험도 계산이 아니다.

실시간 영상, QR 페어링, 푸시 발송, 자동 긴급 격상, 리포트 생성은 이 CRUD에서 실행하지 않는다. PairingCode/Clip/PostureSample/WeeklyReport의 엔티티·리포지토리는 마련되어 있지만 전용 수집·생성 API는 별도 작업이다.

## 실제 PostgreSQL 검증

```sh
cd fall_detection_backend
python3 scripts/test-postgres.py
```

임시 로컬 클러스터를 새로 만들고 스키마 적용 → HTTP 통합 테스트 → 실행 JAR 빌드 후 서버를 종료한다. 기존 DB는 사용하지 않는다. `POSTGRES_BIN`으로 PostgreSQL 실행파일 디렉터리를 바꿀 수 있다. Gradle 의존성이 최초 실행 시 캐시에 없으면 `./gradlew testClasses`로 먼저 내려받는다.

검증 기록 (2026-10-03): 임시 PostgreSQL 16에서 통합 테스트 7개 통과(실패·스킵 0), bootJar 생성 성공. CRUD, 환자별 참조 경계, 메모 누적/삭제, 시간대/빈도 집계, 긴급 대응 시각 보존, 공유 클립·JSONB·복합키 재조회 확인.

## Android 연결

보호자 화면의 기본 CRUD는 Android `BackendRepository`와 연결되어 있다. 에뮬레이터 기본 주소는 `http://10.0.2.2:8080`이며 앱의 서버 설정에서 변경한다. 실행·검증 방법은 [Android 연결 안내](../CareGuardCamera/BACKEND_CONNECTION.md)를 참고한다. 긴급 상황·대응·주간 리포트 목록도 `offset`, `limit`(1~200)을 지원한다.

## 개발용 샘플 데이터

전용 로컬 DB가 실행 중일 때 다음 명령을 사용한다.

```sh
python3 scripts/local-db.py seed
python3 scripts/local-db.py verify
# 백엔드 8080 실행 후
python3 scripts/verify-sample-api.py
```

`../docs/backend/seed-development.sql`은 14개 테이블에 가상 데이터를 삽입한다. 고정 ID와 `ON CONFLICT DO NOTHING`을 사용해 재실행 시 기존 행이나 사용자가 수정한 내용을 덮어쓰지 않는다. 샘플 날짜는 최초 생성한 환자의 생성일을 기준으로 유지된다. 검증 스크립트의 건수 검증은 샘플을 수정하기 전 기준이다.

앱의 **서버 설정 → 저장 및 연결 → 샘플 환자 김돌봄**을 선택하면 화면에서 확인할 수 있다. 에뮬레이터 서버 주소는 `http://10.0.2.2:8080`이다. 기존 환자는 유지된다.

샘플 구성: 보호자 2명, 연락처 2명, 카메라 2대, AI 감지 3건, 수동 기록 13건, 대응 메모 4건, 긴급 상황 1건과 대응 2건, 자세 샘플 12건, 주간 리포트 1건. 만료된 페어링 코드와 미업로드 클립 메타데이터도 각각 1건 포함한다. 실제 영상 파일, 푸시 발송, 실시간 카메라 연결은 생성하지 않는다. 전화번호와 주소는 가상 값이다.

검증: 2026-10-03 로컬 DB 실제 삽입 및 두 번 실행 후 동일 행 수 확인. 기존 8080 서버의 환자별 조회 API, 아침/오후/저녁 각 2건, 캘린더 LOW/MEDIUM/HIGH 확인.

## 배포

EC2 한 대에 Docker Compose로 올리는 절차는 [deploy/README.md](../deploy/README.md)를 따른다. 이미지는 이 디렉터리의 `Dockerfile`로 만든다.

## 실시간 영상 개발 API

동일한 신뢰할 수 있는 LAN 시연용이며 정식 로그인과 WAN 전송은 포함하지 않는다. 상세 앱 실행 및 실기기 검증은 [실시간 MVP 안내](../CareGuardCamera/LIVE_VIDEO_MVP.md)를 따른다.

- 기본은 비활성. `CAREGUARD_LIVE_ENABLED=true`로 `/api/dev/live/**`를 활성화한다. 접속 원본이 loopback/private LAN인 요청만 받는다.
- 공개 서버(EC2 등)에서는 `CAREGUARD_LIVE_ALLOW_REMOTE=true`를 함께 줘야 LAN 제한이 풀린다. 기본값 false. 이때는 보호자 allowlist를 같이 쓰는 것을 권장한다.
- 실제 휴대폰은 `SERVER_ADDRESS=0.0.0.0`과 PC LAN 주소를 사용한다. 공개 프록시나 인터넷 포트 포워딩에 연결하지 않는다.
- 선택적으로 `CAREGUARD_LIVE_GUARDIAN_ALLOWLIST`에 보호자 UUID를 쉼표로 넣어 허용 대상을 제한할 수 있다. 이 설정은 보호자 로그인을 대신하지 않는다.
- 기존 DB는 [privacy revision migration](../docs/backend/migrations/20261004-live-privacy-revision.sql)을 먼저 적용해야 한다. 데이터 삭제 없이 열 하나를 추가한다. 이번 작업에서는 기존 DB에 적용하지 않았다.
- 카메라 bearer로 세션·lease·control·signal·event 요청을 인증한다. 보호자 viewer capability는 128-bit이며 patient 연결 관계를 검사한다.
- 시그널과 세션은 메모리에만 보관하며 서버 재시작 후 새 세션을 만들어야 한다. 원본 영상 및 관절 좌표는 live event 저장 대상이 아니다.
- 이벤트는 원본 kind/rule/state/세 확률과 UUID를 보존한다. 동일 UUID 재전송은 원본 session의 active/tombstone 기간 내에만 중복 없이 처리한다.

실제 앱 API + 영상 재접속 시험(에뮬레이터 필요, 임시 DB 사용):

```sh
CAREGUARD_LIVE_TEST_CLASS=com.example.careguardcamera.LiveSessionIntegrationTest python3 scripts/test-android-api.py
```
