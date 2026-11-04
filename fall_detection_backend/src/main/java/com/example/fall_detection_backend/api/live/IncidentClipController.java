package com.example.fall_detection_backend.api.live;

import com.example.fall_detection_backend.api.live.IncidentClipService.ClipResponse;
import com.example.fall_detection_backend.api.live.IncidentClipService.PatientOkResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/live")
class IncidentClipController {
	private final IncidentClipService clips;

	IncidentClipController(IncidentClipService clips) {
		this.clips = clips;
	}

	/** 카메라 폰 카운트다운에서 환자가 '괜찮습니다'를 눌렀다. eventId는 카메라가 만든 clientEventId다. */
	@PostMapping("/cameras/{cameraId}/events/{eventId}/patient-ok")
	PatientOkResponse patientOk(
		@PathVariable UUID cameraId,
		@PathVariable UUID eventId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
	) {
		return clips.patientOk(cameraId, authorization, eventId);
	}

	/** 카운트다운이 끝난 뒤 카메라가 올리는 낙상 전후 MP4. 본문은 video/mp4 그대로 받는다. */
	@PutMapping(value = "/cameras/{cameraId}/events/{eventId}/clip", consumes = "video/mp4")
	ResponseEntity<ClipResponse> uploadClip(
		@PathVariable UUID cameraId,
		@PathVariable UUID eventId,
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		@RequestParam(required = false) Instant startedAt,
		@RequestParam(required = false) Float durationSec,
		HttpServletRequest request
	) throws IOException {
		clips.requireCamera(cameraId, authorization);
		var bytes = clips.readClipBody(request.getInputStream());
		var response = clips.uploadClip(cameraId, authorization, eventId, bytes, startedAt, durationSec);
		return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
	}

	/** 보호자 긴급 화면의 현장 영상. eventId는 서버 감지 이벤트 id(incident.detectionEventId)다. */
	@GetMapping("/patients/{patientId}/events/{eventId}/clip")
	ResponseEntity<Resource> clip(
		@PathVariable UUID patientId,
		@PathVariable UUID eventId,
		@RequestParam UUID guardianUserId
	) {
		var file = clips.clip(patientId, eventId, guardianUserId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType("video/mp4"))
			.cacheControl(CacheControl.noStore())
			.body(new FileSystemResource(file.path()));
	}
}
