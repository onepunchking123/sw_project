package com.example.fall_detection_backend.api.camera;

import com.example.fall_detection_backend.api.camera.CameraPairingService.CameraPairRequest;
import com.example.fall_detection_backend.api.camera.CameraPairingService.CameraRegisterRequest;
import com.example.fall_detection_backend.api.camera.CameraPairingService.CameraSelfResponse;
import com.example.fall_detection_backend.api.camera.CameraPairingService.GuardianCameraResponse;
import com.example.fall_detection_backend.api.camera.CameraPairingService.PairingCodeResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class CameraPairingController {
	private final CameraPairingService pairing;
	private final CameraRateLimiter rateLimiter;

	CameraPairingController(CameraPairingService pairing, CameraRateLimiter rateLimiter) {
		this.pairing = pairing;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/api/camera-devices/register")
	ResponseEntity<CameraSelfResponse> register(
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestBody(required = false) CameraRegisterRequest request,
		HttpServletRequest servletRequest
	) {
		rateLimiter.check("register:" + clientIp(servletRequest), 10, Duration.ofHours(1));
		var result = pairing.register(authorization, request == null ? new CameraRegisterRequest(null) : request);
		if (result.created()) {
			return ResponseEntity.created(URI.create("/api/camera-devices/" + result.response().cameraId()))
				.body(result.response());
		}
		return ResponseEntity.ok(result.response());
	}

	@GetMapping("/api/camera-devices/me")
	CameraSelfResponse me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return pairing.self(authorization);
	}

	@PostMapping("/api/camera-devices/me/pairing-codes")
	ResponseEntity<PairingCodeResponse> issuePairingCode(
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
	) {
		rateLimiter.check("pairing-code:" + authorizationHashKey(authorization), 3, Duration.ofMinutes(1));
		var response = pairing.issuePairingCode(authorization);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/api/camera-devices/me/heartbeat")
	CameraSelfResponse heartbeat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return pairing.heartbeat(authorization);
	}

	@PostMapping("/api/patients/{patientId}/cameras/pair")
	GuardianCameraResponse pair(
		@PathVariable UUID patientId,
		@RequestBody CameraPairRequest request,
		HttpServletRequest servletRequest
	) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페어링 정보를 입력해주세요.");
		}
		rateLimiter.check("pair-ip:" + clientIp(servletRequest), 10, Duration.ofMinutes(1));
		rateLimiter.check("pair-user:" + request.guardianUserId(), 5, Duration.ofMinutes(1));
		return pairing.claim(patientId, request);
	}

	private String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	private String authorizationHashKey(String authorization) {
		return Integer.toHexString(authorization == null ? 0 : authorization.hashCode());
	}
}
