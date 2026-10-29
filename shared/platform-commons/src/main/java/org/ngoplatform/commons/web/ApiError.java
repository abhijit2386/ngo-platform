package org.ngoplatform.commons.web;

import java.time.Instant;
import java.util.List;

/** Uniform error body (spec §21.3). */
public record ApiError(String code, String message, int status, String correlationId,
                       List<FieldViolation> fieldErrors, Instant timestamp) {
    public record FieldViolation(String field, String code, String message) {}
}
