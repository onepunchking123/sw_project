package com.example.fall_detection_backend.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.fall_detection_backend.domain.DomainEnums.Posture;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "posture_samples")
public class PostureSample {

	@EmbeddedId
	private PostureSampleId id;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "posture")
	private Posture dominant;

	@Column(name = "lying_ratio", nullable = false)
	private Float lyingRatio;

	@Column(nullable = false)
	private Float motion;

	@Column(name = "detect_rate", nullable = false)
	private Float detectRate;

	private Float fps;
}
