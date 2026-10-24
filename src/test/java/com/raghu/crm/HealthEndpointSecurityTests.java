package com.raghu.crm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.raghu.crm.systemdesign.ratelimit.RedisRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"management.health.mongodb.enabled=false",
	"management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
class HealthEndpointSecurityTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RedisRateLimiter limiter;

	@BeforeEach
	void allowRequestsForHealthAndValidationTests() {
		when(limiter.check(any(), anyString(), anyInt(), any()))
			.thenReturn(new RedisRateLimiter.Decision(true, 100, 60));
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void unknownEndpointDoesNotAllowAnonymousAccess() throws Exception {
		mockMvc.perform(get("/not-public"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void customerRegistrationEndpointIsPublicButValidated() throws Exception {
		mockMvc.perform(post("/api/customers")
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}
}
