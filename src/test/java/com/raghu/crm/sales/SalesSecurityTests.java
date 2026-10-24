package com.raghu.crm.sales;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SalesSecurityTests {

	@Autowired private MockMvc mvc;
	@MockitoBean private LeadService leads;
	@MockitoBean private SalesRepresentativeService representatives;
	@MockitoBean private RedisRateLimiter limiter;

	@BeforeEach
	void allowQuota() {
		when(limiter.check(any(), anyString(), anyInt(), any())).thenReturn(new RedisRateLimiter.Decision(true, 119, 60));
	}

	@Test
	void salesEndpointsRejectAnonymousAndOrdinaryCustomer() throws Exception {
		mvc.perform(get("/api/customers/123/leads")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/sales-representatives").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(post("/api/customers/123/leads").with(jwt()).contentType("application/json")
			.content("{\"title\":\"New deal\"}")).andExpect(status().isForbidden());
		mvc.perform(put("/api/customers/123/leads/456/status").with(jwt()).contentType("application/json")
			.content("{\"status\":\"LOST\",\"version\":0}")).andExpect(status().isForbidden());
		verifyNoInteractions(leads, representatives);
	}

	@Test
	void staffCanCreateLeadAndReadRepresentativePool() throws Exception {
		Instant now = Instant.now();
		Lead lead = new Lead("456", "123", "New deal", null, null, LeadStatus.NEW, "789", null, now, now, 0);
		when(leads.create(eq("123"), any())).thenReturn(lead);
		when(representatives.available()).thenReturn(List.of(new SalesRepresentativeService.Representative("789", "Staff", "staff@example.com")));
		mvc.perform(post("/api/customers/123/leads").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_crm:staff")))
			.contentType("application/json").content("{\"title\":\"New deal\"}"))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("456"));
		mvc.perform(get("/api/sales-representatives").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_crm:staff"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("789"));
	}

	@Test
	void rejectsMissingVersionInvalidMoneyAndOversizedLists() throws Exception {
		var staff = jwt().authorities(new SimpleGrantedAuthority("SCOPE_crm:staff"));
		mvc.perform(put("/api/customers/123/leads/456/status").with(staff).contentType("application/json")
			.content("{\"status\":\"CONTACTED\"}")).andExpect(status().isBadRequest());
		for (String amount : List.of("-1", "1.001", "1000000000000")) {
			mvc.perform(post("/api/customers/123/leads/456/opportunity").with(staff).contentType("application/json")
				.content("{\"amount\":" + amount + ",\"currency\":\"INR\",\"version\":0}"))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/customers/123/leads").param("limit", "500").with(staff)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/customers/123/leads").param("limit", "abc").with(staff)).andExpect(status().isBadRequest());
		mvc.perform(post("/api/customers/123/leads").with(staff).contentType("application/json").content("{"))
			.andExpect(status().isBadRequest());
		mvc.perform(put("/api/customers/123/leads/456/status").with(staff).contentType("application/json")
			.content("{\"status\":\"INVALID\",\"version\":0}")).andExpect(status().isBadRequest());
		verifyNoInteractions(leads);
	}
}
