package com.raghu.crm.sales;

import com.raghu.crm.auth.StaffAccounts;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesRepresentativeTests {

	@Test
	void onlyRegisteredStaffCanBeAssignedAndPoolOrderIsStable() {
		CustomerService customers = mock(CustomerService.class);
		when(customers.findByEmail("one@example.com")).thenReturn(profile("10001", "one@example.com"));
		SalesRepresentativeService reps = new SalesRepresentativeService(new StaffAccounts("missing@example.com,one@example.com"), customers);
		assertEquals(1, reps.available().size());
		assertEquals("10001", reps.assign("lead-123", null));
		assertEquals("10001", reps.assign("lead-123", "10001"));
		assertEquals(HttpStatus.BAD_REQUEST, assertThrows(SalesException.class, () -> reps.assign("lead-123", "10002")).status());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(SalesException.class,
			() -> new SalesRepresentativeService(new StaffAccounts(""), customers).assign("lead-123", null)).status());
	}

	@Test
	void assignmentDistributesAcrossThreeRepsAndIsDeterministicAcrossInstances() {
		CustomerService customers = mock(CustomerService.class);
		String emails = "one@example.com,two@example.com,three@example.com";
		when(customers.findByEmail("one@example.com")).thenReturn(profile("10001", "one@example.com"));
		when(customers.findByEmail("two@example.com")).thenReturn(profile("10002", "two@example.com"));
		when(customers.findByEmail("three@example.com")).thenReturn(profile("10003", "three@example.com"));
		SalesRepresentativeService first = new SalesRepresentativeService(new StaffAccounts(emails), customers);
		SalesRepresentativeService second = new SalesRepresentativeService(new StaffAccounts(emails), customers);
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < 10_000; i++) {
			String assigned = first.assign("lead-" + i, null);
			assertEquals(assigned, second.assign("lead-" + i, null));
			counts.merge(assigned, 1, Integer::sum);
		}
		assertEquals(3, counts.size());
		counts.values().forEach(count -> assertTrue(count >= 2500 && count <= 4200, counts.toString()));
		System.out.println("Consistent-hash representative assignment over 10,000 lead keys: " + new java.util.TreeMap<>(counts));
	}

	private CustomerResponse profile(String id, String email) {
		return new CustomerResponse(id, "shard-0", "Test Rep", email, null, null, Instant.now(), Instant.now(), 0);
	}
}
