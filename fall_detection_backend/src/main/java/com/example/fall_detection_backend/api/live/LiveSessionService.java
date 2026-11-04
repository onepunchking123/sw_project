package com.example.fall_detection_backend.api.live;

import com.example.fall_detection_backend.domain.CameraDevice;
import com.example.fall_detection_backend.domain.CameraDeviceRepository;
import com.example.fall_detection_backend.domain.CaregiverLinkRepository;
import com.example.fall_detection_backend.domain.DetectionEventRepository;
import com.example.fall_detection_backend.domain.DomainEnums;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveSessionService {
	private static final Duration SESSION_TTL = Duration.ofSeconds(15);
	private static final Duration LEASE_TTL = Duration.ofSeconds(10);
	private static final Duration CONTROL_TTL = Duration.ofSeconds(10);
	private static final Duration TOMBSTONE_TTL = Duration.ofSeconds(120);
	private static final Duration SIGNAL_RATE_WINDOW = Duration.ofMinutes(1);
	private static final Duration SIGNAL_TTL = Duration.ofSeconds(60);
	private static final Duration ICE_WINDOW = Duration.ofSeconds(30);
	/** 환자가 카메라 폰에서 '괜찮습니다'로 취소할 수 있는 시간. 지나면 보호자 앱이 긴급 화면을 띄운다. */
	static final Duration INCIDENT_COUNTDOWN = Duration.ofSeconds(10);
	/** 이 시간 안에 열린 긴급 상황이 아직 처리 중이면 이어진 R1을 같은 낙상으로 본다. */
	private static final Duration INCIDENT_DEDUP_WINDOW = Duration.ofSeconds(60);
	private static final int QUEUE_LIMIT = 64;
	private static final int VIEWER_LIMIT = 1;
	private static final int SESSION_LIMIT = 64;
	private static final int TOMBSTONE_LIMIT = 128;
	private static final int ICE_LIMIT_PER_SIDE = 32;
	private static final int SIGNAL_RATE_LIMIT = 60;
	private static final int MAX_SIGNAL_TOTAL_BYTES = 128 * 1024;
	private static final int MAX_SIGNAL_PAYLOAD_BYTES = 64 * 1024;
	private static final int MAX_ICE_PAYLOAD_BYTES = 4 * 1024;
	private static final String TOKEN_DIGEST_PREFIX = "sha256:";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Object lock = new Object();
	private final Clock clock;
	private final JdbcTemplate jdbc;
	private final CameraDeviceRepository cameras;
	private final CaregiverLinkRepository caregiverLinks;
	private final DetectionEventRepository detectionEvents;
	private final Set<UUID> guardianAllowlist;
	private final Map<UUID, LiveSession> sessions = new HashMap<>();
	private final Map<UUID, Tombstone> tombstones = new HashMap<>();
	private final Map<UUID, EventProvenance> eventProvenance = new HashMap<>();

	LiveSessionService(
		Clock clock,
		JdbcTemplate jdbc,
		CameraDeviceRepository cameras,
		CaregiverLinkRepository caregiverLinks,
		DetectionEventRepository detectionEvents,
		@Value("${careguard.live.guardian-allowlist:${CAREGUARD_LIVE_GUARDIAN_ALLOWLIST:}}") String guardianAllowlist
	) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.cameras = cameras;
		this.caregiverLinks = caregiverLinks;
		this.detectionEvents = detectionEvents;
		this.guardianAllowlist = parseGuardianAllowlist(guardianAllowlist);
	}

	public SessionResponse start(UUID cameraId, String authorization, StartSessionRequest request) {
		if (request.model() != LiveModel.PHONE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHONE 모델만 라이브 추론 세션을 시작할 수 있습니다.");
		}
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			requirePairedCamera(camera);
			var active = sessions.values().stream()
				.filter(candidate -> candidate.cameraId.equals(cameraId))
				.findFirst();
			if (active.isPresent()) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 라이브 세션이 있습니다.");
			}
			var privacyRevision = privacyRevision(camera);
			var session = new LiveSession(
				UUID.randomUUID(),
				cameraId,
				camera.getPatientId(),
				camera.getPrivacyMode(),
				privacyRevision,
				LiveControlState.RUNNING,
				now.plus(LEASE_TTL),
				now.plus(CONTROL_TTL),
				now.plus(SESSION_TTL),
				now
			);
			sessions.put(session.id, session);
			trimSessions(now);
			return view(session, now);
		}
	}

	public SessionResponse renewLease(UUID cameraId, UUID sessionId, String authorization) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			var session = requireCameraSession(cameraId, sessionId, now);
			requireCurrentCameraSession(session, camera, now);
			extend(session, now, true, false);
			return view(session, now);
		}
	}

	public SessionResponse control(UUID cameraId, UUID sessionId, String authorization, ControlRequest request) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			var session = requireCameraSession(cameraId, sessionId, now);
			requireCurrentCameraSession(session, camera, now);
			session.controlState = request.state();
			extend(session, now, false, true);
			if (request.state() == LiveControlState.STOPPED) {
				tombstone(session, now);
			}
			return view(session, now);
		}
	}

	public SignalCreatedResponse cameraSignal(UUID cameraId, UUID sessionId, String authorization, SignalRequest request) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			var session = requireCameraSession(cameraId, sessionId, now);
			requireCurrentCameraSession(session, camera, now);
			var sequence = enqueue(session, SignalSender.CAMERA, request.kind(), request.payload(), now);
			extend(session, now, true, false);
			return new SignalCreatedResponse(sequence, session.currentGeneration, now);
		}
	}

	public SignalListResponse cameraSignals(UUID cameraId, UUID sessionId, String authorization, long after, int limit) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			var session = requireCameraSession(cameraId, sessionId, now);
			requireCurrentCameraSession(session, camera, now);
			return signalList(session, after, limit, now);
		}
	}

	public SessionResponse discover(UUID patientId, UUID cameraId, UUID guardianUserId) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			requireLinked(patientId, guardianUserId);
			var session = sessions.values().stream()
				.filter(candidate -> candidate.patientId.equals(patientId) && candidate.cameraId.equals(cameraId))
				.findFirst()
				.orElse(null);
			if (session != null) {
				return view(session, now);
			}
			var tombstone = tombstones.values().stream()
				.filter(candidate -> candidate.patientId().equals(patientId) && candidate.cameraId().equals(cameraId))
				.findFirst()
				.orElse(null);
			if (tombstone != null && now.isBefore(tombstone.expiresAt())) {
				throw new ResponseStatusException(HttpStatus.GONE, tombstone.reason());
			}
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "라이브 세션을 찾을 수 없습니다.");
		}
	}

	public SignalCreatedResponse guardianSignal(UUID patientId, UUID sessionId, String viewerId, SignalRequest request) {
		if (request.kind() == SignalKind.OFFER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 앱은 ANSWER 또는 ICE만 보낼 수 있습니다.");
		}
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var session = requireGuardianSession(patientId, sessionId, now);
			requireViewer(session, viewerId, now);
			var sequence = enqueue(session, SignalSender.GUARDIAN, request.kind(), request.payload(), now);
			return new SignalCreatedResponse(sequence, session.currentGeneration, now);
		}
	}

	public SignalListResponse signals(UUID patientId, UUID sessionId, String viewerId, long after, int limit) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var session = requireGuardianSession(patientId, sessionId, now);
			requireViewer(session, viewerId, now);
			return signalList(session, after, limit, now);
		}
	}

	public ViewerResponse join(UUID patientId, UUID sessionId, ViewerRequest request) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			requireLinked(patientId, request.guardianUserId());
			var session = requireGuardianSession(patientId, sessionId, now);
			if (session.privacyMode == DomainEnums.PrivacyMode.SKELETON_ONLY) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 개인정보 설정에서는 라이브 시청을 시작할 수 없습니다.");
			}
			pruneExpiredViewers(session, now);
			if (session.viewers.size() >= VIEWER_LIMIT) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "라이브 시청자 수가 가득 찼습니다.");
			}
			var viewerId = randomCapability();
			var expiresAt = now.plus(SESSION_TTL);
			var generation = ++session.currentGeneration;
			session.currentGenerationStartedAt = now;
			session.signals.clear();
			session.signalBytes = 0;
			session.viewers.put(viewerId, new Viewer(viewerId, request.guardianUserId(), generation, expiresAt));
			enqueueSystem(session, SignalKind.VIEWER_JOINED, Map.of("viewerId", viewerId, "generation", generation), now);
			return new ViewerResponse(viewerId, generation, expiresAt, now);
		}
	}

	public void leave(UUID patientId, UUID sessionId, String viewerId) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var session = requireGuardianSession(patientId, sessionId, now);
			removeViewer(session, viewerId, now);
		}
	}

	public SessionResponse status(UUID patientId, UUID sessionId, String viewerId) {
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var session = requireGuardianSession(patientId, sessionId, now);
			requireViewer(session, viewerId, now);
			return view(session, now);
		}
	}

	@Transactional
	public EventResponse event(UUID cameraId, UUID sessionId, String authorization, LiveEventRequest request) {
		var canonical = request.canonical(cameraId);
		synchronized (lock) {
			var now = now();
			cleanup(now);
			var existing = replayIfExisting(request, canonical, sessionId, authorization);
			if (existing != null) {
				return existing;
			}
			var camera = requireAuthenticatedCamera(cameraId, authorization);
			var session = requireCameraSession(cameraId, sessionId, now);
			requireCurrentCameraSession(session, camera, now);
			var created = insertEvent(session, request, canonical, now);
			if (created) {
				eventProvenance.put(request.eventId(), new EventProvenance(
					request.eventId(),
					session.id,
					session.cameraId,
					session.patientId,
					now.plus(TOMBSTONE_TTL)
				));
			}
			return new EventResponse(request.eventId(), session.id, session.cameraId, session.patientId, created, now);
		}
	}

	public void invalidateCamera(UUID cameraId, String reason) {
		synchronized (lock) {
			var now = now();
			var affected = sessions.values().stream()
				.filter(session -> session.cameraId.equals(cameraId))
				.map(session -> session.id)
				.toList();
			for (var sessionId : affected) {
				var session = sessions.get(sessionId);
				if (session != null) {
					tombstone(session, now, reason);
				}
			}
			trimTombstones();
		}
	}

	private boolean insertEvent(LiveSession session, LiveEventRequest request, CanonicalEvent canonical, Instant now) {
		var inserted = jdbc.query("""
			insert into detection_events (
				client_event_id, patient_id, camera_device_id, occurred_at, kind, rule, state,
				prob_normal, prob_fall, prob_fallen, duration_sec, keypoints, created_at
			)
			values (?, ?, ?, ?, ?::event_kind, ?::event_rule, ?::pose_state, ?, ?, ?, ?, ?::jsonb, ?)
			on conflict (client_event_id) do nothing
			returning id
			""", (rs, rowNum) -> (UUID) rs.getObject("id"),
			request.eventId(),
			session.patientId,
			session.cameraId,
			Timestamp.from(request.occurredAt()),
			canonical.kind().name(),
			canonical.rule().name(),
			canonical.state().name(),
			canonical.probNormal(),
			canonical.probFall(),
			canonical.probFallen(),
			canonical.durationSec(),
			canonical.keypointsJson(),
			Timestamp.from(now)
		);
		if (!inserted.isEmpty()) {
			if (canonical.kind() == DomainEnums.EventKind.FALLEN_IMMOBILE && canonical.rule() == DomainEnums.EventRule.R1) {
				openIncident(inserted.get(0), session.patientId, request.occurredAt(), now);
			}
			return true;
		}
		var existing = detectionEvents.findByClientEventId(request.eventId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "감지 이벤트 상태를 확인할 수 없습니다."));
		if (!Objects.equals(existing.getPatientId(), session.patientId)
			|| !Objects.equals(existing.getCameraDeviceId(), session.cameraId)
			|| !Objects.equals(existing.getOccurredAt(), request.occurredAt())
			|| existing.getKind() != canonical.kind()
			|| existing.getRule() != canonical.rule()
			|| existing.getState() != canonical.state()
			|| !Objects.equals(existing.getProbNormal(), canonical.probNormal())
			|| !Objects.equals(existing.getProbFall(), canonical.probFall())
			|| !Objects.equals(existing.getProbFallen(), canonical.probFallen())
			|| !Objects.equals(existing.getDurationSec(), canonical.durationSec())
			|| !Objects.equals(existing.getKeypoints(), canonical.keypointsJson())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 eventId로 다른 감지 이벤트를 저장할 수 없습니다.");
		}
		return false;
	}

	/**
	 * 넘어진 뒤 3초 이상 쓰러져 있는 R1 이벤트만 긴급 상황으로 올린다.
	 * 같은 환자에게 방금 연 긴급 상황이 아직 열려 있으면 새로 만들지 않는다. 카메라도 처리 중에는 다음 낙상을 받지 않는다.
	 */
	private void openIncident(UUID detectionEventId, UUID patientId, Instant occurredAt, Instant now) {
		var startedAt = occurredAt.isAfter(now) ? now : occurredAt;
		jdbc.update("""
			insert into incidents (detection_event_id, patient_id, started_at, countdown_ends_at, created_at)
			select ?, ?, ?, ?, ?
			where not exists (
				select 1 from incidents where patient_id = ? and resolved_at is null and created_at > ?
			)
			on conflict (detection_event_id) do nothing
			""",
			detectionEventId, patientId, Timestamp.from(startedAt), Timestamp.from(now.plus(INCIDENT_COUNTDOWN)), Timestamp.from(now),
			patientId, Timestamp.from(now.minus(INCIDENT_DEDUP_WINDOW)));
	}

	private EventResponse replayIfExisting(
		LiveEventRequest request,
		CanonicalEvent canonical,
		UUID requestedSessionId,
		String authorization
	) {
		var existing = detectionEvents.findByClientEventId(request.eventId()).orElse(null);
		if (existing == null) {
			return null;
		}
		requireAuthenticatedCamera(existing.getCameraDeviceId(), authorization);
		if (!sameCanonicalEvent(existing, canonical, request)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 eventId로 다른 감지 이벤트를 저장할 수 없습니다.");
		}
		var now = now();
		var provenance = eventProvenance.get(request.eventId());
		if (provenance == null
			|| !provenance.sessionId().equals(requestedSessionId)
			|| !provenance.cameraId().equals(existing.getCameraDeviceId())
			|| !provenance.patientId().equals(existing.getPatientId())
			|| !now.isBefore(provenance.expiresAt())) {
			throw tombstoneOrNotFound(requestedSessionId, now);
		}
		var activeSession = sessions.get(requestedSessionId);
		if (activeSession == null) {
			var tombstone = tombstones.get(requestedSessionId);
			if (tombstone == null || !now.isBefore(tombstone.expiresAt())) {
				throw tombstoneOrNotFound(requestedSessionId, now);
			}
		}
		return new EventResponse(
			request.eventId(),
			requestedSessionId,
			existing.getCameraDeviceId(),
			existing.getPatientId(),
			false,
			now
		);
	}

	private boolean sameCanonicalEvent(
		com.example.fall_detection_backend.domain.DetectionEvent existing,
		CanonicalEvent canonical,
		LiveEventRequest request
	) {
		return Objects.equals(existing.getCameraDeviceId(), canonical.cameraId())
			&& Objects.equals(existing.getOccurredAt(), request.occurredAt())
			&& existing.getKind() == canonical.kind()
			&& existing.getRule() == canonical.rule()
			&& existing.getState() == canonical.state()
			&& Objects.equals(existing.getProbNormal(), canonical.probNormal())
			&& Objects.equals(existing.getProbFall(), canonical.probFall())
			&& Objects.equals(existing.getProbFallen(), canonical.probFallen())
			&& Objects.equals(existing.getDurationSec(), canonical.durationSec())
			&& Objects.equals(existing.getKeypoints(), canonical.keypointsJson());
	}

	CameraDevice requireAuthenticatedCamera(UUID cameraId, String bearerToken) {
		var digest = digestToken(requireToken(bearerToken));
		var camera = cameras.findByDeviceToken(digest)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 올바르지 않습니다."));
		if (!cameraId.equals(camera.getId())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카메라 인증 정보가 요청 카메라와 일치하지 않습니다.");
		}
		return camera;
	}

	private void requirePairedCamera(CameraDevice camera) {
		if (camera.getPatientId() == null || camera.getPairedAt() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "보호자 앱에 등록된 카메라만 라이브 세션을 사용할 수 있습니다.");
		}
	}

	private void requireCurrentCameraSession(LiveSession session, CameraDevice camera, Instant now) {
		if (!session.patientId.equals(camera.getPatientId()) || !Objects.equals(session.privacyRevision, privacyRevision(camera))) {
			invalidateCamera(session.cameraId, "카메라 연결 또는 개인정보 설정이 변경되어 라이브 세션이 종료되었습니다.");
			throw new ResponseStatusException(HttpStatus.GONE, "라이브 세션이 만료되었습니다.");
		}
		if (!now.isBefore(session.expiresAt)) {
			tombstone(session, now);
			throw new ResponseStatusException(HttpStatus.GONE, "라이브 세션이 만료되었습니다.");
		}
	}

	private LiveSession requireCameraSession(UUID cameraId, UUID sessionId, Instant now) {
		var session = sessions.get(sessionId);
		if (session == null) {
			throw tombstoneOrNotFound(sessionId, now);
		}
		if (!session.cameraId.equals(cameraId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "라이브 세션을 찾을 수 없습니다.");
		}
		if (!now.isBefore(session.expiresAt)) {
			tombstone(session, now);
			throw new ResponseStatusException(HttpStatus.GONE, "라이브 세션이 만료되었습니다.");
		}
		return session;
	}

	private LiveSession requireGuardianSession(UUID patientId, UUID sessionId, Instant now) {
		var session = sessions.get(sessionId);
		if (session == null) {
			throw tombstoneOrNotFound(sessionId, now);
		}
		if (!session.patientId.equals(patientId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "라이브 세션을 찾을 수 없습니다.");
		}
		if (!now.isBefore(session.expiresAt)) {
			tombstone(session, now);
			throw new ResponseStatusException(HttpStatus.GONE, "라이브 세션이 만료되었습니다.");
		}
		return session;
	}

	private ResponseStatusException tombstoneOrNotFound(UUID sessionId, Instant now) {
		var tombstone = tombstones.get(sessionId);
		if (tombstone != null && now.isBefore(tombstone.expiresAt())) {
			return new ResponseStatusException(HttpStatus.GONE, tombstone.reason());
		}
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "라이브 세션을 찾을 수 없습니다.");
	}

	void requireLinked(UUID patientId, UUID guardianUserId) {
		if (guardianUserId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 정보를 입력해주세요.");
		}
		if (!guardianAllowlist.isEmpty() && !guardianAllowlist.contains(guardianUserId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "라이브 개발 API 보호자 allowlist에 없는 보호자입니다.");
		}
		if (!caregiverLinks.existsByPatientIdAndUserId(patientId, guardianUserId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 환자의 보호자만 라이브 세션을 사용할 수 있습니다.");
		}
	}

	private Viewer requireViewer(LiveSession session, String viewerId, Instant now) {
		var viewer = session.viewers.get(viewerId);
		if (viewer == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "라이브 시청 권한이 필요합니다.");
		}
		if (!now.isBefore(viewer.expiresAt())) {
			removeViewer(session, viewerId, now);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "라이브 시청 권한이 필요합니다.");
		}
		if (!guardianAllowlist.isEmpty() && !guardianAllowlist.contains(viewer.guardianUserId())) {
			removeViewer(session, viewerId, now);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "라이브 개발 API 보호자 allowlist에 없는 보호자입니다.");
		}
		if (!caregiverLinks.existsByPatientIdAndUserId(session.patientId, viewer.guardianUserId())) {
			removeViewer(session, viewerId, now);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 환자의 보호자만 라이브 세션을 사용할 수 있습니다.");
		}
		viewer.expiresAt = now.plus(SESSION_TTL);
		return viewer;
	}

	private long enqueue(LiveSession session, SignalSender from, SignalKind kind, Map<String, Object> payload, Instant now) {
		pruneSignals(session, now);
		validateSignal(session, from, kind, payload, now);
		var sequence = ++session.nextSignalSequence;
		var item = new QueuedSignal(sequence, session.currentGeneration, from, kind, payload == null ? Map.of() : Map.copyOf(payload), now);
		session.signals.addLast(item);
		session.signalBytes += signalPayloadBytes(payload);
		trimSignals(session);
		return sequence;
	}

	private void enqueueSystem(LiveSession session, SignalKind kind, Map<String, Object> payload, Instant now) {
		pruneSignals(session, now);
		var sequence = ++session.nextSignalSequence;
		var item = new QueuedSignal(sequence, session.currentGeneration, SignalSender.SYSTEM, kind, Map.copyOf(payload), now);
		session.signals.addLast(item);
		session.signalBytes += signalPayloadBytes(payload);
		trimSignals(session);
	}

	private void trimSignals(LiveSession session) {
		while (session.signals.size() > QUEUE_LIMIT) {
			var removed = session.signals.removeFirst();
			session.signalBytes -= signalPayloadBytes(removed.payload());
		}
	}

	private void pruneSignals(LiveSession session, Instant now) {
		var boundary = now.minus(SIGNAL_TTL);
		while (!session.signals.isEmpty() && !session.signals.peekFirst().createdAt().isAfter(boundary)) {
			var removed = session.signals.removeFirst();
			session.signalBytes -= signalPayloadBytes(removed.payload());
		}
	}

	private void validateSignal(LiveSession session, SignalSender from, SignalKind kind, Map<String, Object> payload, Instant now) {
		if (from == SignalSender.CAMERA && kind == SignalKind.ANSWER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "카메라 앱은 OFFER 또는 ICE만 보낼 수 있습니다.");
		}
		if (from == SignalSender.GUARDIAN && kind == SignalKind.OFFER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 앱은 ANSWER 또는 ICE만 보낼 수 있습니다.");
		}
		if (kind == SignalKind.VIEWER_JOINED || kind == SignalKind.VIEWER_LEFT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VIEWER_JOINED/VIEWER_LEFT는 서버가 생성하는 시그널입니다.");
		}
		var negotiation = session.negotiations.computeIfAbsent(session.currentGeneration, ignored -> new NegotiationState());
		if (kind == SignalKind.OFFER) {
			if (negotiation.cameraOfferSeen) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "OFFER는 negotiation generation당 한 번만 보낼 수 있습니다.");
			}
		}
		if (kind == SignalKind.ANSWER) {
			if (negotiation.guardianAnswerSeen) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "ANSWER는 negotiation generation당 한 번만 보낼 수 있습니다.");
			}
		}
		var nextIceCount = 0;
		if (kind == SignalKind.ICE) {
			if (now.isAfter(session.currentGenerationStartedAt.plus(ICE_WINDOW))) {
				throw new ResponseStatusException(HttpStatus.GONE, "현재 negotiation generation의 ICE 수신 시간이 만료되었습니다.");
			}
			var currentIceCount = negotiation.iceCounts.getOrDefault(from, 0);
			if (currentIceCount >= ICE_LIMIT_PER_SIDE) {
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "ICE 후보는 송신자별 32개까지만 보낼 수 있습니다.");
			}
			nextIceCount = currentIceCount + 1;
		}
		var payloadBytes = signalPayloadBytes(payload);
		if (kind == SignalKind.ICE && payloadBytes > MAX_ICE_PAYLOAD_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "ICE 후보 payload가 너무 큽니다.");
		}
		if (payloadBytes > MAX_SIGNAL_PAYLOAD_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "시그널 payload가 너무 큽니다.");
		}
		if (session.signalBytes + payloadBytes > MAX_SIGNAL_TOTAL_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "시그널 누적 payload가 너무 큽니다.");
		}
		rateLimitSignals(session, from, now);
		if (kind == SignalKind.OFFER) {
			negotiation.cameraOfferSeen = true;
		}
		if (kind == SignalKind.ANSWER) {
			negotiation.guardianAnswerSeen = true;
		}
		if (kind == SignalKind.ICE) {
			negotiation.iceCounts.put(from, nextIceCount);
		}
	}

	private void rateLimitSignals(LiveSession session, SignalSender from, Instant now) {
		var timestamps = session.signalTimestamps.computeIfAbsent(from, ignored -> new ArrayDeque<>());
		var boundary = now.minus(SIGNAL_RATE_WINDOW);
		while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(boundary)) {
			timestamps.removeFirst();
		}
		if (timestamps.size() >= SIGNAL_RATE_LIMIT) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "시그널 전송 속도가 너무 빠릅니다.");
		}
		timestamps.addLast(now);
	}

	private int signalPayloadBytes(Map<String, Object> payload) {
		return payload == null ? 0 : payload.toString().getBytes(StandardCharsets.UTF_8).length;
	}

	private SignalListResponse signalList(LiveSession session, long after, int limit, Instant now) {
		pruneSignals(session, now);
		var cappedLimit = Math.max(1, Math.min(limit, QUEUE_LIMIT));
		var items = session.signals.stream()
			.filter(signal -> signal.sequence() > after)
			.limit(cappedLimit)
			.map(QueuedSignal::toSignalItem)
			.toList();
		var nextCursor = items.isEmpty() ? after : items.get(items.size() - 1).sequence();
		return new SignalListResponse(items, nextCursor, now);
	}

	private void extend(LiveSession session, Instant now, boolean lease, boolean control) {
		if (lease) {
			session.leaseExpiresAt = now.plus(LEASE_TTL);
		}
		if (control) {
			session.controlLeaseExpiresAt = now.plus(CONTROL_TTL);
		}
		session.expiresAt = now.plus(SESSION_TTL);
	}

	private SessionResponse view(LiveSession session, Instant now) {
		pruneExpiredViewers(session, now);
		return new SessionResponse(
			session.id,
			session.cameraId,
			session.patientId,
			session.privacyMode,
			session.privacyRevision,
			session.controlState,
			session.viewers.size(),
			session.leaseExpiresAt,
			session.controlLeaseExpiresAt,
			session.expiresAt,
			now
		);
	}

	private void tombstone(LiveSession session, Instant now) {
		tombstone(session, now, "라이브 세션이 종료되었습니다.");
	}

	private void tombstone(LiveSession session, Instant now, String reason) {
		sessions.remove(session.id);
		var expiresAt = now.plus(TOMBSTONE_TTL);
		tombstones.put(session.id, new Tombstone(session.id, session.cameraId, session.patientId, expiresAt, reason));
		eventProvenance.replaceAll((eventId, provenance) -> provenance.sessionId().equals(session.id)
			? new EventProvenance(eventId, provenance.sessionId(), provenance.cameraId(), provenance.patientId(), expiresAt)
			: provenance);
		trimTombstones();
	}

	private void cleanup(Instant now) {
		var expiredSessions = sessions.values().stream()
			.filter(session -> !now.isBefore(session.expiresAt))
			.map(session -> session.id)
			.toList();
		for (var sessionId : expiredSessions) {
			var session = sessions.get(sessionId);
			if (session != null) {
				tombstone(session, now);
			}
		}
		tombstones.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
		eventProvenance.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
		trimSessions(now);
		trimTombstones();
	}

	private void pruneExpiredViewers(LiveSession session, Instant now) {
		var expired = session.viewers.entrySet().stream()
			.filter(entry -> !now.isBefore(entry.getValue().expiresAt()))
			.map(Map.Entry::getKey)
			.toList();
		for (var viewerId : expired) {
			removeViewer(session, viewerId, now);
		}
	}

	private void removeViewer(LiveSession session, String viewerId, Instant now) {
		var removed = session.viewers.remove(viewerId);
		if (removed != null) {
			enqueueSystem(session, SignalKind.VIEWER_LEFT, Map.of("viewerId", viewerId, "generation", removed.generation), now);
		}
	}

	private void trimSessions(Instant now) {
		while (sessions.size() > SESSION_LIMIT) {
			var oldest = sessions.values().stream()
				.min((left, right) -> left.createdAt.compareTo(right.createdAt))
				.orElse(null);
			if (oldest == null) {
				return;
			}
			tombstone(oldest, now);
		}
	}

	private void trimTombstones() {
		while (tombstones.size() > TOMBSTONE_LIMIT) {
			var oldest = tombstones.values().stream()
				.min((left, right) -> left.expiresAt().compareTo(right.expiresAt()))
				.orElse(null);
			if (oldest == null) {
				return;
			}
			tombstones.remove(oldest.sessionId());
		}
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

	private long privacyRevision(CameraDevice camera) {
		return camera.getPrivacyRevision() == null ? 0L : camera.getPrivacyRevision();
	}

	private Set<UUID> parseGuardianAllowlist(String configured) {
		if (configured == null || configured.isBlank()) {
			return Set.of();
		}
		try {
			return List.of(configured.split(",")).stream()
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.map(UUID::fromString)
				.collect(Collectors.toUnmodifiableSet());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("careguard.live.guardian-allowlist must contain comma-separated UUIDs", e);
		}
	}

	private String randomCapability() {
		var bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private Instant now() {
		return Instant.now(clock);
	}

	private record Tombstone(UUID sessionId, UUID cameraId, UUID patientId, Instant expiresAt, String reason) {
	}

	private record EventProvenance(UUID eventId, UUID sessionId, UUID cameraId, UUID patientId, Instant expiresAt) {
	}

	private static final class Viewer {
		private final String viewerId;
		private final UUID guardianUserId;
		private final long generation;
		private Instant expiresAt;

		private Viewer(String viewerId, UUID guardianUserId, long generation, Instant expiresAt) {
			this.viewerId = viewerId;
			this.guardianUserId = guardianUserId;
			this.generation = generation;
			this.expiresAt = expiresAt;
		}

		private UUID guardianUserId() {
			return guardianUserId;
		}

		private Instant expiresAt() {
			return expiresAt;
		}
	}

	private static final class NegotiationState {
		private boolean cameraOfferSeen;
		private boolean guardianAnswerSeen;
		private final Map<SignalSender, Integer> iceCounts = new EnumMap<>(SignalSender.class);
	}

	private static final class LiveSession {
		private final UUID id;
		private final UUID cameraId;
		private final UUID patientId;
		private final DomainEnums.PrivacyMode privacyMode;
		private final long privacyRevision;
		private final Instant createdAt;
		private LiveControlState controlState;
		private Instant leaseExpiresAt;
		private Instant controlLeaseExpiresAt;
		private Instant expiresAt;
		private long nextSignalSequence;
		private long currentGeneration;
		private long signalBytes;
		private Instant currentGenerationStartedAt;
		private final ArrayDeque<QueuedSignal> signals = new ArrayDeque<>();
		private final Map<String, Viewer> viewers = new LinkedHashMap<>();
		private final Map<Long, NegotiationState> negotiations = new HashMap<>();
		private final Map<SignalSender, ArrayDeque<Instant>> signalTimestamps = new EnumMap<>(SignalSender.class);

		private LiveSession(
			UUID id,
			UUID cameraId,
			UUID patientId,
			DomainEnums.PrivacyMode privacyMode,
			long privacyRevision,
			LiveControlState controlState,
			Instant leaseExpiresAt,
			Instant controlLeaseExpiresAt,
			Instant expiresAt,
			Instant createdAt
		) {
			this.id = id;
			this.cameraId = cameraId;
			this.patientId = patientId;
			this.privacyMode = privacyMode;
			this.privacyRevision = privacyRevision;
			this.controlState = controlState;
			this.leaseExpiresAt = leaseExpiresAt;
			this.controlLeaseExpiresAt = controlLeaseExpiresAt;
			this.expiresAt = expiresAt;
			this.createdAt = createdAt;
			this.currentGenerationStartedAt = createdAt;
		}
	}

	public enum LiveModel {
		PHONE,
		PC
	}

	public enum LiveControlState {
		RUNNING,
		PAUSED,
		STOPPED
	}

	public enum SignalKind {
		OFFER,
		ANSWER,
		ICE,
		VIEWER_JOINED,
		VIEWER_LEFT
	}

	public enum SignalSender {
		CAMERA,
		GUARDIAN,
		SYSTEM
	}

	public record StartSessionRequest(LiveModel model) {
		public StartSessionRequest {
			if (model == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "추론 모델 정보를 입력해주세요.");
			}
		}
	}

	public record ControlRequest(LiveControlState state) {
		public ControlRequest {
			if (state == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "라이브 제어 상태를 입력해주세요.");
			}
		}
	}

	public record SignalRequest(SignalKind kind, Map<String, Object> payload) {
		public SignalRequest {
			if (kind == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시그널 종류를 입력해주세요.");
			}
				if (payload != null && payload.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SIGNAL_PAYLOAD_BYTES) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시그널 payload가 너무 큽니다.");
				}
		}
	}

	public record ViewerRequest(UUID guardianUserId) {
		public ViewerRequest {
			if (guardianUserId == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 정보를 입력해주세요.");
			}
		}
	}

	public record LiveEventRequest(
		UUID eventId,
		Instant occurredAt,
		String eventType,
		String kind,
		String rule,
		String state,
		Float score,
		Float probNormal,
		Float probFall,
		Float probFallen,
		Float durationSec,
		String severity,
		String keypointsJson
	) {
		public LiveEventRequest {
			if (eventId == null || occurredAt == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "감지 이벤트 id와 발생 시각을 입력해주세요.");
			}
			if (hasAnyProbability(probNormal, probFall, probFallen)) {
				if (!hasExplicitProbabilities(probNormal, probFall, probFallen)) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "probNormal/probFall/probFallen을 모두 입력해주세요.");
				}
				validateProbability(probNormal, "probNormal");
				validateProbability(probFall, "probFall");
				validateProbability(probFallen, "probFallen");
			} else if (score == null || score < 0.0f || score > 1.0f) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "감지 점수는 0 이상 1 이하이어야 합니다.");
			}
			if (durationSec != null && durationSec < 0.0f) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationSec는 0 이상이어야 합니다.");
			}
			if (keypointsJson != null && !keypointsJson.isBlank() && !"{}".equals(keypointsJson.trim())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "라이브 감지 이벤트는 keypoints를 저장하지 않습니다.");
			}
		}

		private static boolean hasAnyProbability(Float probNormal, Float probFall, Float probFallen) {
			return probNormal != null || probFall != null || probFallen != null;
		}

		private static boolean hasExplicitProbabilities(Float probNormal, Float probFall, Float probFallen) {
			return probNormal != null && probFall != null && probFallen != null;
		}

		private static void validateProbability(Float probability, String name) {
			if (probability == null || probability < 0.0f || probability > 1.0f) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + "는 0 이상 1 이하이어야 합니다.");
			}
		}

		private CanonicalEvent canonical(UUID cameraId) {
			var eventKind = enumValue(kind, DomainEnums.EventKind.class, fallbackKind(eventType));
			var eventRule = enumValue(rule, DomainEnums.EventRule.class, DomainEnums.EventRule.MODEL);
			var poseState = enumValue(state, DomainEnums.PoseState.class, fallbackState(eventType));
			var keypoints = "{}";
			var hasProbabilities = hasExplicitProbabilities(probNormal, probFall, probFallen);
			var normal = hasProbabilities ? probNormal : 1.0f - score;
			var fall = hasProbabilities ? probFall : score;
			var fallen = hasProbabilities ? probFallen : (poseState == DomainEnums.PoseState.FALLEN ? score : 0.0f);
			return new CanonicalEvent(
				cameraId,
				eventKind,
				eventRule,
				poseState,
				normal,
				fall,
				fallen,
				durationSec,
				keypoints
			);
		}

		private static DomainEnums.EventKind fallbackKind(String eventType) {
			return "FALLEN_IMMOBILE".equals(eventType) ? DomainEnums.EventKind.FALLEN_IMMOBILE : DomainEnums.EventKind.FALL;
		}

		private static DomainEnums.PoseState fallbackState(String eventType) {
			return "FALLEN_IMMOBILE".equals(eventType) ? DomainEnums.PoseState.FALLEN : DomainEnums.PoseState.FALL;
		}

		private static <T extends Enum<T>> T enumValue(String value, Class<T> enumType, T fallback) {
			if (value == null || value.isBlank()) {
				return fallback;
			}
			try {
				return Enum.valueOf(enumType, value);
			} catch (IllegalArgumentException e) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, enumType.getSimpleName() + " 값이 올바르지 않습니다.");
			}
		}
	}

	private record CanonicalEvent(
		UUID cameraId,
		DomainEnums.EventKind kind,
		DomainEnums.EventRule rule,
		DomainEnums.PoseState state,
		Float probNormal,
		Float probFall,
		Float probFallen,
		Float durationSec,
		String keypointsJson
	) {
	}

	public record SessionResponse(
		UUID sessionId,
		UUID cameraId,
		UUID patientId,
		DomainEnums.PrivacyMode privacyMode,
		long privacyRevision,
		LiveControlState controlState,
		int viewerCount,
		Instant leaseExpiresAt,
		Instant controlLeaseExpiresAt,
		Instant expiresAt,
		Instant serverTime
	) {
	}

	public record SignalCreatedResponse(long sequence, long generation, Instant serverTime) {
	}

	private record QueuedSignal(
		long sequence,
		long generation,
		SignalSender from,
		SignalKind kind,
		Map<String, Object> payload,
		Instant createdAt
	) {
		private SignalItem toSignalItem() {
			return new SignalItem(sequence, generation, from, kind, payload);
		}
	}

	public record SignalItem(long sequence, long generation, SignalSender from, SignalKind kind, Map<String, Object> payload) {
	}

	public record SignalListResponse(List<SignalItem> items, long nextCursor, Instant serverTime) {
		public SignalListResponse {
			items = List.copyOf(items == null ? new ArrayList<>() : items);
		}
	}

	public record ViewerResponse(String viewerId, long generation, Instant expiresAt, Instant serverTime) {
	}

	public record EventResponse(UUID eventId, UUID sessionId, UUID cameraId, UUID patientId, boolean created, Instant serverTime) {
	}
}
