package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.DeviceStatus;
import com.example.fall_detection_backend.domain.DomainEnums.PrivacyMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "camera_devices")
public class CameraDevice {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(nullable = false, length = 60)
	private String name = "거실 카메라";

	@Column(length = 80)
	private String model;

	@Column(name = "device_token", nullable = false, unique = true)
	private String deviceToken;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "privacy_mode", nullable = false, columnDefinition = "privacy_mode")
	private PrivacyMode privacyMode = PrivacyMode.VIDEO_SKELETON;

	@Column(name = "privacy_revision", nullable = false)
	private Long privacyRevision = 0L;

	@Column(nullable = false)
	private Short sensitivity = 2;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "device_status")
	private DeviceStatus status = DeviceStatus.UNPAIRED;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	@Column(name = "last_fps")
	private Float lastFps;

	@Column(name = "battery_pct")
	private Short batteryPct;

	@Column(name = "is_charging")
	private Boolean isCharging;

	@Column(name = "thermal_ok")
	private Boolean thermalOk;

	@Column(name = "paired_at")
	private Instant pairedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();
}
