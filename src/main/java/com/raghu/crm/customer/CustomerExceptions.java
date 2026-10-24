package com.raghu.crm.customer;

final class DuplicateEmailException extends RuntimeException {
	DuplicateEmailException(String email) {
		super("A customer with email " + email + " already exists");
	}
}

final class CustomerNotFoundException extends RuntimeException {
	CustomerNotFoundException(String id) {
		super("Customer " + id + " was not found");
	}
}

final class ProfileVersionConflictException extends RuntimeException {
	ProfileVersionConflictException(String id) {
		super("Customer " + id + " was changed by another request; reload and retry");
	}
}
