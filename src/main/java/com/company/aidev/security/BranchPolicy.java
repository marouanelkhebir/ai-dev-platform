package com.company.aidev.security;

import com.company.aidev.config.GitLabProperties;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Enforces where the platform is allowed to write.
 *
 * <p>The GitLab token should already be restricted to {@code ai/*} by branch protection rules; this
 * class is the second lock. Both exist because the first one is configured by humans in a UI, and
 * the second one is covered by tests.
 */
@Component
public class BranchPolicy {

    private final GitLabProperties properties;

    public BranchPolicy(GitLabProperties properties) {
        this.properties = properties;
    }

    /** Branch name the platform uses for a ticket, e.g. {@code ai/BANK-1245}. */
    public String branchFor(String jiraTicket) {
        String sanitized = jiraTicket.trim().replaceAll("[^A-Za-z0-9._/-]", "-");
        return properties.branchPrefix() + sanitized;
    }

    public boolean isAgentBranch(String branch) {
        return branch != null && branch.startsWith(properties.branchPrefix());
    }

    public boolean isProtected(String branch) {
        if (branch == null) {
            return true;
        }
        String normalized = branch.toLowerCase(Locale.ROOT);
        return properties.protectedBranches().stream()
                .anyMatch(protectedBranch -> {
                    String candidate = protectedBranch.toLowerCase(Locale.ROOT);
                    return normalized.equals(candidate) || normalized.startsWith(candidate + "/");
                });
    }

    /**
     * @throws BranchPolicyViolationException when the branch is not an {@code ai/*} branch
     */
    public void assertAgentBranch(String branch) {
        if (!isAgentBranch(branch)) {
            throw new BranchPolicyViolationException(
                    "Agents may only write on branches prefixed with '" + properties.branchPrefix() + "', got: " + branch);
        }
    }

    /**
     * @throws BranchPolicyViolationException when the branch is protected
     */
    public void assertNotProtected(String branch) {
        if (isProtected(branch)) {
            throw new BranchPolicyViolationException("Writing on protected branch is forbidden: " + branch);
        }
    }

    /** Raised when the platform is asked to write somewhere it must never write. */
    public static class BranchPolicyViolationException extends RuntimeException {
        public BranchPolicyViolationException(String message) {
            super(message);
        }
    }
}
