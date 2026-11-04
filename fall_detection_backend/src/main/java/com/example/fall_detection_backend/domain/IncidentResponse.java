package com.example.fall_detection_backend.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.ResponseStatus;

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
@Table(name = "incident_responses", uniqueConstraints = @UniqueConstraint(columnNames = { "incident_id", "user_id" }))
public class IncidentResponse {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "incident_id", nullable = false)
	private UUID incidentId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "response_status")
	private ResponseStatus status = ResponseStatus.NOTIFIED;

	@Column(name = "notified_at", nullable = false)
	private Instant notifiedAt = Instant.now();

	@Column(name = "acknowledged_at")
	private Instant acknowledgedAt;

	@Column(name = "responding_at")
	private Instant respondingAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(columnDefinition = "text")
	private String note;
}
