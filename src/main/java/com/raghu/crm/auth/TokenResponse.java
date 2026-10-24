package com.raghu.crm.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String customerId) {
}
