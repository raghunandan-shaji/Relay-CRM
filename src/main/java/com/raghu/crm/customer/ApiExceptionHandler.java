package com.raghu.crm.customer;

import com.raghu.crm.sales.SalesException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(SalesException.class)
	ProblemDetail salesError(SalesException exception) {
		return problem(exception.status(), exception.getMessage());
	}

	@ExceptionHandler(BadCredentialsException.class)
	ProblemDetail badCredentials(BadCredentialsException exception) {
		return problem(HttpStatus.UNAUTHORIZED, "Invalid email or password");
	}

	@ExceptionHandler(CustomerNotFoundException.class)
	ProblemDetail notFound(CustomerNotFoundException exception) {
		return problem(HttpStatus.NOT_FOUND, exception.getMessage());
	}

	@ExceptionHandler({DuplicateEmailException.class, ProfileVersionConflictException.class})
	ProblemDetail conflict(RuntimeException exception) {
		return problem(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
		String detail = exception.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.orElse("Request validation failed");
		return problem(HttpStatus.BAD_REQUEST, detail);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail invalidPath(ConstraintViolationException exception) {
		return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(DataAccessException.class)
	ProblemDetail dependencyUnavailable(DataAccessException exception) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "A required data store is unavailable");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalidArgument(IllegalArgumentException exception) {
		return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail malformedBody(HttpMessageNotReadableException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Malformed JSON or unsupported field value");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail invalidParameter(MethodArgumentTypeMismatchException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request parameter type");
	}

	private static ProblemDetail problem(HttpStatus status, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		return problem;
	}
}
