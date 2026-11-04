package com.example.fall_detection_backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fall_detection_backend.domain.CameraDevice;
import com.example.fall_detection_backend.domain.CameraDeviceRepository;
import com.example.fall_detection_backend.domain.Clip;
import com.example.fall_detection_backend.domain.ClipRepository;
import com.example.fall_detection_backend.domain.DetectionEvent;
import com.example.fall_detection_backend.domain.DetectionEventRepository;
import com.example.fall_detection_backend.domain.DomainEnums.ClipStatus;
import com.example.fall_detection_backend.domain.DomainEnums.DeviceStatus;
import com.example.fall_detection_backend.domain.DomainEnums.EventKind;
import com.example.fall_detection_backend.domain.DomainEnums.EventRule;
import com.example.fall_detection_backend.domain.DomainEnums.PoseState;
import com.example.fall_detection_backend.domain.DomainEnums.Posture;
import com.example.fall_detection_backend.domain.DomainEnums.PrivacyMode;
import com.example.fall_detection_backend.domain.PairingCode;
import com.example.fall_detection_backend.domain.PairingCodeRepository;
import com.example.fall_detection_backend.domain.Patient;
import com.example.fall_detection_backend.domain.PatientRepository;
import com.example.fall_detection_backend.domain.PostureSample;
import com.example.fall_detection_backend.domain.PostureSampleId;
import com.example.fall_detection_backend.domain.PostureSampleRepository;
import com.example.fall_detection_backend.domain.User;
import com.example.fall_detection_backend.domain.UserRepository;
import com.example.fall_detection_backend.domain.WeeklyReport;
import com.example.fall_detection_backend.domain.WeeklyReportRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "CAREGUARD_DISPOSABLE_DB", matches = "true")
class EntityMappingIntegrationTests {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    UserRepository users;

    @Autowired
    PatientRepository patients;

    @Autowired
    CameraDeviceRepository cameras;

    @Autowired
    ClipRepository clips;

    @Autowired
    DetectionEventRepository events;

    @Autowired
    PostureSampleRepository postureSamples;

    @Autowired
    PairingCodeRepository pairingCodes;

    @Autowired
    WeeklyReportRepository weeklyReports;

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
    void repositoryMappingsSupportSharedClipJsonbCompositeIdPairingAndWeeklyReports() {
        var now = Instant.parse("2026-10-02T00:00:00Z");
        var user = users.save(user("owner@example.com"));
        var patient = patients.save(patient("김환자"));
        var camera = cameras.save(camera(patient.getId(), "mapping-camera-token"));

        var clip = new Clip();
        clip.setCameraDeviceId(camera.getId());
        clip.setStorageKey("clips/" + patient.getId() + "/shared.mp4");
        clip.setThumbnailKey("clips/" + patient.getId() + "/shared.jpg");
        clip.setStatus(ClipStatus.READY);
        clip.setStartedAt(now);
        clip.setDurationSec(12.5f);
        clip.setSizeBytes(1_024L);
        clip.setMimeType("video/mp4");
        clip.setExpiresAt(now.plusSeconds(30L * 24 * 60 * 60));
        clip = clips.save(clip);
        var clipId = clip.getId();

        var keypoints = "[[12.5,20.0,0.99],[14.0,25.5,0.87]]";
        var firstEvent = events.save(event(patient.getId(), camera.getId(), clipId, now.plusSeconds(60), keypoints));
        var secondEvent = events.save(event(patient.getId(), camera.getId(), clipId, now.plusSeconds(120), "[[1,2,0.5]]"));

        var postureId = new PostureSampleId();
        postureId.setCameraDeviceId(camera.getId());
        postureId.setMinuteAt(now.plusSeconds(180));
        var posture = new PostureSample();
        posture.setId(postureId);
        posture.setDominant(Posture.LYING);
        posture.setLyingRatio(0.8f);
        posture.setMotion(0.12f);
        posture.setDetectRate(0.95f);
        posture.setFps(18.5f);
        postureSamples.save(posture);

        var pairingCode = new PairingCode();
        pairingCode.setCameraDeviceId(camera.getId());
        pairingCode.setCode("ABC12345");
        pairingCode.setExpiresAt(now.plusSeconds(300));
        pairingCode.setUsedAt(now.plusSeconds(30));
        pairingCode.setUsedByUserId(user.getId());
        pairingCodes.save(pairingCode);

        var report = new WeeklyReport();
        report.setPatientId(patient.getId());
        report.setWeekStart(LocalDate.of(2026, 9, 28));
        report.setAbnormalCount(4);
        report.setFallCount(2);
        report.setUptimePercent(98.5f);
        report.setAvgActiveHours(6.25f);
        report.setAvgLyingNightHours(8.5f);
        report.setSummaryText("낮 활동이 유지되었습니다.");
        report.setPdfKey("reports/" + patient.getId() + "/2026-09-28.pdf");
        weeklyReports.save(report);

        entityManager.flush();
        entityManager.clear();

        assertThat(events.findById(firstEvent.getId())).get().satisfies(reloaded -> {
            assertThat(reloaded.getClipId()).isEqualTo(clipId);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            assertThat(mapper.readTree(reloaded.getKeypoints())).isEqualTo(mapper.readTree(keypoints));
        });
        assertThat(events.findById(secondEvent.getId())).get()
            .extracting(DetectionEvent::getClipId)
            .isEqualTo(clipId);
        assertThat(postureSamples.findById(postureId)).get().satisfies(reloaded -> {
            assertThat(reloaded.getDominant()).isEqualTo(Posture.LYING);
            assertThat(reloaded.getDetectRate()).isEqualTo(0.95f);
        });
        assertThat(pairingCodes.findById(pairingCode.getId())).get().satisfies(reloaded -> {
            assertThat(reloaded.getCode()).isEqualTo("ABC12345");
            assertThat(reloaded.getUsedByUserId()).isEqualTo(user.getId());
        });
        assertThat(weeklyReports.findById(report.getId())).get().satisfies(reloaded -> {
            assertThat(reloaded.getPatientId()).isEqualTo(patient.getId());
            assertThat(reloaded.getWeekStart()).isEqualTo(LocalDate.of(2026, 9, 28));
            assertThat(reloaded.getAbnormalCount()).isEqualTo(4);
            assertThat(reloaded.getPdfKey()).endsWith("/2026-09-28.pdf");
        });
    }

    private User user(String email) {
        var user = new User();
        user.setEmail(email);
        user.setPhone("010-0000-0000");
        user.setPasswordHash("hash-secret");
        user.setName("보호자");
        user.setFcmToken("push-secret");
        return user;
    }

    private Patient patient(String name) {
        var patient = new Patient();
        patient.setName(name);
        patient.setAge((short) 82);
        patient.setSex("F");
        patient.setAddress("서울시");
        patient.setDementiaStage("3단계");
        return patient;
    }

    private CameraDevice camera(UUID patientId, String token) {
        var camera = new CameraDevice();
        camera.setPatientId(patientId);
        camera.setName("거실 카메라");
        camera.setModel("Pixel 8");
        camera.setDeviceToken(token);
        camera.setPrivacyMode(PrivacyMode.VIDEO_SKELETON);
        camera.setSensitivity((short) 2);
        camera.setStatus(DeviceStatus.ONLINE);
        return camera;
    }

    private DetectionEvent event(UUID patientId, UUID cameraId, UUID clipId, Instant occurredAt, String keypoints) {
        var event = new DetectionEvent();
        event.setClientEventId(UUID.randomUUID());
        event.setPatientId(patientId);
        event.setCameraDeviceId(cameraId);
        event.setOccurredAt(occurredAt);
        event.setKind(EventKind.FALL);
        event.setRule(EventRule.R1);
        event.setState(PoseState.FALLEN);
        event.setProbNormal(0.05f);
        event.setProbFall(0.85f);
        event.setProbFallen(0.10f);
        event.setDurationSec(7.5f);
        event.setKeypoints(keypoints);
        event.setClipId(clipId);
        return event;
    }
}
