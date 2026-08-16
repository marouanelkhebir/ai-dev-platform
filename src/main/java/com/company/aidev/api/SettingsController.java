package com.company.aidev.api;

import com.company.aidev.settings.PlatformReadiness;
import com.company.aidev.settings.SettingsService;
import com.company.aidev.settings.SettingsValidationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administration API backing the settings screen. Secret values are never returned. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsResponse get() {
        return response();
    }

    @PutMapping
    public SettingsResponse update(
            @RequestBody Map<String, String> changes,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        settings.update(changes, actor == null || actor.isBlank() ? "settings-console" : actor);
        return response();
    }

    @DeleteMapping("/{key:.+}")
    public SettingsResponse reset(@PathVariable String key, @RequestHeader(value = "X-Actor", required = false) String actor) {
        settings.reset(key, actor == null || actor.isBlank() ? "settings-console" : actor);
        return response();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SettingsValidationException.class)
    public ResponseEntity<ApiError> invalid(SettingsValidationException error) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", error.getMessage(), error.getDetails()));
    }

    private SettingsResponse response() {
        return new SettingsResponse(settings.version(), settings.readiness(), settings.values());
    }

    public record SettingsResponse(
            long version, PlatformReadiness readiness, List<SettingsService.SettingValue> settings) {}
}
