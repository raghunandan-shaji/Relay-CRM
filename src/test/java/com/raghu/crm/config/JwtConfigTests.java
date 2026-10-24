package com.raghu.crm.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtConfigTests {

	@Test
	void independentServerCanValidateTokenUsingSameSharedSecret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		String sharedSecret = Base64.getEncoder().encodeToString(bytes);
		JwtConfig serverOne = new JwtConfig();
		JwtConfig serverTwo = new JwtConfig();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(JwtConfig.ISSUER)
			.audience(List.of(JwtConfig.AUDIENCE)).subject("123")
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
		String token = serverOne.jwtEncoder(serverOne.jwtSigningKey(sharedSecret))
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
			.getTokenValue();
		assertEquals("123", serverTwo.jwtDecoder(serverTwo.jwtSigningKey(sharedSecret)).decode(token).getSubject());
	}

	@Test
	void signingKeyMustBeBase64WithAtLeast256Bits() {
		JwtConfig config = new JwtConfig();
		assertThrows(IllegalArgumentException.class, () -> config.jwtSigningKey(""));
		assertThrows(IllegalArgumentException.class, () -> config.jwtSigningKey("not-base64!"));
		assertThrows(IllegalArgumentException.class, () -> config.jwtSigningKey("c2hvcnQ="));
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		assertEquals(32, config.jwtSigningKey(Base64.getEncoder().encodeToString(bytes)).getEncoded().length);
	}
}
