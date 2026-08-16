package com.company.aidev.api;

import java.time.Instant;
import java.util.List;

/** Error payload returned by the API. */
public record ApiError(Instant timestamp, int status, String error, String message, List<String> details) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message, List.of());
    }

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, details == null ? List.of() : List.copyOf(details));
    }
}
