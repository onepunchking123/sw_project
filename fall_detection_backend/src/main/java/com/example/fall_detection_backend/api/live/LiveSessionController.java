package com.example.fall_detection_backend.api.live;

import com.example.fall_detection_backend.api.live.LiveSessionService.ControlRequest;
import com.example.fall_detection_backend.api.live.LiveSessionService.EventResponse;
import com.example.fall_detection_backend.api.live.LiveSessionService.LiveEventRequest;
import com.example.fall_detection_backend.api.live.LiveSessionService.SessionResponse;
import com.example.fall_detection_backend.api.live.LiveSessionService.SignalCreatedResponse;
import com.example.fall_detection_backend.api.live.LiveSessionService.SignalListResponse;
import com.example.fall_detection_backend.api.live.LiveSessionService.SignalRequest;
import com.example.fall_detection_backend.api.live.LiveSessionService.StartSessionRequest;
import com.example.fall_detection_backend.api.live.LiveSessionService.ViewerRequest;
import com.example.fall_detection_backend.api.live.LiveSessionService.ViewerResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dev/live")
class LiveSessionController {
	private final LiveSessionService live;

	LiveSessionController(LiveSessionService live) {
		this.live = live;
	}

	@PostMapping("/cameras/{cameraId}/sessions")
	ResponseEntity<SessionResponse> start(
		@PathVariable UUID cameraId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestBody(required = false) StartSessionRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "라이브 세션 시작 정보를 입력해주세요.");
		}
		var response = live.start(cameraId, authorization, request);
		return ResponseEntity.created(URI.create("/api/dev/live/cameras/" + cameraId + "/sessions/" + response.sessionId()))
			.body(response);
	}

	@PutMapping("/cameras/{cameraId}/sessions/{sessionId}/lease")
	SessionResponse renewLease(
		@PathVariable UUID cameraId,
		@PathVariable UUID sessionId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
	) {
		return live.renewLease(cameraId, sessionId, authorization);
	}

	@PostMapping("/cameras/{cameraId}/sessions/{sessionId}/control")
	SessionResponse control(
		@PathVariable UUID cameraId,
		@PathVariable UUID sessionId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestBody(required = false) ControlRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "라이브 제어 상태를 입력해주세요.");
		}
		return live.control(cameraId, sessionId, authorization, request);
	}

	@PostMapping("/cameras/{cameraId}/sessions/{sessionId}/events")
	ResponseEntity<EventResponse> event(
		@PathVariable UUID cameraId,
		@PathVariable UUID sessionId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestBody(required = false) LiveEventRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "감지 이벤트 정보를 입력해주세요.");
		}
		var response = live.event(cameraId, sessionId, authorization, request);
		return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
	}

	@PostMapping("/cameras/{cameraId}/sessions/{sessionId}/signals")
	ResponseEntity<SignalCreatedResponse> cameraSignal(
		@PathVariable UUID cameraId,
		@PathVariable UUID sessionId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestBody(required = false) SignalRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시그널 정보를 입력해주세요.");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(live.cameraSignal(cameraId, sessionId, authorization, request));
	}

	@GetMapping("/cameras/{cameraId}/sessions/{sessionId}/signals")
	SignalListResponse cameraSignals(
		@PathVariable UUID cameraId,
		@PathVariable UUID sessionId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestParam(defaultValue = "0") long after,
		@RequestParam(defaultValue = "50") int limit
	) {
		return live.cameraSignals(cameraId, sessionId, authorization, after, limit);
	}

	@GetMapping("/patients/{patientId}/sessions/{sessionId}/signals")
	SignalListResponse signals(
		@PathVariable UUID patientId,
		@PathVariable UUID sessionId,
		@RequestParam String viewerId,
		@RequestParam(defaultValue = "0") long after,
		@RequestParam(defaultValue = "50") int limit
	) {
		return live.signals(patientId, sessionId, viewerId, after, limit);
	}

	@PostMapping("/patients/{patientId}/sessions/{sessionId}/signals")
	ResponseEntity<SignalCreatedResponse> guardianSignal(
		@PathVariable UUID patientId,
		@PathVariable UUID sessionId,
		@RequestParam String viewerId,
		@RequestBody(required = false) SignalRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시그널 정보를 입력해주세요.");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(live.guardianSignal(patientId, sessionId, viewerId, request));
	}

	@PostMapping("/patients/{patientId}/sessions/{sessionId}/viewers")
	ResponseEntity<ViewerResponse> join(
		@PathVariable UUID patientId,
		@PathVariable UUID sessionId,
		@RequestBody(required = false) ViewerRequest request
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보호자 정보를 입력해주세요.");
		}
		var response = live.join(patientId, sessionId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@DeleteMapping("/patients/{patientId}/sessions/{sessionId}/viewers/{viewerId}")
	ResponseEntity<Void> leave(
		@PathVariable UUID patientId,
		@PathVariable UUID sessionId,
		@PathVariable String viewerId
	) {
		live.leave(patientId, sessionId, viewerId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/patients/{patientId}/cameras/{cameraId}/session")
	SessionResponse discover(
		@PathVariable UUID patientId,
		@PathVariable UUID cameraId,
		@RequestParam UUID guardianUserId
	) {
		return live.discover(patientId, cameraId, guardianUserId);
	}

	@GetMapping("/patients/{patientId}/sessions/{sessionId}/status")
	SessionResponse status(
		@PathVariable UUID patientId,
		@PathVariable UUID sessionId,
		@RequestParam String viewerId
	) {
		return live.status(patientId, sessionId, viewerId);
	}
}
