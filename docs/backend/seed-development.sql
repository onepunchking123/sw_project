-- Fictional development data only. Stable IDs make this safe to rerun without overwriting edits.
BEGIN;
SET LOCAL TIME ZONE 'Asia/Seoul';
CREATE FUNCTION pg_temp.seed_id(label text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
 SELECT md5('careguard-development-v1:' || label)::uuid
$$;
DO $$
DECLARE
 p uuid := pg_temp.seed_id('patient');
 u uuid := pg_temp.seed_id('user1');
 v uuid := pg_temp.seed_id('user2');
 c uuid := pg_temp.seed_id('camera1');
 d timestamptz;
 e uuid;
 r uuid;
 i integer;
 j integer;
BEGIN
 INSERT INTO users(id,email,password_hash,name,phone) VALUES
 (u,'guardian1@example.invalid','!','샘플 보호자 민지','01000000001'),
 (v,'guardian2@example.invalid','!','샘플 보호자 준호','01000000002') ON CONFLICT DO NOTHING;
 INSERT INTO patients(id,name,age,sex,address,dementia_stage) VALUES
 (p,'샘플 환자 김돌봄',78,'F','개발용 가상 주소 · 실제 주소 아님','미입력') ON CONFLICT DO NOTHING;
 SELECT date_trunc('day',created_at) INTO d FROM patients WHERE id=p;
 INSERT INTO caregiver_links(id,user_id,patient_id,relation,role,notify_still_min) VALUES
 (pg_temp.seed_id('link1'),u,p,'CHILD','OWNER',30),
 (pg_temp.seed_id('link2'),v,p,'CHILD','MEMBER',60) ON CONFLICT DO NOTHING;
 INSERT INTO patient_contacts(id,patient_id,name,relation,phone,sort_order) VALUES
 (pg_temp.seed_id('contact1'),p,'샘플 연락처 민지','CHILD','01000000001',0),
 (pg_temp.seed_id('contact2'),p,'샘플 연락처 준호','CHILD','01000000002',1) ON CONFLICT DO NOTHING;
 INSERT INTO camera_devices(id,patient_id,name,model,device_token,privacy_mode,status,paired_at) VALUES
 (c,p,'샘플 거실 카메라','개발용 가상 기기','seed-only-not-a-real-token-1','VIDEO_SKELETON','OFFLINE',d),
 (pg_temp.seed_id('camera2'),p,'샘플 침실 카메라','개발용 가상 기기','seed-only-not-a-real-token-2','SKELETON_ONLY','UNPAIRED',NULL)
 ON CONFLICT DO NOTHING;
 INSERT INTO pairing_codes(id,camera_device_id,code,expires_at,used_at,used_by_user_id,created_at) VALUES
 (pg_temp.seed_id('pairing'),c,'SAMPLE-EXPIRED',d-interval '1 hour',d-interval '2 hours',u,d-interval '3 hours') ON CONFLICT DO NOTHING;
 INSERT INTO clips(id,camera_device_id,storage_key,status,started_at,duration_sec,expires_at) VALUES
 (pg_temp.seed_id('clip'),c,'development-placeholder/no-uploaded-video.mp4','UPLOADING',d+interval '8 hours',30,d+interval '7 days') ON CONFLICT DO NOTHING;
 FOR i IN 1..3 LOOP
  e := pg_temp.seed_id('event' || i);
  INSERT INTO detection_events(id,client_event_id,patient_id,camera_device_id,occurred_at,kind,rule,state,prob_normal,prob_fall,prob_fallen,duration_sec,clip_id,keypoints)
  VALUES(e,pg_temp.seed_id('client-event' || i),p,c,d+make_interval(hours=>CASE i WHEN 1 THEN 8 WHEN 2 THEN 14 ELSE 19 END),
   CASE i WHEN 1 THEN 'FALLEN_IMMOBILE'::event_kind WHEN 2 THEN 'STILL_HORIZONTAL'::event_kind ELSE 'FALL'::event_kind END,
   CASE i WHEN 1 THEN 'R1'::event_rule WHEN 2 THEN 'R2'::event_rule ELSE 'MODEL'::event_rule END,
   'FALLEN',0.02,0.08,0.90,30,CASE WHEN i=1 THEN pg_temp.seed_id('clip') ELSE NULL END,
   (SELECT jsonb_agg(jsonb_build_array(0.5,0.5,0.9)) FROM generate_series(1,17))) ON CONFLICT DO NOTHING;
 END LOOP;
 INSERT INTO incidents(id,detection_event_id,patient_id,started_at,countdown_ends_at) VALUES
 (pg_temp.seed_id('incident'),pg_temp.seed_id('event1'),p,d+interval '8 hours',d+interval '8 hours 10 seconds') ON CONFLICT DO NOTHING;
 INSERT INTO incident_responses(id,incident_id,user_id,status,notified_at,acknowledged_at,responding_at,note) VALUES
 (pg_temp.seed_id('response1'),pg_temp.seed_id('incident'),u,'RESPONDING',d+interval '8 hours 10 seconds',d+interval '8 hours 20 seconds',d+interval '8 hours 30 seconds','개발용 대응 상태 · 실제 알림 발송 없음'),
 (pg_temp.seed_id('response2'),pg_temp.seed_id('incident'),v,'ACKNOWLEDGED',d+interval '8 hours 10 seconds',d+interval '8 hours 40 seconds',NULL,'개발용 확인 상태') ON CONFLICT DO NOTHING;
 FOR i IN 0..11 LOOP
  INSERT INTO posture_samples(camera_device_id,minute_at,dominant,lying_ratio,motion,detect_rate,fps)
  VALUES(c,d+interval '7 hours'+make_interval(mins=>i),CASE WHEN i<6 THEN 'UPRIGHT'::posture ELSE 'LYING'::posture END,
   CASE WHEN i<6 THEN 0.1 ELSE 0.9 END,0.05,0.95,15) ON CONFLICT DO NOTHING;
 END LOOP;
 -- Today: 3 AI + 3 manual = HIGH; yesterday LOW; two days ago MEDIUM; three days ago HIGH.
 FOR i IN 0..3 LOOP
  FOR j IN 1..CASE i WHEN 0 THEN 3 WHEN 1 THEN 1 WHEN 2 THEN 3 ELSE 6 END LOOP
   r := pg_temp.seed_id('record-' || i || '-' || j);
   INSERT INTO care_records(id,patient_id,author_user_id,recorded_at,category,condition,title,memo)
   VALUES(r,p,u,d-make_interval(days=>i)+make_interval(hours=>CASE WHEN i=0 THEN 4+j*5 ELSE 7+j END),
    'SYMPTOM',CASE j WHEN 1 THEN 'DIZZY'::condition_level WHEN 2 THEN 'APPETITE'::condition_level ELSE 'GAIT'::condition_level END,
    CASE j WHEN 1 THEN '샘플 · 어지러움 호소' WHEN 2 THEN '샘플 · 식사량 감소' ELSE '샘플 · 보행 불안정' END,
    '화면 연동을 위한 가상 기록입니다. AI가 탐지하지 못한 이상상황을 보호자가 작성한 예시입니다.') ON CONFLICT DO NOTHING;
  END LOOP;
 END LOOP;
 INSERT INTO event_notes(id,detection_event_id,care_record_id,author_user_id,kind,patient_state,memo,created_at) VALUES
 (pg_temp.seed_id('note1'),pg_temp.seed_id('event1'),NULL,u,'CALL','UNSURE','샘플: 전화로 상태 확인 시도',d+interval '8 hours 1 minute'),
 (pg_temp.seed_id('note2'),pg_temp.seed_id('event1'),NULL,v,'VISIT','FINE','샘플: 방문하여 상태 확인',d+interval '8 hours 30 minutes'),
 (pg_temp.seed_id('note3'),NULL,pg_temp.seed_id('record-0-1'),u,'OBSERVE','UNSURE','샘플: 휴식 후 경과 관찰',d+interval '9 hours 10 minutes'),
 (pg_temp.seed_id('note4'),NULL,pg_temp.seed_id('record-0-1'),v,'CALL','FINE','샘플: 통화로 호전 여부 확인',d+interval '9 hours 40 minutes') ON CONFLICT DO NOTHING;
 INSERT INTO weekly_reports(id,patient_id,week_start,abnormal_count,fall_count,uptime_percent,summary_text)
 VALUES(pg_temp.seed_id('report'),p,date_trunc('week',d)::date,3,1,0,
 '개발용 샘플 리포트입니다. AI 감지 3건과 보호자 기록을 확인할 수 있습니다. 실제 모니터링이나 의료 판단 결과가 아닙니다.') ON CONFLICT DO NOTHING;
END $$;
COMMIT;
