# CareGuard 백엔드 엔티티 설계 메모

_Spring Boot + JPA · PostgreSQL 15+ · DDL 은 `schema.sql`_

## 엔티티 14개

| 구역 | 엔티티 | 역할 |
|---|---|---|
| 계정·환자 | users | 로그인 계정 = 보호자 |
| | patients | 돌봄 대상 |
| | caregiver_links | users ↔ patients 다대다. 관계(자녀·배우자…), 역할(OWNER/MEMBER), 알림 설정의 주인 |
| | patient_contacts | 비가입 가족도 가능한 환자별 비상 연락처 |
| 기기 | camera_devices | 거치 폰. 하트비트로 `last_seen_at`·fps·배터리·열 갱신. 영상 공개 모드·민감도 |
| | pairing_codes | 8자리 1회용 코드. 카메라 폰 발급 → 보호자 폰 소비. QR은 이 코드를 담는 후속 UI로 확장 가능 |
| 감지·긴급 | detection_events | 규칙 발화 1건 = 1행 (FALL / FALLEN_IMMOBILE R1 / STILL_HORIZONTAL R2). 확률 3개·지속 시간·발화 프레임 키포인트(jsonb)·오탐 리뷰 |
| | clips | 영상 **포인터**. 바이트는 S3/MinIO, 행에는 `storage_key`·상태·보존 기한만 |
| | incidents | R1 이벤트가 긴급으로 격상된 것. 10초 카운트다운·환자 응답·자동 알림·종료 사유 |
| | incident_responses | 보호자별 대응 상태 (NOTIFIED → ACKNOWLEDGED → RESPONDING) |
| 자세 요약 | posture_samples | 1분 단위 자세 집계. 지난밤 요약·활동 시간·주간 리포트의 원천 |
| 기록·리포트 | care_records | 보호자 일지 (증상·투약·복약·환경·진료·기타) + 오늘 상태 |
| | event_notes | AI 감지 또는 수동 이상상황에 누적하는 대응 메모 |
| | weekly_reports | 주간 집계 스냅샷 (비정규화) + PDF 포인터 |

## 확정된 화면·저장 계약 (2026-10-02)

- 엔티티는 기존 12개 + `PatientContact` + `EventNote`의 14개다. 카메라 재연결 표시와 `DeviceStatusHistory`는 이번 범위에서 제외한다.
- `CareRecord`는 기록 탭에서 보호자가 입력하는, AI가 탐지하지 못한 환자 이상상황이다. 타임라인에 표시하고 상세에서 대응 메모를 여러 개 누적한다. 원본 이상상황 기록과 후속 대응 메모는 구분한다.
- `EventNote`는 `detection_event_id` 또는 `care_record_id` 중 정확히 하나를 참조한다. 두 nullable FK와 XOR CHECK로 고아 대상/이중 대상을 금지한다. 작성자·대응 종류·선택 환자 상태·본문·작성 시각을 저장한다. 다른 보호자에게 알리는 기능은 제외한다. 대상 환자에 대한 작성자 접근 권한은 서비스에서 검증한다.
- `PatientContact`는 환자별 이름·관계·전화번호를 저장한다. 비가입 가족도 등록하며 로그인 계정이나 앱 접근 권한을 생성하지 않는다. 온보딩에서 최초 등록하고 설정 > 보호자 관리에서 추가·수정한다. `User` 및 `CaregiverLink`는 가입 보호자의 계정·접근 관계를 계속 담당한다.
- 환자는 프론트에 맞춰 `age`(0~130)를 저장한다. `birth_date`를 나이로 추정하지 않으며 자동으로 나이를 증가시키지 않는다.
- 캘린더는 환자별 하루 AI 이상현상 건수 + `CareRecord` 건수로 계산한다. 대응 메모 수, 정상/기상 파생 행은 제외한다. 0건은 색상 없음, 1~2건은 연한 보라, 3~5건은 중간 보라, 6건 이상은 진한 보라다. 이는 의료적 위험도가 아닌 이상현상 기록 빈도다.
- 초기 집계 날짜는 Asia/Seoul 기준으로 AI의 `occurred_at`, 수동 기록의 `recorded_at`(상황 발생 시각)을 사용한다. `created_at`은 작성 시각이다. 원본 행을 각각 집계한 뒤 합산하여 대응 메모 JOIN으로 건수가 늘어나지 않게 한다. 별도 캘린더/타임라인 엔티티는 추가하지 않는다.
- 후속 프론트 작업: 수동 기록 상세에도 대응 메모 연결, 비상 연락처 입력을 온보딩·보호자 관리로 통합, 재연결 샘플 제거, 캘린더 실제 빈도 집계 및 범례 적용. 현재 화면 구현이 이 계약을 모두 만족한다는 의미는 아니다.
- 미확정 보존 정책: 영상의 보존 기한을 영상 없는 수동 기록·대응 메모에 자동 적용하지 않는다. 삭제 정책은 별도 확정한다.

## 모델 출력과 전송 흐름

모델(YOLO11n-pose → GRU)은 0.5초마다 상태 `normal/fall/fallen/no_person` 과 확률 3개를 낸다. 규칙(ImmobilityDetector)이 그 시계열을 보다가 조건이 맞는 순간만 이벤트를 낸다.

| 카메라 폰 → 서버 | 주기 | 테이블 |
|---|---|---|
| 이벤트 `POST /devices/{id}/events` | 규칙 발화 시 1회. `clientEventId` 로 중복 방지. 응답에 클립 업로드 presigned URL | detection_events, clips, incidents |
| 자세 요약 `POST /devices/{id}/samples` | 1분 배치 (dominant · lying_ratio · motion · detect_rate · fps) | posture_samples |
| 하트비트 | 15초 REST (라이브 화면 foreground 동안) | camera_devices 컬럼 갱신만 |

- 0.5초 원본 상태는 서버로 보내지 않는다 (하루 17만 행, 쓸 데 없음).
- 카메라 등록은 앱이 생성해 Keystore로 보관한 64자리 토큰을 Bearer로 보내고 서버는 digest만 저장한다. 같은 토큰으로 재시도하면 같은 카메라 행을 재사용한다.
- `Incident` 생성 기준은 **FALL 이 아니라 R1**. 넘어졌다 바로 일어나는 경우는 타임라인 기록만.
- 카운트다운 중 환자가 "괜찮습니다"를 누르면 폰이 `PATCH /incidents/{id}` 로 `patientResponded=true`.
- 타임라인의 "기상 08:00", "활동 정상" 행은 posture_samples 에서 서버가 계산하는 파생 행. 카메라 재연결은 이번 범위에서 제외한다.
- 캘린더는 아래 확정 계약의 일별 이상현상 기록 빈도로 집계한다. 별도 테이블 없음.

## 클립 저장

1. 폰이 이벤트 POST → 서버가 `UPLOADING` clip 행 + presigned 업로드 URL 반환
2. 폰이 mp4 를 스토리지에 직접 업로드 (Spring 을 거치지 않음)
3. 폰이 완료 통보 → `READY`
4. 보호자 앱이 상세 요청 → 서버가 몇 분짜리 presigned 다운로드 URL 반환
5. `expires_at` 지난 행은 야간 배치가 오브젝트 삭제 후 `DELETED`

## 일부러 뺀 것

- Notification 로그 테이블: incident_responses 의 NOTIFIED 로 충분
- Settings 테이블: caregiver_links.notify_* 와 camera_devices.privacy_mode 로 흡수
- 오늘 상태 입력·진료 여부 화면: care_records category 로 흡수

## 검증

기존 12개 테이블 버전 검증 기록: 로컬 PostgreSQL 16 임시 클러스터에서 `schema.sql` 적용 → 테이블 12개 생성, users→patients→links→devices→events→incidents→responses→samples→records 연쇄 insert 와 `updated_at` 트리거 동작 확인 (2026-10-02).

## 안드로이드 쪽 후속

현재 `ImmobilityDetector.kt` 에는 파이썬 데모의 `FALL` 상승 에지 이벤트와 R2 의 "움직임 없음(motion_energy)" 조건이 빠져 있다. 이벤트 종류 3개를 서버와 맞추려면 카메라 앱 구현 때 파이썬 데모와 동일하게 보완해야 한다.

## 확정안 DDL 검증 (2026-10-02)

PostgreSQL 16 임시 클러스터에 수정한 schema.sql 적용 성공: 테이블 14개. 비가입 가족 연락처 저장, 수동 이상상황 한 건에 대응 메모 2개 저장 성공. 대응 메모 대상이 없거나 두 개인 INSERT는 CHECK 제약으로 거절됨을 확인했다. 임시 서버는 검증 후 종료했다. 프론트 및 JPA 구현 검증은 포함하지 않는다.

## CRUD 구현 보완 (2026-10-03)

- 테이블 수는 14개를 유지한다. 연락처 순서를 위한 `patient_contacts.sort_order`, 메모 수정 시각 `event_notes.updated_at`, 대응 확인/시작 시각 `incident_responses.acknowledged_at/responding_at`을 추가했다.
- REST 범위와 실행 방법은 `fall_detection_backend/README.md` 참고. 인증·실제 페어링·자동 알림은 별도이며 프론트 저장소를 API로 교체하는 작업도 아직 남아 있다.
