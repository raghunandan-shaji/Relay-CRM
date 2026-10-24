package com.raghu.crm.engagement;

import com.raghu.crm.systemdesign.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EngagementSecurityTests {
	@Autowired private MockMvc mvc;
	@MockitoBean private EngagementService engagement;
	@MockitoBean private RedisRateLimiter limiter;

	@BeforeEach
	void allowQuota() {
		when(limiter.check(any(), anyString(), anyInt(), any())).thenReturn(new RedisRateLimiter.Decision(true, 119, 60));
	}

	@Test
	void ownersCanReadButCannotWriteAndOtherCustomersCannotRead() throws Exception {
		var owner = jwt().jwt(token -> token.subject("123"));
		for (String path : List.of("interactions", "interactions/456", "activities", "activities/456")) {
			mvc.perform(get("/api/customers/123/" + path)).andExpect(status().isUnauthorized());
			mvc.perform(get("/api/customers/123/" + path).with(jwt().jwt(token -> token.subject("999"))))
				.andExpect(status().isForbidden());
		}
		verifyNoInteractions(engagement);
		mvc.perform(get("/api/customers/123/interactions").with(owner)).andExpect(status().isOk());
		mvc.perform(get("/api/customers/123/interactions/456").with(owner)).andExpect(status().isOk());
		mvc.perform(get("/api/customers/123/activities").with(owner)).andExpect(status().isOk());
		mvc.perform(get("/api/customers/123/activities/456").with(owner)).andExpect(status().isOk());
		clearInvocations(engagement);
		mvc.perform(post("/api/customers/123/interactions").with(owner).contentType("application/json")
			.content("{\"type\":\"NOTE\",\"summary\":\"Blocked\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/customers/123/activities").with(owner).contentType("application/json")
			.content("{\"type\":\"TASK\",\"title\":\"Blocked\",\"dueAt\":\"2099-01-01T00:00:00Z\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(put("/api/customers/123/activities/456/status").with(owner).contentType("application/json")
			.content("{\"status\":\"COMPLETED\",\"version\":0}")).andExpect(status().isForbidden());
		verifyNoInteractions(engagement);
	}

	@Test
	void staffWritesUseTheAuthenticatedActor() throws Exception {
		var staff = jwt().jwt(token -> token.subject("789")).authorities(new SimpleGrantedAuthority("SCOPE_crm:staff"));
		Instant now = Instant.now();
		when(engagement.record(eq("123"), any(), eq("789"))).thenReturn(
			new Interaction("456", "123", null, InteractionType.CALL, "Call", null, now, now, "789"));
		mvc.perform(post("/api/customers/123/interactions").with(staff).contentType("application/json")
			.content("{\"type\":\"CALL\",\"summary\":\"Call\",\"recordedBy\":\"999\"}"))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.recordedBy").value("789"));
		verify(engagement).record(eq("123"), any(), eq("789"));
		mvc.perform(get("/api/customers/123/interactions").with(staff)).andExpect(status().isOk());
	}

	@Test
	void invalidBodiesDatesVersionsAndPageSizesAreRejected() throws Exception {
		var staff = jwt().authorities(new SimpleGrantedAuthority("SCOPE_crm:staff"));
		for (String body : List.of("{", "{\"type\":\"UNKNOWN\",\"summary\":\"Bad\"}",
			"{\"type\":\"CALL\",\"summary\":\" \"}",
			"{\"type\":\"CALL\",\"summary\":\"Bad date\",\"occurredAt\":\"2099-01-01T00:00:00Z\"}",
			"{\"type\":\"CALL\",\"summary\":\"Bad lead\",\"leadId\":\"abc\"}")) {
			mvc.perform(post("/api/customers/123/interactions").with(staff).contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
		}
		for (String body : List.of("{\"type\":\"TASK\",\"title\":\"Missing due date\"}",
			"{\"type\":\"TASK\",\"title\":\"Past\",\"dueAt\":\"2020-01-01T00:00:00Z\"}")) {
			mvc.perform(post("/api/customers/123/activities").with(staff).contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(put("/api/customers/123/activities/456/status").with(staff).contentType("application/json")
			.content("{\"status\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
		for (String path : List.of("interactions?limit=51", "interactions?beforeAt=bad", "activities?status=UNKNOWN", "activities?limit=0")) {
			mvc.perform(get("/api/customers/123/" + path).with(staff)).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(engagement);
	}
}
