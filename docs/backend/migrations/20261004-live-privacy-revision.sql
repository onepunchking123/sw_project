-- 기존 camera_devices 데이터와 연결 상태를 보존하는 추가 스키마 변경.
-- 신규 DB는 docs/backend/schema.sql에 이미 포함되어 있으므로 별도 적용하지 않는다.
begin;
alter table camera_devices
    add column if not exists privacy_revision bigint not null default 0;
commit;
