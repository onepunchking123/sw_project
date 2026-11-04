package com.example.fall_detection_backend.api.camera;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
