package com.example.fall_detection_backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class ApiCrudIntegrationTests {
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
    void setupCrudRejectsInvalidAgeAndDoesNotExposeSecrets() throws Exception {
        var user = post("/api/users", json(Map.of(
            "email", "owner@example.com",
            "phone", "010-1111-2222",
            "name", "보호자"
        )));
        assertThat(user.status()).isEqualTo(201);
        var userJson = read(user);
        assertThat(userJson.get("id").asText()).isNotBlank();
        assertThat(userJson.has("passwordHash")).isFalse();
        assertThat(userJson.has("fcmToken")).isFalse();

        var invalidPatient = post("/api/patients", json(Map.of(
            "name", "김환자",
            "age", 131,
            "sex", "F",
            "address", "서울시",
            "dementiaStage", "3단계"
        )));
        assertThat(invalidPatient.status()).isEqualTo(400);

        var patient = post("/api/patients", json(Map.of(
            "name", "김환자",
            "age", 82,
            "sex", "F",
            "address", "서울시",
            "dementiaStage", "3단계"
        )));
        assertThat(patient.status()).isEqualTo(201);
        var patientId = read(patient).get("id").asText();

        var camera = post("/api/patients/" + patientId + "/cameras", json(Map.of(
            "name", "거실 카메라",
            "model", "Pixel 8",
            "privacyMode", "VIDEO_SKELETON",
            "sensitivity", 2
        )));
        assertThat(camera.status()).isEqualTo(201);
        var cameraJson = read(camera);
        assertThat(cameraJson.get("id").asText()).isNotBlank();
        assertThat(cameraJson.has("deviceToken")).isFalse();

        var contact = post("/api/patients/" + patientId + "/contacts", json(Map.of(
            "name", "비가입 가족",
            "relation", "CHILD",
            "phone", "010-3333-4444",
            "sortOrder", 1
        )));
        assertThat(contact.status()).isEqualTo(201);
        var contactId = read(contact).get("id").asText();
        assertThat(get("/api/patients/" + patientId + "/contacts").body()).contains(contactId);

        var caregiver = post("/api/patients/" + patientId + "/caregivers", json(Map.of(
            "userId", userJson.get("id").asText(),
            "relation", "CHILD",
            "role", "OWNER",
            "notifyFall", true,
            "notifyStillMin", 5,
            "notifyOffline", true
        )));
        assertThat(caregiver.status()).isEqualTo(201);
        assertThat(read(caregiver).get("id").asText()).isNotBlank();
    }

    @Test
    void recordsEventsAndNotesCrudKeepPatientBoundariesAndCascadeNotes() throws Exception {
        var patientA = seedPatient("김환자");
        var patientB = seedPatient("이환자");
        var author = seedUser("writer@example.com");
        var cameraA = seedCamera(patientA, "camera-a-token");
        var cameraB = seedCamera(patientB, "camera-b-token");

        var record = post("/api/patients/" + patientA + "/records", json(Map.of(
            "authorUserId", author.toString(),
            "recordedAt", "2026-10-02T02:00:00Z",
            "category", "SYMPTOM",
            "condition", "DIZZY",
            "title", "어지러움 호소",
            "memo", "아침 식사 후 어지럽다고 말함"
        )));
        assertThat(record.status()).isEqualTo(201);
        var recordId = read(record).get("id").asText();

        var updatedRecord = put("/api/patients/" + patientA + "/records/" + recordId, json(Map.of(
            "authorUserId", author.toString(),
            "recordedAt", "2026-10-02T02:05:00Z",
            "category", "SYMPTOM",
            "condition", "GAIT",
            "title", "걸음 불안정",
            "memo", "화장실 이동 중 비틀거림"
        )));
        assertThat(updatedRecord.status()).isEqualTo(200);
        assertThat(read(updatedRecord).get("title").asText()).isEqualTo("걸음 불안정");

        var duplicateEventBody = eventBody(cameraA, "2026-10-02T09:10:00Z");
        var event = post("/api/patients/" + patientA + "/events", duplicateEventBody);
        assertThat(event.status()).isEqualTo(201);
        var eventId = read(event).get("id").asText();
        assertThat(post("/api/patients/" + patientA + "/events", duplicateEventBody).status()).isEqualTo(409);

        var crossPatientCamera = post("/api/patients/" + patientA + "/events", eventBody(cameraB, "2026-10-02T09:20:00Z"));
        assertThat(crossPatientCamera.status()).isEqualTo(404);

        var manualNote = post("/api/patients/" + patientA + "/notes", json(Map.of(
            "careRecordId", recordId,
            "authorUserId", author.toString(),
            "kind", "OBSERVE",
            "patientState", "UNSURE",
            "memo", "30분간 더 지켜보기로 함"
        )));
        assertThat(manualNote.status()).isEqualTo(201);
        var manualNoteId = read(manualNote).get("id").asText();

        var eventNote = post("/api/patients/" + patientA + "/notes", json(Map.of(
            "detectionEventId", eventId,
            "authorUserId", author.toString(),
            "kind", "CALL",
            "patientState", "FINE",
            "memo", "전화 확인 완료"
        )));
        assertThat(eventNote.status()).isEqualTo(201);
        var eventNoteId = read(eventNote).get("id").asText();

        var missingTarget = post("/api/patients/" + patientA + "/notes", json(Map.of(
            "authorUserId", author.toString(),
            "kind", "ETC",
            "memo", "대상 없음"
        )));
        assertThat(missingTarget.status()).isEqualTo(400);

        var doubleTarget = post("/api/patients/" + patientA + "/notes", json(Map.of(
            "detectionEventId", eventId,
            "careRecordId", recordId,
            "authorUserId", author.toString(),
            "kind", "ETC",
            "memo", "대상 둘"
        )));
        assertThat(doubleTarget.status()).isEqualTo(400);

        assertThat(get("/api/patients/" + patientB + "/notes/" + manualNoteId).status()).isEqualTo(404);
        assertThat(read(get("/api/patients/" + patientA + "/notes?careRecordId=" + recordId)).size()).isEqualTo(1);
        assertThat(read(get("/api/patients/" + patientA + "/notes?detectionEventId=" + eventId)).size()).isEqualTo(1);

        assertThat(delete("/api/patients/" + patientA + "/records/" + recordId).status()).isEqualTo(204);
        assertThat(get("/api/patients/" + patientA + "/notes/" + manualNoteId).status()).isEqualTo(404);
        assertThat(get("/api/patients/" + patientA + "/notes/" + eventNoteId).status()).isEqualTo(200);

        assertThat(delete("/api/patients/" + patientA + "/events/" + eventId).status()).isEqualTo(204);
        assertThat(get("/api/patients/" + patientA + "/notes/" + eventNoteId).status()).isEqualTo(404);
    }

    @Test
    void timelineGroupsByOccurrenceTimeAndCalendarCountsRecordsAndEventsOnly() throws Exception {
        var patient = seedPatient("김환자");
        var author = seedUser("timeline@example.com");
        var camera = seedCamera(patient, "camera-timeline-token");

        postRecord(patient, author, "2026-10-01T16:30:00Z", "새벽 수동 기록");
        postEvent(patient, camera, "2026-10-01T23:30:00Z");
        postRecord(patient, author, "2026-10-02T01:00:00Z", "아침 수동 기록");
        var afternoonRecord = postRecord(patient, author, "2026-10-02T06:10:00Z", "오후 수동 기록");
        postEvent(patient, camera, "2026-10-02T09:00:00Z");
        postRecord(patient, author, "2026-10-02T12:00:00Z", "저녁 수동 기록");

        var note = post("/api/patients/" + patient + "/notes", json(Map.of(
            "careRecordId", read(afternoonRecord).get("id").asText(),
            "authorUserId", author.toString(),
            "kind", "OBSERVE",
            "memo", "캘린더 카운트에서 제외되어야 하는 대응 메모"
        )));
        assertThat(note.status()).isEqualTo(201);

        postRecord(patient, author, "2026-10-03T01:00:00Z", "다음날 기록 1");
        postEvent(patient, camera, "2026-10-03T02:00:00Z");
        postRecord(patient, author, "2026-10-04T01:00:00Z", "사흘째 기록 1");
        postRecord(patient, author, "2026-10-04T02:00:00Z", "사흘째 기록 2");
        postEvent(patient, camera, "2026-10-04T03:00:00Z");

        var timeline = read(get("/api/patients/" + patient + "/timeline?date=2026-10-02"));
        assertThat(timeline.isArray()).isTrue();
        assertSection(timeline, "DAWN", 1);
        assertSection(timeline, "MORNING", 2);
        assertSection(timeline, "AFTERNOON", 1);
        assertSection(timeline, "EVENING", 2);

        var calendar = read(get("/api/patients/" + patient + "/calendar?month=2026-10"));
        assertDay(calendar, "2026-10-02", 6, "HIGH");
        assertDay(calendar, "2026-10-03", 2, "LOW");
        assertDay(calendar, "2026-10-04", 3, "MEDIUM");
    }

    @Test
    void missingPatientReturnsNotFound() throws Exception {
        assertThat(get("/api/patients/" + UUID.randomUUID() + "/records").status()).isEqualTo(404);
    }

    private UUID seedUser(String email) {
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into users (id, email, phone, password_hash, name, fcm_token)
            values (?, ?, ?, ?, ?, ?)
            """, id, email, "010-0000-0000", "hash-secret", "테스트 보호자", "push-secret");
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

    private UUID seedCamera(UUID patientId, String token) {
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into camera_devices (id, patient_id, name, model, device_token, privacy_mode, sensitivity, status)
            values (?, ?, ?, ?, ?, ?::privacy_mode, ?, ?::device_status)
            """, id, patientId, "거실 카메라", "Pixel 8", token, "VIDEO_SKELETON", 2, "ONLINE");
        return id;
    }

    private Response postRecord(UUID patientId, UUID authorUserId, String recordedAt, String title) throws Exception {
        return post("/api/patients/" + patientId + "/records", json(Map.of(
            "authorUserId", authorUserId.toString(),
            "recordedAt", recordedAt,
            "category", "SYMPTOM",
            "condition", "DIZZY",
            "title", title,
            "memo", title + " 메모"
        )));
    }

    private Response postEvent(UUID patientId, UUID cameraId, String occurredAt) throws Exception {
        return post("/api/patients/" + patientId + "/events", eventBody(cameraId, occurredAt));
    }

    private String eventBody(UUID cameraId, String occurredAt) throws IOException {
        return json(Map.of(
            "clientEventId", UUID.randomUUID().toString(),
            "cameraDeviceId", cameraId.toString(),
            "occurredAt", occurredAt,
            "kind", "FALL",
            "rule", "R1",
            "state", "FALLEN",
            "probNormal", 0.05,
            "probFall", 0.85,
            "probFallen", 0.10,
            "durationSec", 7.5
        ));
    }

    private void assertSection(JsonNode sections, String period, int count) {
        var section = nodes(sections).stream()
            .filter(node -> period.equals(node.get("period").asText()))
            .findFirst();
        assertThat(section).as("timeline section %s", period).isPresent();
        assertThat(section.get().get("count").asInt()).isEqualTo(count);
        assertThat(section.get().get("entries").size()).isEqualTo(count);
    }

    private void assertDay(JsonNode days, String date, int count, String level) {
        var day = nodes(days).stream()
            .filter(node -> date.equals(node.get("date").asText()))
            .findFirst();
        assertThat(day).as("calendar day %s", date).isPresent();
        assertThat(day.get().get("count").asInt()).isEqualTo(count);
        assertThat(day.get().get("level").asText()).isEqualTo(level);
    }

    private List<JsonNode> nodes(JsonNode array) {
        var result = new ArrayList<JsonNode>();
        for (var node : array) {
            result.add(node);
        }
        return result;
    }

    private Response get(String path) throws Exception {
        return send("GET", path, null);
    }

    private Response post(String path, String body) throws Exception {
        return send("POST", path, body);
    }

    private Response put(String path, String body) throws Exception {
        return send("PUT", path, body);
    }

    private Response delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private Response send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json");
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

    private record Response(int status, String body) {
    }
}
