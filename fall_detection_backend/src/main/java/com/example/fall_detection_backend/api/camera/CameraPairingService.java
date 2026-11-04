package com.example.fall_detection_backend.api.camera;

import com.example.fall_detection_backend.api.live.LiveSessionService;
import com.example.fall_detection_backend.domain.CameraDevice;
import com.example.fall_detection_backend.domain.CameraDeviceRepository;
import com.example.fall_detection_backend.domain.CaregiverLinkRepository;
import com.example.fall_detection_backend.domain.DomainEnums;
import com.example.fall_detection_backend.domain.PairingCodeRepository;
import com.example.fall_detection_backend.domain.PatientRepository;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CameraPairingService {
	private static final Duration PAIRING_TTL = Duration.ofMinutes(5);
	private static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);
	private static final String TOKEN_DIGEST_PREFIX = "sha256:";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Clock clock;
	private final JdbcTemplate jdbc;
	private final CameraDeviceRepository cameras;
	private final PairingCodeRepository pairingCodes;
	private final PatientRepository patients;
	private final CaregiverLinkRepository caregiverLinks;
	private LiveSessionService liveSessions;

	CameraPairingService(
		Clock clock,
		JdbcTemplate jdbc,
		CameraDeviceRepository cameras,
		PairingCodeRepository pairingCodes,
		PatientRepository patients,
		CaregiverLinkRepository caregiverLinks
	) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.cameras = cameras;
		this.pairingCodes = pairingCodes;
		this.patients = patients;
		this.caregiverLinks = caregiverLinks;
	}

	@Autowired(required = false)
	void setLiveSessions(LiveSessionService liveSessions) {
		this.liveSessions = liveSessions;
	}

	public RegistrationResult register(String bearerToken, CameraRegisterRequest request) {
		var digest = digestToken(requireToken(bearerToken));
		var inserted = jdbc.query("""
			insert into camera_devices (name, model, device_token, privacy_mode, sensitivity, status)
			values (?, ?, ?, ?::privacy_mode, ?, ?::device_status)
			on conflict (device_token) do nothing
			returning id
			""", (rs, rowNum) -> (UUID) rs.getObject("id"),
			"거실 카메라",
			request.model(),
			digest,
			DomainEnums.PrivacyMode.VIDEO_SKELETON.name(),
			(short) 2,
			DomainEnums.DeviceStatus.UNPAIRED.name()
		);
		var cameraId = inserted.stream()
			.findFirst()
			.orElseGet(() -> jdbc.queryForObject(
				"select id from camera_devices where device_token = ?",
				(rs, rowNum) -> (UUID) rs.getObject("id"),
				digest
			));
		var camera = cameras.findById(cameraId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카메라를 찾을 수 없습니다."));
		return new RegistrationResult(selfView(camera), !inserted.isEmpty());
	}

	@Transactional
	public PairingCodeResponse issuePairingCode(String bearerToken) {
		var camera = lockAuthenticatedCamera(bearerToken);
		if (camera.getPatientId() != null || camera.getPairedAt() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 보호자 앱에 등록된 카메라입니다.");
		}
		var now = now();
		expireUnusedCodes(camera.getId(), now);

		for (int attempt = 0; attempt < 5; attempt++) {
			var code = randomEightDigitCode();
			var inserted = jdbc.query("""
				insert into pairing_codes (camera_device_id, code, expires_at)
				values (?, ?, ?)
				on conflict (code) do nothing
				returning id
				""", (rs, rowNum) -> (UUID) rs.getObject("id"), camera.getId(), code, Timestamp.from(now.plus(PAIRING_TTL)));
			if (!inserted.isEmpty()) {
				return new PairingCodeResponse(code, now.plus(PAIRING_TTL), now);
			}
		}
		throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "페어링 코드를 만들 수 없습니다. 다시 시도해주세요.");
	}

	@Transactional
	public CameraSelfResponse self(String bearerToken) {
		return selfView(authenticatedCamera(bearerToken));
	}

	@Transactional
	public CameraSelfResponse heartbeat(String bearerToken) {
		var camera = lockAuthenticatedCamera(bearerToken);
		if (camera.getPatientId() == null || camera.getPairedAt() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "보호자 앱에 등록된 뒤 하트비트를 보낼 수 있습니다.");
		}
		camera.setLastSeenAt(now());
		camera.setStatus(DomainEnums.DeviceStatus.ONLINE);
		return selfView(camera);
	}

	@Transactional
	public GuardianCameraResponse claim(UUID patientId, CameraPairRequest request) {
		requirePatient(patientId);
		if (!caregiverLinks.existsByPatientIdAndUserId(patientId, request.guardianUserId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 환자의 보호자만 카메라를 등록할 수 있습니다.");
		}
		var cameraId = jdbc.query("""
			select camera_device_id
			from pairing_codes
			where code = ?
			""", (rs, rowNum) -> (UUID) rs.getObject("camera_device_id"), request.code())
			.stream()
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "페어링 코드가 올바르지 않습니다."));
		var camera = cameras.findLockedById(cameraId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "등록할 수 없는 페어링 코드입니다."));
		var code = pairingCodes.findLockedByCode(request.code())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "페어링 코드가 올바르지 않습니다."));

		if (code.getUsedAt() != null) {
			if (request.guardianUserId().equals(code.getUsedByUserId()) && patientId.equals(camera.getPatientId())) {
				return guardianView(camera);
			}
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 사용된 페어링 코드입니다.");
		}
		var now = now();
		if (isExpired(code.getExpiresAt(), now)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료된 페어링 코드입니다.");
		}
		if (camera.getPatientId() != null && !patientId.equals(camera.getPatientId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 환자에게 등록된 카메라입니다.");
		}
		if (camera.getPatientId() != null || camera.getPairedAt() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 보호자 앱에 등록된 카메라입니다.");
		}

		camera.setPatientId(patientId);
		camera.setName(request.name());
		camera.setPairedAt(now);
		camera.setLastSeenAt(null);
		camera.setStatus(DomainEnums.DeviceStatus.OFFLINE);
		code.setUsedAt(now);
		code.setUsedByUserId(request.guardianUserId());
		return guardianView(camera);
	}

	@Transactional
	public void unlink(UUID patientId, UUID cameraId) {
		requirePatient(patientId);
		var camera = cameras.findLockedById(cameraId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카메라를 찾을 수 없습니다."));
		if (!patientId.equals(camera.getPatientId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "환자와 카메라가 일치하지 않습니다.");
		}
		var now = now();
		expireUnusedCodes(camera.getId(), now);
		camera.setPatientId(null);
		camera.setPairedAt(null);
		camera.setLastSeenAt(null);
		camera.setStatus(DomainEnums.DeviceStatus.UNPAIRED);
		if (liveSessions != null) {
			liveSessions.invalidateCamera(cameraId, "카메라 연결이 해제되어 라이브 세션이 종료되었습니다.");
		}
	}

	public GuardianCameraResponse guardianView(CameraDevice camera) {
		var serverTime = now();
		return new GuardianCameraResponse(
			camera.getId(),
			camera.getPatientId(),
			camera.getName(),
			camera.getModel(),
			camera.getPrivacyMode(),
			camera.getSensitivity(),
			status(camera, serverTime),
			camera.getPairedAt(),
			camera.getLastSeenAt(),
			serverTime
		);
	}

	private CameraSelfResponse selfView(CameraDevice camera) {
		var serverTime = now();
		return new CameraSelfResponse(
			camera.getId(),
			camera.getName(),
			camera.getPatientId() != null && camera.getPairedAt() != null,
			status(camera, serverTime),
			camera.getLastSeenAt(),
			serverTime
		);
	}

	DomainEnums.DeviceStatus status(CameraDevice camera, Instant serverTime) {
		if (camera.getPatientId() == null || camera.getPairedAt() == null) {
			return DomainEnums.DeviceStatus.UNPAIRED;
		}
		if (camera.getLastSeenAt() != null && !camera.getLastSeenAt().isBefore(serverTime.minus(ONLINE_WINDOW))) {
			return DomainEnums.DeviceStatus.ONLINE;
		}
		return DomainEnums.DeviceStatus.OFFLINE;
	}

	boolean isExpired(Instant expiresAt, Instant serverTime) {
		return !serverTime.isBefore(expiresAt);
	}

	private CameraDevice authenticatedCamera(String bearerToken) {
		var digest = digestToken(requireToken(bearerToken));
		return cameras.findByDeviceToken(digest)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 올바르지 않습니다."));
	}

	private CameraDevice lockAuthenticatedCamera(String bearerToken) {
		var digest = digestToken(requireToken(bearerToken));
		return cameras.findLockedByDeviceToken(digest)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 올바르지 않습니다."));
	}

	private String requireToken(String bearerToken) {
		if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 필요합니다.");
		}
		var token = bearerToken.substring("Bearer ".length()).trim();
		if (!token.matches("[0-9a-f]{64}")) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 올바르지 않습니다.");
		}
		return token;
	}

	private String digestToken(String token) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return TOKEN_DIGEST_PREFIX + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 digest is unavailable", e);
		}
	}

	private void requirePatient(UUID patientId) {
		if (!patients.existsById(patientId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "환자를 찾을 수 없습니다.");
		}
	}

	private void expireUnusedCodes(UUID cameraId, Instant expiresAt) {
		var codes = pairingCodes.findByCameraDeviceIdAndUsedAtIsNull(cameraId);
		for (var code : codes) {
			code.setExpiresAt(expiresAt);
		}
	}

	private String randomEightDigitCode() {
		return "%08d".formatted(RANDOM.nextInt(100_000_000));
	}

	private Instant now() {
		return Instant.now(clock);
	}

	public record CameraRegisterRequest(String model) {
		public CameraRegisterRequest {
			if (model != null && model.length() > 80) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카메라 모델명은 80자 이하여야 합니다.");
			}
		}
	}

	public record CameraPairRequest(String code, String name, UUID guardianUserId) {
		public CameraPairRequest {
			if (code == null || !code.matches("\\d{8}")) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페어링 코드는 숫자 8자리여야 합니다.");
			}
			if (name == null || name.isBlank() || name.length() > 60) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카메라 이름을 입력해주세요.");
			}
			if (guardianUserId == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 정보를 입력해주세요.");
			}
		}
	}

	public record PairingCodeResponse(String code, Instant expiresAt, Instant serverTime) {
	}

	public record RegistrationResult(CameraSelfResponse response, boolean created) {
	}

	public record CameraSelfResponse(
		UUID cameraId,
		String name,
		boolean paired,
		DomainEnums.DeviceStatus status,
		Instant lastSeenAt,
		Instant serverTime
	) {
	}

	public record GuardianCameraResponse(
		UUID id,
		UUID patientId,
		String name,
		String model,
		DomainEnums.PrivacyMode privacyMode,
		Short sensitivity,
		DomainEnums.DeviceStatus status,
		Instant pairedAt,
		Instant lastSeenAt,
		Instant serverTime
	) {
	}
}
