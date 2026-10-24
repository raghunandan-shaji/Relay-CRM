package com.raghu.crm.sales;

import org.springframework.http.HttpStatus;

public class SalesException extends RuntimeException {

	private final HttpStatus status;

	public SalesException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
