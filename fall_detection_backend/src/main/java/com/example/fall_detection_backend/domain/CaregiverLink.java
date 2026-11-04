package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.CaregiverRelation;
import com.example.fall_detection_backend.domain.DomainEnums.CaregiverRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "caregiver_links", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "patient_id" }))
public class CaregiverLink {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "patient_id", nullable = false)
	private UUID patientId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "caregiver_relation")
	private CaregiverRelation relation;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "caregiver_role")
	private CaregiverRole role = CaregiverRole.MEMBER;

	@Column(name = "notify_fall", nullable = false)
	private Boolean notifyFall = true;

	@Column(name = "notify_still_min")
	private Short notifyStillMin;

	@Column(name = "notify_offline", nullable = false)
	private Boolean notifyOffline = true;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt = Instant.now();
}
