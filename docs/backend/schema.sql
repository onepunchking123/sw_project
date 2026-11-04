-- CareGuard 백엔드 스키마 v1 (PostgreSQL 15+)
-- Spring Boot + JPA 기준. Flyway 를 쓰면 이 파일을 V1__init.sql 로 옮긴다.
-- 설계 메모: docs/backend/entities.md (엔티티 관계 · 전송 흐름)

create extension if not exists pgcrypto;   -- gen_random_uuid()

-- ─────────────────────────────────────────────────────────────
-- 열거형
-- ─────────────────────────────────────────────────────────────
create type caregiver_role     as enum ('OWNER', 'MEMBER');
create type caregiver_relation as enum ('CHILD', 'SPOUSE', 'SIBLING', 'RELATIVE', 'ACQUAINTANCE', 'OTHER');
create type privacy_mode       as enum ('VIDEO_SKELETON', 'SKELETON_ONLY', 'NO_OVERLAY');
create type device_status      as enum ('ONLINE', 'OFFLINE', 'UNPAIRED');
create type event_kind         as enum ('FALL', 'FALLEN_IMMOBILE', 'STILL_HORIZONTAL');
create type event_rule         as enum ('MODEL', 'R1', 'R2');
create type pose_state         as enum ('NORMAL', 'FALL', 'FALLEN', 'NO_PERSON');
create type review_label       as enum ('CONFIRMED', 'FALSE_POSITIVE');
create type posture            as enum ('UPRIGHT', 'LYING', 'ABSENT');
create type clip_status        as enum ('UPLOADING', 'READY', 'DELETED');
create type incident_resolution as enum ('PATIENT_OK', 'CAREGIVER_OK', 'EMERGENCY_CALL', 'FALSE_POSITIVE');
create type response_status    as enum ('NOTIFIED', 'ACKNOWLEDGED', 'RESPONDING');
create type record_category    as enum ('SYMPTOM', 'MEDICATION', 'DOSE', 'ENVIRONMENT', 'VISIT', 'OTHER');
create type condition_level    as enum ('NORMAL', 'SLIGHTLY_UNSTABLE', 'DIZZY', 'GAIT', 'APPETITE', 'SLEEP', 'MEMORY', 'FALL');

-- ─────────────────────────────────────────────────────────────
-- 계정 · 환자 · 연결
-- ─────────────────────────────────────────────────────────────
create table users (
    id             uuid primary key default gen_random_uuid(),
    email          varchar(255) not null unique,
    phone          varchar(32),
    password_hash  varchar(255) not null,
    name           varchar(60)  not null,
    fcm_token      varchar(512),                 -- 푸시 대상 토큰. 로그인 기기 1대 전제
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

create table patients (
    id             uuid primary key default gen_random_uuid(),
    name           varchar(60)  not null,
    age            smallint check (age between 0 and 130), -- 보호자가 입력한 나이. 생년월일 추정·자동 증가 없음
    sex            char(1) check (sex in ('M', 'F')),
    address        varchar(255),                 -- 119 신고용. 긴급 화면에 그대로 노출
    dementia_stage varchar(40),                  -- 스토리보드 3~4단계 (진단 여부·단계). 자유 입력
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

-- User ↔ Patient 다대다. "관계"와 알림 설정의 주인
create table caregiver_links (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references users(id)    on delete cascade,
    patient_id     uuid not null references patients(id) on delete cascade,
    relation       caregiver_relation not null,
    role           caregiver_role     not null default 'MEMBER',
    notify_fall    boolean not null default true,         -- 낙상 알림
    notify_still_min smallint,                            -- 미동(R2) 알림 기준 분. null 이면 끔
    notify_offline boolean not null default true,         -- 카메라 연결 끊김 알림
    joined_at      timestamptz not null default now(),
    unique (user_id, patient_id)
);
create index ix_caregiver_links_patient on caregiver_links(patient_id);

-- 온보딩에서 최초 등록, 설정 > 보호자 관리에서 추가·수정. 가입 계정과 독립.
create table patient_contacts (
    id             uuid primary key default gen_random_uuid(),
    patient_id     uuid not null references patients(id) on delete cascade,
    name           varchar(60) not null,
    relation       caregiver_relation not null,
    phone          varchar(32) not null,
    sort_order     integer not null default 0 check (sort_order >= 0),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_patient_contacts_patient on patient_contacts(patient_id);

-- ─────────────────────────────────────────────────────────────
-- 기기
-- ─────────────────────────────────────────────────────────────
create table camera_devices (
    id             uuid primary key default gen_random_uuid(),
    patient_id     uuid references patients(id) on delete set null,   -- 페어링 전엔 null
    name           varchar(60)  not null default '거실 카메라',
    model          varchar(80),
    device_token   varchar(255) not null unique, -- 카메라 폰 인증용. 페어링 시 발급
    privacy_mode   privacy_mode  not null default 'VIDEO_SKELETON',
    privacy_revision bigint      not null default 0, -- 라이브 세션 개인정보 설정 버전. 설정 변경마다 증가
    sensitivity    smallint      not null default 2 check (sensitivity between 1 and 3),
    status         device_status not null default 'UNPAIRED',
    last_seen_at   timestamptz,                  -- 하트비트. 60초를 넘으면 OFFLINE 판정
    last_fps       real,
    battery_pct    smallint,
    is_charging    boolean,
    thermal_ok     boolean,
    paired_at      timestamptz,
    created_at     timestamptz   not null default now()
);
create index ix_camera_devices_patient on camera_devices(patient_id);
create index ix_camera_devices_last_seen on camera_devices(last_seen_at);

-- QR 에 담기는 1회용 코드. 카메라 폰이 발급, 보호자 폰이 소비
create table pairing_codes (
    id               uuid primary key default gen_random_uuid(),
    camera_device_id uuid not null references camera_devices(id) on delete cascade,
    code             varchar(16) not null unique,
    expires_at       timestamptz not null,
    used_at          timestamptz,
    used_by_user_id  uuid references users(id),
    created_at       timestamptz not null default now()
);

-- ─────────────────────────────────────────────────────────────
-- 감지 · 클립 · 긴급
-- ─────────────────────────────────────────────────────────────

-- 저장 영상 포인터. 바이트는 오브젝트 스토리지(S3/MinIO)
create table clips (
    id               uuid primary key default gen_random_uuid(),
    camera_device_id uuid not null references camera_devices(id) on delete cascade,
    storage_key      varchar(512) not null unique, -- clips/{patient_id}/{event_id}.mp4
    thumbnail_key    varchar(512),
    status           clip_status  not null default 'UPLOADING',
    started_at       timestamptz  not null,
    duration_sec     real,
    size_bytes       bigint,
    mime_type        varchar(60)  not null default 'video/mp4',
    expires_at       timestamptz  not null,        -- 보존 기한. 야간 배치가 지움
    created_at       timestamptz  not null default now()
);
create index ix_clips_expires on clips(expires_at) where status <> 'DELETED';

-- 규칙이 발화한 순간 1행. 하루 몇 건 수준
create table detection_events (
    id               uuid primary key default gen_random_uuid(),
    client_event_id  uuid not null unique,         -- 폰이 생성. 재전송 중복 방지
    patient_id       uuid not null references patients(id)       on delete cascade,
    camera_device_id uuid not null references camera_devices(id) on delete cascade,
    occurred_at      timestamptz not null,
    kind             event_kind  not null,
    rule             event_rule  not null,
    state            pose_state  not null,
    prob_normal      real not null check (prob_normal between 0 and 1),
    prob_fall        real not null check (prob_fall   between 0 and 1),
    prob_fallen      real not null check (prob_fallen between 0 and 1),
    duration_sec     real,                         -- R1·R2 지속 시간
    keypoints        jsonb,                        -- [[x,y,conf] × 17] 발화 프레임 1장
    clip_id          uuid references clips(id) on delete set null,
    reviewed_as      review_label,                 -- 보호자의 '오탐으로 표시'. 재학습 데이터
    reviewed_by_user_id uuid references users(id),
    reviewed_at      timestamptz,
    created_at       timestamptz not null default now()
);
create index ix_events_patient_time on detection_events(patient_id, occurred_at desc);
create index ix_events_device_time  on detection_events(camera_device_id, occurred_at desc);

-- 낙상이 긴급 상황으로 격상된 것. R1 이벤트 1건당 최대 1개
create table incidents (
    id                   uuid primary key default gen_random_uuid(),
    detection_event_id   uuid not null unique references detection_events(id) on delete cascade,
    patient_id           uuid not null references patients(id) on delete cascade,
    started_at           timestamptz not null,
    countdown_ends_at    timestamptz not null,     -- started_at + 10초
    patient_responded    boolean not null default false,
    patient_responded_at timestamptz,
    auto_notified_at     timestamptz,              -- 카운트다운 만료 후 FCM 발송 시각
    resolved_at          timestamptz,
    resolution           incident_resolution,
    resolved_by_user_id  uuid references users(id),
    resolution_note      text,
    created_at           timestamptz not null default now()
);
create index ix_incidents_open on incidents(patient_id) where resolved_at is null;

-- 보호자별 대응 상태. 긴급 화면의 '대응 중 · 확인함 · 미확인'
create table incident_responses (
    id           uuid primary key default gen_random_uuid(),
    incident_id  uuid not null references incidents(id) on delete cascade,
    user_id      uuid not null references users(id)     on delete cascade,
    status       response_status not null default 'NOTIFIED',
    notified_at  timestamptz not null default now(),
    acknowledged_at timestamptz,
    responding_at timestamptz,
    updated_at   timestamptz not null default now(),
    note         text,
    unique (incident_id, user_id)
);

-- ─────────────────────────────────────────────────────────────
-- 자세 요약 (1분 단위). 지난밤 요약 · 활동 시간 · 주간 리포트의 원천
-- ─────────────────────────────────────────────────────────────
create table posture_samples (
    camera_device_id uuid not null references camera_devices(id) on delete cascade,
    minute_at        timestamptz not null,         -- 분 단위로 절삭된 시각
    dominant         posture not null,
    lying_ratio      real not null check (lying_ratio between 0 and 1),
    motion           real not null,                -- 평균 관절 변위 (몸통 길이 단위)
    detect_rate      real not null check (detect_rate between 0 and 1),
    fps              real,
    primary key (camera_device_id, minute_at)
);

-- ─────────────────────────────────────────────────────────────
-- 기록 · 리포트
-- ─────────────────────────────────────────────────────────────
create table care_records (
    id             uuid primary key default gen_random_uuid(),
    patient_id     uuid not null references patients(id) on delete cascade,
    author_user_id uuid not null references users(id),
    recorded_at    timestamptz not null default now(),
    category       record_category not null,
    condition      condition_level,              -- 분류가 SYMPTOM 일 때 주로 채움
    title          varchar(80) not null,
    memo           text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index ix_records_patient_time on care_records(patient_id, recorded_at desc);

-- AI 감지 또는 보호자가 기록한 이상상황 중 정확히 하나에 대응 메모를 누적한다.
-- 연락처 등록만으로 작성 권한이 생기지 않으며 작성자는 로그인 보호자다.
create table event_notes (
    id                 uuid primary key default gen_random_uuid(),
    detection_event_id uuid references detection_events(id) on delete cascade,
    care_record_id     uuid references care_records(id) on delete cascade,
    author_user_id     uuid not null references users(id),
    kind               varchar(16) not null check (kind in ('CALL', 'VISIT', 'HOSPITAL', 'OBSERVE', 'ETC')),
    patient_state      varchar(16) check (patient_state in ('FINE', 'PAIN', 'SLEEPY', 'UNSURE')),
    memo               text not null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint ck_event_notes_one_parent check (
        (detection_event_id is not null) <> (care_record_id is not null)
    )
);
create index ix_event_notes_detection on event_notes(detection_event_id, created_at);
create index ix_event_notes_record on event_notes(care_record_id, created_at);

-- 주간 집계 스냅샷. 매주 월요일 배치로 생성 (비정규화)
create table weekly_reports (
    id              uuid primary key default gen_random_uuid(),
    patient_id      uuid not null references patients(id) on delete cascade,
    week_start      date not null,                -- 월요일
    abnormal_count  int  not null default 0,      -- FALL + STILL 이벤트 수
    fall_count      int  not null default 0,      -- R1 기준
    uptime_percent  real not null default 0,      -- 카메라 ONLINE 비율
    avg_active_hours real,                        -- posture_samples 에서 UPRIGHT 분/60
    avg_lying_night_hours real,
    summary_text    text,                         -- "낮 활동이 지난주보다 늘었고 …"
    pdf_key         varchar(512),                 -- 오브젝트 스토리지 포인터
    generated_at    timestamptz not null default now(),
    unique (patient_id, week_start)
);

-- ─────────────────────────────────────────────────────────────
-- updated_at 자동 갱신
-- ─────────────────────────────────────────────────────────────
create or replace function touch_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;

create trigger trg_users_touch    before update on users           for each row execute function touch_updated_at();
create trigger trg_patients_touch before update on patients        for each row execute function touch_updated_at();
create trigger trg_records_touch  before update on care_records    for each row execute function touch_updated_at();
create trigger trg_resp_touch     before update on incident_responses for each row execute function touch_updated_at();

create trigger trg_contacts_touch before update on patient_contacts for each row execute function touch_updated_at();

create trigger trg_event_notes_touch before update on event_notes for each row execute function touch_updated_at();
