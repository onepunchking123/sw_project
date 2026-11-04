package com.example.fall_detection_backend.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PostureSampleRepository extends JpaRepository<PostureSample, PostureSampleId> {
}
