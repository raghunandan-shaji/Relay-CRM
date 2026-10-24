package com.raghu.crm.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class StaffAccounts {

	private final Set<String> emails;

	public StaffAccounts(@Value("${crm.auth.staff-emails:}") String configuredEmails) {
		emails = Arrays.stream(configuredEmails.split(","))
			.map(email -> email.strip().toLowerCase(Locale.ROOT))
			.filter(email -> !email.isEmpty()).collect(Collectors.toUnmodifiableSet());
	}

	public boolean contains(String email) {
		return emails.contains(email.strip().toLowerCase(Locale.ROOT));
	}

	public Set<String> emails() {
		return emails;
	}
}
