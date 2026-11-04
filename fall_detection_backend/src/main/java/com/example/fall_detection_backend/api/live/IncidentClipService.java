package com.example.fall_detection_backend.api.live;

import com.example.fall_detection_backend.domain.DomainEnums;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 낙상 긴급 상황의 카메라 쪽 응답과 현장 클립.
 * 환자가 카운트다운 안에 '괜찮습니다'를 누르면 긴급 상황을 닫고 클립을 받지 않는다.
 * 클립은 해당 R1 이벤트에 1개만 연결하고, 보호자는 환자와 연결된 경우에만 볼 수 있다.
 */
@Service
class IncidentClipService {
	private static final byte[] MP4_FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);

	private final Clock clock;
	private final JdbcTemplate jdbc;
	private final LiveSessionService live;
	private final Path storageRoot;
	private final long maxClipBytes;
	private final Duration retention;

	IncidentClipService(
		Clock clock,
		JdbcTemplate jdbc,
		LiveSessionService live,
		@Value("${careguard.storage.dir:${CAREGUARD_STORAGE_DIR:./data}}") String storageDir,
		@Value("${careguard.clips.max-bytes:20971520}") long maxClipBytes,
		@Value("${careguard.clips.retention-days:7}") long retentionDays
	) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.live = live;
		this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
		this.maxClipBytes = maxClipBytes;
		this.retention = Duration.ofDays(retentionDays);
	}

	@Transactional
	PatientOkResponse patientOk(UUID cameraId, String authorization, UUID clientEventId) {
		live.requireAuthenticatedCamera(cameraId, authorization);
		var event = requireCameraEvent(cameraId, clientEventId);
		var now = clock.instant();
		jdbc.update("""
			update incidents
			set patient_responded = true, patient_responded_at = ?, resolved_at = ?, resolution = 'PATIENT_OK'::incident_resolution
			where detection_event_id = ? and resolved_at is null
			""", Timestamp.from(now), Timestamp.from(now), event.id());
		return jdbc.query("""
			select id, resolution, resolved_at from incidents where detection_event_id = ?
			""", (rs, rowNum) -> new PatientOkResponse(
				(UUID) rs.getObject("id"),
				rs.getString("resolution"),
				rs.getTimestamp("resolved_at").toInstant()
			), event.id())
			.stream()
			.findFirst()
			// 열린 긴급 상황이 있어 서버가 이 R1로 새 긴급 상황을 만들지 않았다면 닫을 것이 없다.
			.orElseGet(() -> new PatientOkResponse(null, null, null));
	}

	/** 본문을 읽기 전에 카메라를 확인한다. 트랜잭션 밖에서 호출해 느린 업로드가 DB 연결을 잡지 않게 한다. */
	void requireCamera(UUID cameraId, String authorization) {
		live.requireAuthenticatedCamera(cameraId, authorization);
	}

	/** 업로드 본문을 상한까지만 읽는다. 트랜잭션 밖에서 호출한다. */
	byte[] readClipBody(InputStream body) {
		return readBounded(body);
	}

	@Transactional
	ClipResponse uploadClip(UUID cameraId, String authorization, UUID clientEventId, byte[] bytes, Instant startedAt, Float durationSec) {
		var camera = live.requireAuthenticatedCamera(cameraId, authorization);
		if (camera.getPrivacyMode() == DomainEnums.PrivacyMode.SKELETON_ONLY) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "골격만 모드 카메라는 현장 영상을 저장하지 않습니다.");
		}
		var event = requireCameraEvent(cameraId, clientEventId);
		var patientDeclined = jdbc.queryForObject("""
			select count(*) from incidents where detection_event_id = ? and resolution = 'PATIENT_OK'::incident_resolution
			""", Integer.class, event.id());
		if (patientDeclined != null && patientDeclined > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "환자가 괜찮다고 응답해 영상을 저장하지 않습니다.");
		}
		if (event.clipId() != null) {
			return new ClipResponse(event.clipId(), event.id(), false);
		}

		if (bytes.length < 8 || !Arrays.equals(Arrays.copyOfRange(bytes, 4, 8), MP4_FTYP)) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MP4 영상만 업로드할 수 있습니다.");
		}

		var storageKey = "clips/" + event.patientId() + "/" + event.id() + ".mp4";
		var target = resolveStorage(storageKey);
		writeAtomically(target, bytes);

		var now = clock.instant();
		var clipId = jdbc.query("""
			insert into clips (camera_device_id, storage_key, status, started_at, duration_sec, size_bytes, mime_type, expires_at, created_at)
			values (?, ?, 'READY'::clip_status, ?, ?, ?, 'video/mp4', ?, ?)
			on conflict (storage_key) do nothing
			returning id
			""", (rs, rowNum) -> (UUID) rs.getObject("id"),
			cameraId,
			storageKey,
			Timestamp.from(startedAt == null ? event.occurredAt() : startedAt),
			durationSec,
			(long) bytes.length,
			Timestamp.from(now.plus(retention)),
			Timestamp.from(now)
		).stream().findFirst().orElseGet(() -> jdbc.queryForObject(
			"select id from clips where storage_key = ?", UUID.class, storageKey));
		jdbc.update("update detection_events set clip_id = ? where id = ? and clip_id is null", clipId, event.id());
		return new ClipResponse(clipId, event.id(), true);
	}

	ClipFile clip(UUID patientId, UUID eventId, UUID guardianUserId) {
		live.requireLinked(patientId, guardianUserId);
		var clip = jdbc.query("""
			select c.storage_key, c.status, c.expires_at, c.size_bytes
			from detection_events e join clips c on c.id = e.clip_id
			where e.id = ? and e.patient_id = ?
			""", (rs, rowNum) -> new StoredClip(
				rs.getString("storage_key"),
				rs.getString("status"),
				rs.getTimestamp("expires_at").toInstant()
			), eventId, patientId).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "현장 영상이 아직 준비되지 않았습니다."));
		if (!"READY".equals(clip.status()) || !clock.instant().isBefore(clip.expiresAt())) {
			throw new ResponseStatusException(HttpStatus.GONE, "보관 기간이 지나 현장 영상을 볼 수 없습니다.");
		}
		var path = resolveStorage(clip.storageKey());
		if (!Files.isRegularFile(path)) {
			throw new ResponseStatusException(HttpStatus.GONE, "현장 영상 파일을 찾을 수 없습니다.");
		}
		return new ClipFile(path);
	}

	private CameraEvent requireCameraEvent(UUID cameraId, UUID clientEventId) {
		return jdbc.query("""
			select id, patient_id, clip_id, occurred_at from detection_events
			where client_event_id = ? and camera_device_id = ?
			""", (rs, rowNum) -> new CameraEvent(
				(UUID) rs.getObject("id"),
				(UUID) rs.getObject("patient_id"),
				(UUID) rs.getObject("clip_id"),
				rs.getTimestamp("occurred_at").toInstant()
			), clientEventId, cameraId).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "감지 이벤트를 찾을 수 없습니다."));
	}

	private byte[] readBounded(InputStream body) {
		try {
			var bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxClipBytes + 1));
			if (bytes.length > maxClipBytes) {
				throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "현장 영상이 허용 크기를 넘었습니다.");
			}
			return bytes;
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현장 영상을 읽지 못했습니다.", e);
		}
	}

	private Path resolveStorage(String storageKey) {
		var path = storageRoot.resolve(storageKey).normalize();
		if (!path.startsWith(storageRoot)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 저장 경로입니다.");
		}
		return path;
	}

	private void writeAtomically(Path target, byte[] bytes) {
		try {
			Files.createDirectories(target.getParent());
			var temp = Files.createTempFile(target.getParent(), ".upload-", ".mp4");
			try {
				Files.write(temp, bytes);
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "현장 영상을 저장하지 못했습니다.", e);
		}
	}

	private record CameraEvent(UUID id, UUID patientId, UUID clipId, Instant occurredAt) {
	}

	private record StoredClip(String storageKey, String status, Instant expiresAt) {
	}

	record ClipFile(Path path) {
	}

	record PatientOkResponse(UUID incidentId, String resolution, Instant resolvedAt) {
	}

	record ClipResponse(UUID clipId, UUID detectionEventId, boolean created) {
	}
}
