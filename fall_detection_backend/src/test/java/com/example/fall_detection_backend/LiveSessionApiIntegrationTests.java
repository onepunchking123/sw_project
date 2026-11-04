package com.example.fall_detection_backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"careguard.live.enabled=true", "careguard.storage.dir=build/test-storage"}
)
@EnabledIfEnvironmentVariable(named = "CAREGUARD_DISPOSABLE_DB", matches = "true")
class LiveSessionApiIntegrationTests {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetDatabase() {
        clock.set(Instant.parse("2026-10-03T00:00:00Z"));
        jdbc.execute("""
            truncate table
                incident_responses,
                incidents,
                event_notes,
                detection_events,
                care_records,
                posture_samples,
                pairing_codes,
                clips,
                camera_devices,
                patient_contacts,
                caregiver_links,
                weekly_reports,
                patients,
                users
            restart identity cascade
            """);
    }

    @Test
    void liveApiRequiresRelationshipsCameraBearerAndPhoneModel() throws Exception {
        var token = hexToken("a");
        var cameraId = registerCamera(token);
        var patientId = seedPatient("김환자");
        var guardianId = seedUser("owner@example.com");
        var outsiderId = seedUser("outsider@example.com");
        pairCamera(token, patientId, guardianId, cameraId);

        assertThat(post("/api/dev/live/cameras/" + cameraId + "/sessions", json(Map.of(
            "model", "PC"
        )), token).status()).isEqualTo(400);
        assertThat(post("/api/dev/live/cameras/" + cameraId + "/sessions", json(Map.of(
            "model", "PHONE"
        )), hexToken("b")).status()).isEqualTo(401);
        var sessionId = read(post("/api/dev/live/cameras/" + cameraId + "/sessions", json(Map.of(
            "model", "PHONE"
        )), token)).get("sessionId").asText();
        assertThat(post("/api/dev/live/cameras/" + cameraId + "/sessions", json(Map.of(
            "model", "PHONE"
        )), token).status()).isEqualTo(409);
        assertThat(get("/api/dev/live/patients/" + patientId + "/cameras/" + cameraId + "/session?guardianUserId=" + guardianId, null).status())
            .isEqualTo(200);
        assertThat(get("/api/dev/live/patients/" + patientId + "/cameras/" + cameraId + "/session?guardianUserId=" + outsiderId, null).status())
            .isEqualTo(403);
        assertThat(post("/api/dev/live/patients/" + patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", outsiderId.toString()
        )), null).status()).isEqualTo(403);
        var privateFixture = pairedFixture(hexToken("2"));
        assertThat(put("/api/patients/" + privateFixture.patientId + "/cameras/" + privateFixture.cameraId, json(Map.of(
            "name", "거실 카메라",
            "model", "Pixel 8",
            "privacyMode", "SKELETON_ONLY",
            "sensitivity", 2
        )), null).status()).isEqualTo(200);
        var privateSession = startSession(privateFixture);
        assertThat(privateSession.get("privacyMode").asText()).isEqualTo("SKELETON_ONLY");
        assertThat(privateSession.get("privacyRevision").asLong()).isEqualTo(1L);
        assertThat(get("/api/dev/live/patients/" + privateFixture.patientId + "/cameras/" + privateFixture.cameraId + "/session?guardianUserId=" + privateFixture.guardianId, null).status())
            .isEqualTo(200);
        assertThat(post("/api/dev/live/patients/" + privateFixture.patientId + "/sessions/" + privateSession.get("sessionId").asText() + "/viewers", json(Map.of(
            "guardianUserId", privateFixture.guardianId.toString()
        )), null).status()).isEqualTo(403);

    }

    @Test
    void signalingBoundsViewerGenerationAndLeaveDoNotStopSession() throws Exception {
        var fixture = pairedFixture(hexToken("c"));
        var session = startSession(fixture);
        var sessionId = session.get("sessionId").asText();

        var viewer = read(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", fixture.guardianId.toString()
        )), null));
        assertThat(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", fixture.guardianId.toString()
        )), null).status()).isEqualTo(409);
        var viewerId = viewer.get("viewerId").asText();
        var generation = viewer.get("generation").asLong();
        assertThat(viewerId).matches("[A-Za-z0-9_-]{22}");
        assertThat(read(get("/api/dev/live/patients/" + fixture.patientId + "/cameras/" + fixture.cameraId + "/session?guardianUserId=" + fixture.guardianId, null))
            .get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(read(get("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/status?viewerId=" + viewerId, null))
            .get("viewerCount").asInt()).isEqualTo(1);

        var cameraJoinNotice = read(get("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals?after=0&limit=10", fixture.token));
        assertThat(cameraJoinNotice.get("items").get(0).get("kind").asText()).isEqualTo("VIEWER_JOINED");
        assertThat(cameraJoinNotice.get("items").get(0).get("generation").asLong()).isEqualTo(generation);

        var offer = post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "OFFER",
            "payload", Map.of("type", "offer", "sdp", "test-offer")
        )), fixture.token);
        assertThat(offer.status()).isEqualTo(201);
        assertThat(read(offer).get("generation").asLong()).isEqualTo(generation);
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "OFFER",
            "payload", Map.of("type", "offer", "sdp", "second-offer")
        )), fixture.token).status()).isEqualTo(409);

        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "ANSWER",
            "payload", Map.of("type", "answer", "sdp", "camera-answer")
        )), fixture.token).status()).isEqualTo(400);
        assertThat(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/signals?viewerId=" + viewerId, json(Map.of(
            "kind", "OFFER",
            "payload", Map.of("type", "offer", "sdp", "guardian-offer")
        )), null).status()).isEqualTo(400);

        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "ICE",
            "payload", Map.of("candidate", "x".repeat(5000))
        )), fixture.token).status()).isEqualTo(413);

        var answer = post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/signals?viewerId=" + viewerId, json(Map.of(
            "kind", "ANSWER",
            "payload", Map.of("type", "answer", "sdp", "test-answer")
        )), null);
        assertThat(answer.status()).isEqualTo(201);
        assertThat(read(answer).get("generation").asLong()).isEqualTo(generation);
        assertThat(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/signals?viewerId=" + viewerId, json(Map.of(
            "kind", "ANSWER",
            "payload", Map.of("type", "answer", "sdp", "second-answer")
        )), null).status()).isEqualTo(409);

        for (int index = 1; index <= 32; index++) {
            var signal = post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
                "kind", "ICE",
                "payload", Map.of("candidate", "candidate-" + index)
            )), fixture.token);
            assertThat(signal.status()).isEqualTo(201);
        }
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "ICE",
            "payload", Map.of("candidate", "candidate-33")
        )), fixture.token).status()).isEqualTo(429);

        var signals = read(get("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/signals?viewerId=" + viewerId
            + "&after=0&limit=100", null));
        assertThat(signals.get("items").size()).isEqualTo(35);
        assertThat(signals.get("items").get(0).get("kind").asText()).isEqualTo("VIEWER_JOINED");
        assertThat(signals.get("nextCursor").asLong()).isEqualTo(35L);

        assertThat(delete("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers/" + viewerId).status()).isEqualTo(204);
        var leftNotice = read(get("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals?after=35&limit=10", fixture.token));
        assertThat(leftNotice.get("items").get(0).get("kind").asText()).isEqualTo("VIEWER_LEFT");
        assertThat(leftNotice.get("items").get(0).get("generation").asLong()).isEqualTo(generation);
        assertThat(put("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/lease", null, fixture.token).status()).isEqualTo(200);
        var rejoined = read(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", fixture.guardianId.toString()
        )), null));
        assertThat(rejoined.get("generation").asLong()).isEqualTo(generation + 1);
        var reoffer = post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "OFFER",
            "payload", Map.of("type", "offer", "sdp", "reconnect-offer")
        )), fixture.token);
        assertThat(reoffer.status()).isEqualTo(201);
        assertThat(read(reoffer).get("generation").asLong()).isEqualTo(generation + 1);
    }



    @Test
    void signalPayloadTtlPrunesMailboxAndLateIceIsRejected() throws Exception {
        var fixture = pairedFixture(hexToken("4"));
        var sessionId = startSession(fixture).get("sessionId").asText();
        var viewer = read(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", fixture.guardianId.toString()
        )), null));
        var viewerId = viewer.get("viewerId").asText();

        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "OFFER",
            "payload", Map.of("type", "offer", "sdp", "ttl-offer")
        )), fixture.token).status()).isEqualTo(201);

        for (int step = 1; step <= 7; step++) {
            clock.advance(Duration.ofSeconds(10));
            assertThat(get("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/status?viewerId=" + viewerId, null).status())
                .isEqualTo(200);
            assertThat(put("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/lease", null, fixture.token).status())
                .isEqualTo(200);
        }

        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals", json(Map.of(
            "kind", "ICE",
            "payload", Map.of("candidate", "late-candidate")
        )), fixture.token).status()).isEqualTo(410);
        var signals = read(get("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals?after=0&limit=10", fixture.token));
        assertThat(signals.get("items").size()).isZero();
        assertThat(signals.get("nextCursor").asLong()).isZero();
    }

    @Test
    void revokedCaregiverRelationshipRemovesViewerAndNotifiesCamera() throws Exception {
        var fixture = pairedFixture(hexToken("3"));
        var sessionId = startSession(fixture).get("sessionId").asText();

        var viewer = read(post("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/viewers", json(Map.of(
            "guardianUserId", fixture.guardianId.toString()
        )), null));
        var viewerId = viewer.get("viewerId").asText();
        var generation = viewer.get("generation").asLong();

        jdbc.update("delete from caregiver_links where patient_id = ? and user_id = ?", fixture.patientId, fixture.guardianId);

        assertThat(get("/api/dev/live/patients/" + fixture.patientId + "/sessions/" + sessionId + "/status?viewerId=" + viewerId, null).status())
            .isEqualTo(403);
        var cameraSignals = read(get("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/signals?after=1&limit=10", fixture.token));
        assertThat(cameraSignals.get("items").size()).isEqualTo(1);
        assertThat(cameraSignals.get("items").get(0).get("kind").asText()).isEqualTo("VIEWER_LEFT");
        assertThat(cameraSignals.get("items").get(0).get("generation").asLong()).isEqualTo(generation);
    }

    @Test
    void eventInsertIsAtomicReplaySafeAndImmutable() throws Exception {
        var fixture = pairedFixture(hexToken("d"));
        var sessionId = startSession(fixture).get("sessionId").asText();
        var eventId = UUID.randomUUID();
        var body = eventBody(eventId, 0.91f);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", body, fixture.token));
            var second = pool.submit(() -> post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", body, fixture.token));
            var statuses = java.util.List.of(first.get().status(), second.get().status());
            assertThat(statuses).contains(201);
            assertThat(statuses).contains(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from detection_events where client_event_id = ?", Integer.class, eventId)).isEqualTo(1);
        // R1 이벤트는 긴급 상황을 1건 연다. 동시 재전송이 와도 중복으로 만들지 않는다.
        assertThat(jdbc.queryForObject("select count(*) from incidents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from clips", Integer.class)).isZero();

        var conflict = post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", eventBody(eventId, 0.42f), fixture.token);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + UUID.randomUUID() + "/events", body, fixture.token).status())
            .isEqualTo(404);
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", eventBody(UUID.randomUUID(), 0.91f, "{\"nose\":[1,2]}") , fixture.token).status())
            .isEqualTo(400);

        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/control", json(Map.of("state", "STOPPED")), fixture.token)
            .status()).isEqualTo(200);
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", body, fixture.token).status()).isEqualTo(200);
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", eventBody(UUID.randomUUID(), 0.91f), fixture.token)
            .status()).isEqualTo(410);
    }

    @Test
    void privacyUpdateStopAndUnlinkInvalidateLiveSessionWithGone() throws Exception {
        var fixture = pairedFixture(hexToken("e"));
        var sessionId = startSession(fixture).get("sessionId").asText();

        assertThat(put("/api/patients/" + fixture.patientId + "/cameras/" + fixture.cameraId, json(Map.of(
            "name", "거실 카메라",
            "model", "Pixel 8",
            "privacyMode", "SKELETON_ONLY",
            "sensitivity", 2
        )), null).status()).isEqualTo(200);
        assertThat(put("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/lease", null, fixture.token).status()).isEqualTo(410);

        fixture = pairedFixture(hexToken("f"));
        sessionId = startSession(fixture).get("sessionId").asText();
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/control", json(Map.of("state", "STOPPED")), fixture.token)
            .status()).isEqualTo(200);
        assertThat(put("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/lease", null, fixture.token).status()).isEqualTo(410);
        assertThat(get("/api/dev/live/patients/" + fixture.patientId + "/cameras/" + fixture.cameraId + "/session?guardianUserId=" + fixture.guardianId, null)
            .status()).isEqualTo(410);

        fixture = pairedFixture(hexToken("1"));
        sessionId = startSession(fixture).get("sessionId").asText();
        assertThat(delete("/api/patients/" + fixture.patientId + "/cameras/" + fixture.cameraId).status()).isEqualTo(204);
        assertThat(put("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/lease", null, fixture.token).status()).isEqualTo(410);
    }


    @Test
    void r1EventOpensIncidentAndPatientOkClosesItWithoutClip() throws Exception {
        var fixture = seededPairedFixture(hexToken("e"));
        var sessionId = startSession(fixture).get("sessionId").asText();
        var eventsPath = "/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events";

        // 순간 낙상 판정(FALL/MODEL)만으로는 긴급 상황을 만들지 않는다.
        assertThat(post(eventsPath, fallBody(UUID.randomUUID()), fixture.token).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from incidents", Integer.class)).isZero();

        var eventId = UUID.randomUUID();
        assertThat(post(eventsPath, eventBody(eventId, 0.91f), fixture.token).status()).isEqualTo(201);
        var countdownEndsAt = jdbc.queryForObject("""
            select i.countdown_ends_at from incidents i join detection_events e on e.id = i.detection_event_id
            where e.client_event_id = ?
            """, java.sql.Timestamp.class, eventId).toInstant();
        assertThat(countdownEndsAt).isEqualTo(Instant.parse("2026-10-03T00:00:10Z"));

        var otherCamera = seedCamera(hexToken("f"));
        assertThat(post(patientOkPath(otherCamera, eventId), null, hexToken("f")).status()).isEqualTo(404);
        assertThat(post(patientOkPath(fixture.cameraId, eventId), null, hexToken("f")).status()).isEqualTo(401);

        clock.advance(Duration.ofSeconds(4));
        var ok = post(patientOkPath(fixture.cameraId, eventId), null, fixture.token);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(read(ok).get("resolution").asText()).isEqualTo("PATIENT_OK");
        assertThat(jdbc.queryForObject("""
            select patient_responded and resolved_at is not null from incidents i
            join detection_events e on e.id = i.detection_event_id where e.client_event_id = ?
            """, Boolean.class, eventId)).isTrue();

        // 환자가 괜찮다고 했으면 현장 영상을 받지 않는다.
        assertThat(putClip(fixture, eventId, fakeMp4(64)).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from clips", Integer.class)).isZero();
    }

    @Test
    void clipUploadLinksEventAndOnlyLinkedGuardianCanStreamIt() throws Exception {
        var fixture = seededPairedFixture(hexToken("1"));
        var sessionId = startSession(fixture).get("sessionId").asText();
        var eventId = UUID.randomUUID();
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", eventBody(eventId, 0.91f), fixture.token)
            .status()).isEqualTo(201);
        var detectionEventId = jdbc.queryForObject("select id from detection_events where client_event_id = ?", UUID.class, eventId);

        assertThat(putClip(fixture, eventId, "not a video".getBytes()).status()).isEqualTo(415);
        // 골격만 모드 카메라의 원본 영상은 서버가 받지 않는다.
        jdbc.update("update camera_devices set privacy_mode = 'SKELETON_ONLY'::privacy_mode where id = ?", fixture.cameraId);
        assertThat(putClip(fixture, eventId, fakeMp4(64)).status()).isEqualTo(409);
        jdbc.update("update camera_devices set privacy_mode = 'VIDEO_SKELETON'::privacy_mode where id = ?", fixture.cameraId);
        assertThat(putClip(fixture, UUID.randomUUID(), fakeMp4(64)).status()).isEqualTo(404);

        var video = fakeMp4(4096);
        var created = putClip(fixture, eventId, video);
        assertThat(created.status()).isEqualTo(201);
        var clipId = read(created).get("clipId").asText();
        var replay = putClip(fixture, eventId, fakeMp4(128));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(read(replay).get("clipId").asText()).isEqualTo(clipId);
        assertThat(jdbc.queryForObject("select clip_id from detection_events where id = ?", UUID.class, detectionEventId).toString())
            .isEqualTo(clipId);

        var clipPath = "/api/dev/live/patients/" + fixture.patientId + "/events/" + detectionEventId + "/clip?guardianUserId=";
        var full = getBytes(clipPath + fixture.guardianId, null);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.headers().firstValue("Content-Type")).contains("video/mp4");
        assertThat(full.body()).isEqualTo(video);
        var ranged = getBytes(clipPath + fixture.guardianId, "bytes=0-99");
        assertThat(ranged.statusCode()).isEqualTo(206);
        assertThat(ranged.body()).hasSize(100);

        // 긴급 상황이 열려 있는 동안 이어진 R1은 같은 낙상으로 보고 새 긴급 상황을 만들지 않는다.
        var repeatId = UUID.randomUUID();
        assertThat(post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions/" + sessionId + "/events", eventBody(repeatId, 0.92f), fixture.token)
            .status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from incidents", Integer.class)).isEqualTo(1);
        var noIncident = post(patientOkPath(fixture.cameraId, repeatId), null, fixture.token);
        assertThat(noIncident.status()).isEqualTo(200);
        assertThat(read(noIncident).get("incidentId").isNull()).isTrue();

        var stranger = seedUser("stranger@example.com");
        assertThat(getBytes(clipPath + stranger, null).statusCode()).isEqualTo(403);

        clock.advance(Duration.ofDays(8));
        assertThat(getBytes(clipPath + fixture.guardianId, null).statusCode()).isEqualTo(410);
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-03T00:00:00Z"));
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private JsonNode startSession(Fixture fixture) throws Exception {
        var response = post("/api/dev/live/cameras/" + fixture.cameraId + "/sessions", json(Map.of(
            "model", "PHONE"
        )), fixture.token);
        assertThat(response.status()).isEqualTo(201);
        return read(response);
    }

    private Fixture pairedFixture(String token) throws Exception {
        var cameraId = registerCamera(token);
        var patientId = seedPatient("김환자");
        var guardianId = seedUser("owner-" + token.charAt(0) + "@example.com");
        pairCamera(token, patientId, guardianId, cameraId);
        return new Fixture(token, cameraId, patientId, guardianId);
    }

    /** 등록·페어링 API는 IP당 요청 수가 제한되고 이 클래스 전체가 한도를 공유한다. 추가 테스트는 페어링된 카메라를 DB에 직접 만든다. */
    private Fixture seededPairedFixture(String token) throws Exception {
        var cameraId = seedCamera(token);
        var patientId = seedPatient("김환자");
        var guardianId = seedUser("owner-" + token.charAt(0) + "@example.com");
        linkCaregiver(patientId, guardianId);
        jdbc.update("""
            update camera_devices set patient_id = ?, paired_at = now(), status = 'OFFLINE'::device_status where id = ?
            """, patientId, cameraId);
        return new Fixture(token, cameraId, patientId, guardianId);
    }

    private UUID seedCamera(String token) throws Exception {
        var digest = "sha256:" + java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return jdbc.queryForObject("""
            insert into camera_devices (name, model, device_token, privacy_mode, sensitivity, status)
            values ('거실 카메라', 'Pixel 8', ?, 'VIDEO_SKELETON'::privacy_mode, 2, 'UNPAIRED'::device_status)
            returning id
            """, UUID.class, digest);
    }

    private UUID registerCamera(String token) throws Exception {
        var response = post("/api/camera-devices/register", json(Map.of("model", "Pixel 8")), token);
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(read(response).get("cameraId").asText());
    }

    private void pairCamera(String token, UUID patientId, UUID guardianId, UUID cameraId) throws Exception {
        linkCaregiver(patientId, guardianId);
        var code = read(post("/api/camera-devices/me/pairing-codes", null, token)).get("code").asText();
        var response = post("/api/patients/" + patientId + "/cameras/pair", json(Map.of(
            "code", code,
            "name", "거실 카메라",
            "guardianUserId", guardianId.toString()
        )), null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(read(response).get("id").asText()).isEqualTo(cameraId.toString());
    }

    private String eventBody(UUID eventId, float score) throws IOException {
        return eventBody(eventId, score, "{}");
    }

    private String eventBody(UUID eventId, float score, String keypointsJson) throws IOException {
        return json(Map.ofEntries(
            Map.entry("eventId", eventId.toString()),
            Map.entry("occurredAt", Instant.parse("2026-10-03T00:00:00Z").toString()),
            Map.entry("eventType", "FALLEN_IMMOBILE"),
            Map.entry("kind", "FALLEN_IMMOBILE"),
            Map.entry("rule", "R1"),
            Map.entry("state", "FALLEN"),
            Map.entry("score", score),
            Map.entry("probNormal", 1.0f - score),
            Map.entry("probFall", 0.0f),
            Map.entry("probFallen", score),
            Map.entry("durationSec", 4.25f),
            Map.entry("severity", "HIGH"),
            Map.entry("keypointsJson", keypointsJson)
        ));
    }

    private String fallBody(UUID eventId) throws IOException {
        return json(Map.ofEntries(
            Map.entry("eventId", eventId.toString()),
            Map.entry("occurredAt", Instant.parse("2026-10-03T00:00:00Z").toString()),
            Map.entry("eventType", "FALL"),
            Map.entry("kind", "FALL"),
            Map.entry("rule", "MODEL"),
            Map.entry("state", "FALL"),
            Map.entry("score", 0.9f),
            Map.entry("probNormal", 0.05f),
            Map.entry("probFall", 0.9f),
            Map.entry("probFallen", 0.05f),
            Map.entry("severity", "HIGH"),
            Map.entry("keypointsJson", "{}")
        ));
    }

    private String patientOkPath(UUID cameraId, UUID eventId) {
        return "/api/dev/live/cameras/" + cameraId + "/events/" + eventId + "/patient-ok";
    }

    /** MP4 판별에 쓰는 ftyp 상자만 갖춘 가짜 영상. */
    private byte[] fakeMp4(int size) {
        var bytes = new byte[size];
        System.arraycopy(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, bytes, 0, 12);
        for (int i = 12; i < size; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private Response putClip(Fixture fixture, UUID eventId, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/dev/live/cameras/" + fixture.cameraId + "/events/" + eventId + "/clip?durationSec=10"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + fixture.token)
            .header("Content-Type", "video/mp4")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private HttpResponse<byte[]> getBytes(String path, String range) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET();
        if (range != null) {
            builder.header("Range", range);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private UUID seedUser(String email) {
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into users (id, email, phone, password_hash, name)
            values (?, ?, ?, ?, ?)
            """, id, email, "010-0000-0000", "hash-secret", "테스트 보호자");
        return id;
    }

    private UUID seedPatient(String name) {
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into patients (id, name, age, sex, address, dementia_stage)
            values (?, ?, ?, ?, ?, ?)
            """, id, name, 82, "F", "서울시", "3단계");
        return id;
    }

    private void linkCaregiver(UUID patientId, UUID userId) {
        jdbc.update("""
            insert into caregiver_links (user_id, patient_id, relation, role, notify_fall, notify_still_min, notify_offline)
            values (?, ?, ?::caregiver_relation, ?::caregiver_role, ?, ?, ?)
            """, userId, patientId, "CHILD", "OWNER", true, (short) 5, true);
    }

    private Response get(String path, String token) throws Exception {
        return send("GET", path, null, token);
    }

    private Response post(String path, String body, String token) throws Exception {
        return send("POST", path, body, token);
    }

    private Response put(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token);
    }

    private Response delete(String path) throws Exception {
        return send("DELETE", path, null, null);
    }

    private Response send(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String json(Map<String, ?> body) throws IOException {
        return JSON.writeValueAsString(body);
    }

    private JsonNode read(Response response) throws IOException {
        return JSON.readTree(response.body());
    }

    private String hexToken(String digit) {
        return digit.repeat(64);
    }

    private record Fixture(String token, UUID cameraId, UUID patientId, UUID guardianId) {
    }

    private record Response(int status, String body) {
    }
}
