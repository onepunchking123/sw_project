package com.example.fall_detection_backend.api.camera;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fall_detection_backend.domain.CameraDevice;
import com.example.fall_detection_backend.domain.DomainEnums;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CameraPairingServiceUnitTests {
	private static final Instant BASE = Instant.parse("2026-10-03T00:00:00Z");

	private final CameraPairingService service = new CameraPairingService(
		Clock.fixed(BASE, ZoneOffset.UTC),
		null,
		null,
		null,
		null,
		null
	);

	@Test
	void statusIsOnlineAtExactlySixtySecondsAndOfflineAfterBoundary() {
		var camera = pairedCamera();

		camera.setLastSeenAt(BASE.minusSeconds(60));
		assertThat(service.status(camera, BASE)).isEqualTo(DomainEnums.DeviceStatus.ONLINE);

		camera.setLastSeenAt(BASE.minusSeconds(60).minusNanos(1));
		assertThat(service.status(camera, BASE)).isEqualTo(DomainEnums.DeviceStatus.OFFLINE);
	}

	@Test
	void pairingCodeExpiresAtExactExpiryInstant() {
		var expiresAt = BASE.plusSeconds(300);

		assertThat(service.isExpired(expiresAt, expiresAt.minusNanos(1))).isFalse();
		assertThat(service.isExpired(expiresAt, expiresAt)).isTrue();
	}

	@Test
	void pairRequestAcceptsLeadingZeroCode() {
		var request = new CameraPairingService.CameraPairRequest("00001234", "거실 카메라", UUID.randomUUID());

		assertThat(request.code()).isEqualTo("00001234");
	}

	private CameraDevice pairedCamera() {
		var camera = new CameraDevice();
		camera.setPatientId(UUID.randomUUID());
		camera.setPairedAt(BASE.minusSeconds(120));
		camera.setName("거실 카메라");
		camera.setDeviceToken("sha256:test");
		return camera;
	}
}
