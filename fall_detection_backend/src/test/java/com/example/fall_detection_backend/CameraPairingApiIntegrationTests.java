package com.example.fall_detection_backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "CAREGUARD_DISPOSABLE_DB", matches = "true")
class CameraPairingApiIntegrationTests {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
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
    void registerIsIdempotentAndNeverReturnsRawToken() throws Exception {
        var token = hexToken("1");

        var created = post("/api/camera-devices/register", json(Map.of("model", "Pixel 8")), token);
        assertThat(created.status()).isEqualTo(201);
        var createdJson = read(created);
        assertThat(createdJson.get("cameraId").asText()).isNotBlank();
        assertThat(createdJson.get("paired").asBoolean()).isFalse();
        assertThat(createdJson.get("status").asText()).isEqualTo("UNPAIRED");
        assertThat(createdJson.has("deviceToken")).isFalse();

        var retried = post("/api/camera-devices/register", json(Map.of("model", "Pixel 8")), token);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(read(retried).get("cameraId").asText()).isEqualTo(createdJson.get("cameraId").asText());

        var storedToken = jdbc.queryForObject("select device_token from camera_devices", String.class);
        assertThat(storedToken).startsWith("sha256:");
        assertThat(storedToken).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("select count(*) from camera_devices", Integer.class)).isEqualTo(1);

        assertThat(get("/api/camera-devices/me", null).status()).isEqualTo(401);
        assertThat(get("/api/camera-devices/me", "bad-token").status()).isEqualTo(401);
        assertThat(get("/api/camera-devices/me", hexToken("2")).status()).isEqualTo(401);
    }

    @Test
    void codeClaimHeartbeatListAndUnlinkFollowServerState() throws Exception {
        var token = hexToken("3");
        registerCamera(token);
        var patientId = seedPatient("김환자");
        var guardianId = seedUser("owner@example.com");
        linkCaregiver(patientId, guardianId);

        var firstCode = read(post("/api/camera-devices/me/pairing-codes", null, token)).get("code").asText();
        var secondIssue = post("/api/camera-devices/me/pairing-codes", null, token);
        assertThat(secondIssue.status()).isEqualTo(201);
        assertThat(secondIssue.location()).isEmpty();
        var secondCode = read(secondIssue).get("code").asText();
        assertThat(secondCode).matches("\\d{8}");

        var oldClaim = post("/api/patients/" + patientId + "/cameras/pair", pairBody(firstCode, "거실 카메라", guardianId), null);
        assertThat(oldClaim.status()).isEqualTo(400);

        assertThat(post("/api/camera-devices/me/heartbeat", null, token).status()).isEqualTo(409);

        var claim = post("/api/patients/" + patientId + "/cameras/pair", pairBody(secondCode, "거실 카메라", guardianId), null);
        assertThat(claim.status()).isEqualTo(200);
        var cameraId = read(claim).get("id").asText();
        assertThat(read(claim).get("status").asText()).isEqualTo("OFFLINE");
        assertThat(read(claim).get("pairedAt").asText()).isNotBlank();

        var idempotentClaim = post("/api/patients/" + patientId + "/cameras/pair", pairBody(secondCode, "이름 변경 금지", guardianId), null);
        assertThat(idempotentClaim.status()).isEqualTo(200);
        assertThat(read(idempotentClaim).get("name").asText()).isEqualTo("거실 카메라");

        var heartbeat = post("/api/camera-devices/me/heartbeat", null, token);
        assertThat(heartbeat.status()).isEqualTo(200);
        assertThat(read(heartbeat).get("status").asText()).isEqualTo("ONLINE");

        var list = read(get("/api/patients/" + patientId + "/cameras", null));
        assertThat(list.get(0).get("id").asText()).isEqualTo(cameraId);
        assertThat(list.get(0).get("status").asText()).isEqualTo("ONLINE");
        assertThat(list.get(0).get("lastSeenAt").asText()).isNotBlank();
        assertThat(list.get(0).get("serverTime").asText()).isNotBlank();

        jdbc.update(
            "update camera_devices set last_seen_at = current_timestamp - interval '61 seconds' where id = ?",
            UUID.fromString(cameraId)
        );
        var staleList = read(get("/api/patients/" + patientId + "/cameras", null));
        assertThat(staleList.get(0).get("status").asText()).isEqualTo("OFFLINE");

        assertThat(delete("/api/patients/" + patientId + "/cameras/" + cameraId).status()).isEqualTo(204);
        assertThat(post("/api/camera-devices/me/heartbeat", null, token).status()).isEqualTo(409);
        assertThat(read(get("/api/camera-devices/me", token)).get("status").asText()).isEqualTo("UNPAIRED");
    }

    @Test
    void claimRequiresCaregiverLinkAndConcurrentClaimsConsumeCodeOnce() throws Exception {
        var token = hexToken("4");
        registerCamera(token);
        var patientId = seedPatient("김환자");
        var ownerId = seedUser("owner@example.com");
        var memberId = seedUser("member@example.com");
        var outsiderId = seedUser("outsider@example.com");
        linkCaregiver(patientId, ownerId);
        linkCaregiver(patientId, memberId);
        var code = read(post("/api/camera-devices/me/pairing-codes", null, token)).get("code").asText();

        var outsiderClaim = post("/api/patients/" + patientId + "/cameras/pair", pairBody(code, "거실 카메라", outsiderId), null);
        assertThat(outsiderClaim.status()).isEqualTo(403);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> post("/api/patients/" + patientId + "/cameras/pair", pairBody(code, "거실 카메라", ownerId), null));
            var second = pool.submit(() -> post("/api/patients/" + patientId + "/cameras/pair", pairBody(code, "거실 카메라", memberId), null));
            var statuses = java.util.List.of(first.get().status(), second.get().status());
            assertThat(statuses).contains(200);
            assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pairingCodeRateLimitReturnsRetryAfter() throws Exception {
        var token = hexToken("5");
        registerCamera(token);

        assertThat(post("/api/camera-devices/me/pairing-codes", null, token).status()).isEqualTo(201);
        assertThat(post("/api/camera-devices/me/pairing-codes", null, token).status()).isEqualTo(201);
        assertThat(post("/api/camera-devices/me/pairing-codes", null, token).status()).isEqualTo(201);
        var limited = post("/api/camera-devices/me/pairing-codes", null, token);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.retryAfter()).isNotBlank();
    }

    private JsonNode registerCamera(String token) throws Exception {
        var response = post("/api/camera-devices/register", json(Map.of("model", "Pixel 8")), token);
        assertThat(response.status()).isEqualTo(201);
        return read(response);
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

    private String pairBody(String code, String name, UUID guardianUserId) throws IOException {
        return json(Map.of(
            "code", code,
            "name", name,
            "guardianUserId", guardianUserId.toString()
        ));
    }

    private Response get(String path, String token) throws Exception {
        return send("GET", path, null, token);
    }

    private Response post(String path, String body, String token) throws Exception {
        return send("POST", path, body, token);
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
        return new Response(
            response.statusCode(),
            response.body(),
            response.headers().firstValue("Retry-After").orElse(""),
            response.headers().firstValue("Location").orElse("")
        );
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

    private record Response(int status, String body, String retryAfter, String location) {
    }
}
