package com.mel.aidev.gitlab.model;

/**
 * Raw output of a GitLab security scanner, retrieved from the job artifacts.
 *
 * <p>The security agent interprets these reports; it does not replace them. Scanners find the known
 * vulnerabilities, the agent explains which ones actually matter in this change.
 */
public record ScannerReport(String kind, String jobName, String content) {

    public static final String SAST = "sast";
    public static final String DEPENDENCY_SCANNING = "dependency-scanning";
    public static final String SECRET_DETECTION = "secret-detection";

    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
