package com.raghu.crm.auth;

import com.raghu.crm.config.JwtConfig;
import com.raghu.crm.customer.CustomerService;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AuthController {

	private static final long TOKEN_LIFETIME_SECONDS = 900;
	private final CustomerService customers;
	private final JwtEncoder encoder;
	private final StaffAccounts staff;

	public AuthController(CustomerService customers, JwtEncoder encoder, StaffAccounts staff) {
		this.customers = customers;
		this.encoder = encoder;
		this.staff = staff;
	}

	@PostMapping("/api/auth/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		String customerId = customers.authenticate(request.email(), request.password());
		Instant now = Instant.now();
		// shortcut: tokens cannot be revoked before expiry; add revocation when logout/password changes are implemented.
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.audience(List.of(JwtConfig.AUDIENCE))
			.subject(customerId)
			.claim("scope", staff.contains(request.email()) ? "crm:staff" : "")
			.issuedAt(now)
			.notBefore(now)
			.expiresAt(now.plusSeconds(TOKEN_LIFETIME_SECONDS))
			.id(UUID.randomUUID().toString())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new TokenResponse(token, "Bearer", TOKEN_LIFETIME_SECONDS, customerId);
	}
}
