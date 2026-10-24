package com.raghu.crm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

@Configuration
public class JwtConfig {

	public static final String ISSUER = "crm-lab";
	public static final String AUDIENCE = "crm-api";

	@Bean
	SecretKey jwtSigningKey(@Value("${crm.auth.secret-base64}") String secret) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(secret);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("JWT_SECRET must be valid Base64", exception);
		}
		if (bytes.length < 32) {
			throw new IllegalArgumentException("JWT_SECRET must encode at least 32 random bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
			.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
			JwtValidators.createDefaultWithIssuer(ISSUER),
			new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(AUDIENCE)),
			new JwtClaimValidator<String>("sub", subject -> subject != null && subject.matches("[0-9]{1,19}")),
			new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
		return decoder;
	}
}
