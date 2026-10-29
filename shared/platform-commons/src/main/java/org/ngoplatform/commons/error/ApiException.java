package org.ngoplatform.commons.error;

import org.springframework.http.HttpStatus;

/** Business/API failure with a stable machine-readable code. Never carries stack traces to clients. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    /** Request is well-formed but violates a business rule (422). */
    public static ApiException rule(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    /** Entitlement missing for current plan (402 is semantically "payment required"). */
    public static ApiException entitlement(String key, String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, "ENTITLEMENT_REQUIRED:" + key, message);
    }
}
