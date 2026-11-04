package com.example.fall_detection_backend.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Embeddable
public class PostureSampleId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "camera_device_id", nullable = false)
	private UUID cameraDeviceId;

	@Column(name = "minute_at", nullable = false)
	private Instant minuteAt;
}
