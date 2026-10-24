package com.raghu.crm.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAccountsTests {

	@Test
	void staffRequiresExactServerConfiguredEmail() {
		StaffAccounts staff = new StaffAccounts(" Staff@Example.com ,other@example.com");
		assertTrue(staff.contains(" STAFF@example.com "));
		assertFalse(staff.contains("staff@example.com.attacker.test"));
		assertFalse(staff.contains("customer@example.com"));
		assertFalse(new StaffAccounts("").contains("staff@example.com"));
	}
}
