package io.openmesh.gateway.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/** Business error mapped to a JSON {@code ApiError} by {@link GlobalExceptionHandler}. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public static ApiException badRequest(String message, List<String> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, details);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
