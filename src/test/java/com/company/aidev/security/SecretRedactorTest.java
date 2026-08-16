package com.company.aidev.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Everything that leaves the process goes through this class, so the coverage here is deliberate. */
class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    @DisplayName("redacts GitLab and GitHub tokens")
    void shouldRedactVcsTokens() {
        assertThat(redactor.redact("token is glpat-abcdefghij1234567890"))
                .doesNotContain("abcdefghij1234567890")
                .contains("REDACTED");
        assertThat(redactor.redact("ghp_abcdefghij1234567890abcd")).contains("REDACTED");
    }

    @Test
    @DisplayName("redacts credentials embedded in a URL")
    void shouldRedactUrlCredentials() {
        String redacted = redactor.redact("https://oauth2:glpat-secretvalue1234@gitlab.company.com/group/repo.git");

        assertThat(redacted).doesNotContain("glpat-secretvalue1234").contains("gitlab.company.com");
    }

    @Test
    @DisplayName("redacts key/value assignments that look like credentials")
    void shouldRedactAssignments() {
        assertThat(redactor.redact("api_key=super-secret-value")).doesNotContain("super-secret-value");
        assertThat(redactor.redact("password: hunter2xyz")).doesNotContain("hunter2xyz");
        assertThat(redactor.redact("OPENAI_API_KEY=sk-abcdef123456")).doesNotContain("sk-abcdef123456");
    }

    @Test
    @DisplayName("redacts authorization headers and JWTs")
    void shouldRedactAuthorizationAndJwt() {
        assertThat(redactor.redact("Authorization: Bearer abc.def.ghi")).contains("REDACTED");
        assertThat(redactor.redact("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NX0.dBjftJeZ4CVPmB92K27uhbUJU1p1r"))
                .contains("REDACTED");
    }

    @Test
    @DisplayName("redacts a private key block")
    void shouldRedactPrivateKey() {
        String input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----";

        assertThat(redactor.redact(input)).isEqualTo("***REDACTED***");
    }

    @Test
    @DisplayName("leaves ordinary source code untouched")
    void shouldNotMangleSourceCode() {
        String code = "public void suspendFee(CustomerId id) { fees.suspend(id); }";

        assertThat(redactor.redact(code)).isEqualTo(code);
    }

    @Test
    @DisplayName("truncates long content and says so")
    void shouldTruncate() {
        String result = redactor.redactAndTruncate("x".repeat(500), 100);

        assertThat(result).hasSizeLessThan(200).contains("truncated 400 chars");
    }

    @Test
    @DisplayName("handles null and blank input")
    void shouldHandleNullAndBlank() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
    }
}
