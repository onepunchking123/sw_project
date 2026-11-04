package com.example.fall_detection_backend.api.live;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class LiveApiAccessConfig implements WebMvcConfigurer {
	private final boolean enabled;
	private final boolean allowRemote;

	LiveApiAccessConfig(
		@Value("${careguard.live.enabled:${CAREGUARD_LIVE_ENABLED:false}}") boolean enabled,
		// 기본은 LAN/사설 IP 만 허용한다. 공개 서버(EC2 등)에 올릴 때만 명시적으로 true 로 켠다.
		@Value("${careguard.live.allow-remote:${CAREGUARD_LIVE_ALLOW_REMOTE:false}}") boolean allowRemote
	) {
		this.enabled = enabled;
		this.allowRemote = allowRemote;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new LiveApiAccessInterceptor(enabled, allowRemote)).addPathPatterns("/api/dev/live/**");
	}

	private static final class LiveApiAccessInterceptor implements HandlerInterceptor {
		private final boolean enabled;
		private final boolean allowRemote;

		private LiveApiAccessInterceptor(boolean enabled, boolean allowRemote) {
			this.enabled = enabled;
			this.allowRemote = allowRemote;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			if (!enabled) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "라이브 개발 API가 비활성화되어 있습니다.");
			}
			if (!allowRemote && !isLocalDevelopmentAddress(request.getRemoteAddr())) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "라이브 개발 API는 로컬/LAN 개발 환경에서만 사용할 수 있습니다. 공개 서버에서는 CAREGUARD_LIVE_ALLOW_REMOTE=true 가 필요합니다.");
			}
			return true;
		}

		private boolean isLocalDevelopmentAddress(String remoteAddr) {
			try {
				var address = InetAddress.getByName(remoteAddr);
				return address.isAnyLocalAddress()
					|| address.isLoopbackAddress()
					|| address.isSiteLocalAddress()
					|| address.isLinkLocalAddress();
			} catch (UnknownHostException e) {
				return false;
			}
		}
	}
}
