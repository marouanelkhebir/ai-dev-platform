package com.company.aidev.git;

import com.company.aidev.config.GitLabProperties;
import com.company.aidev.gitlab.model.GitLabProject;
import com.company.aidev.sandbox.CommandResult;
import com.company.aidev.sandbox.Sandbox;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.security.BranchPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Git operations issued by the platform, not by an agent.
 *
 * <p>Cloning, committing and pushing are privileged actions and are therefore deliberately absent
 * from the tools handed to the developer agent. The agent edits files and runs tests; the platform
 * decides what becomes a commit and where it goes.
 *
 * <p>The GitLab token is injected through {@code GIT_CONFIG_*} environment variables rather than
 * embedded in the remote URL. It therefore never reaches {@code .git/config}, {@code git remote -v}
 * or the reflog, all of which the agent can read.
 */
@Component
public class GitOperations {

    private static final Logger log = LoggerFactory.getLogger(GitOperations.class);

    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration QUICK_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(5);

    private final SandboxManager sandboxManager;
    private final GitLabProperties properties;
    private final BranchPolicy branchPolicy;

    public GitOperations(SandboxManager sandboxManager, GitLabProperties properties, BranchPolicy branchPolicy) {
        this.sandboxManager = sandboxManager;
        this.properties = properties;
        this.branchPolicy = branchPolicy;
    }

    /**
     * Clones the repository into the sandbox and checks out the base branch.
     *
     * <p>A shallow clone keeps the sandbox small; the depth is large enough for {@code git diff}
     * against the base branch to work.
     */
    public void cloneRepository(Sandbox sandbox, GitLabProject project, String baseBranch) {
        String cloneUrl = project.httpUrlToRepo();
        if (cloneUrl == null || cloneUrl.isBlank()) {
            cloneUrl = properties.rootUrl() + "/" + project.pathWithNamespace() + ".git";
        }
        CommandResult result = run(
                sandbox,
                List.of("git", "clone", "--depth", "50", "--branch", baseBranch, cloneUrl, sandbox.repositoryPath()),
                sandbox.workspacePath(),
                CLONE_TIMEOUT);
        assertSuccess(result, "clone " + project.pathWithNamespace());
        log.info("Repository cloned project={} branch={} into {}", project.pathWithNamespace(), baseBranch, sandbox.repositoryPath());
    }

    /** Creates the {@code ai/TICKET} branch from the current HEAD. */
    public void createBranch(Sandbox sandbox, String branch) {
        branchPolicy.assertAgentBranch(branch);
        branchPolicy.assertNotProtected(branch);
        CommandResult result = run(sandbox, List.of("git", "checkout", "-B", branch), sandbox.repositoryPath(), QUICK_TIMEOUT);
        assertSuccess(result, "create branch " + branch);
    }

    /** Checks out an existing remote {@code ai/*} branch, used when a review asks for changes. */
    public void checkoutExistingBranch(Sandbox sandbox, String branch) {
        branchPolicy.assertAgentBranch(branch);
        CommandResult fetch = run(
                sandbox,
                List.of("git", "fetch", "origin", branch + ":" + branch),
                sandbox.repositoryPath(),
                QUICK_TIMEOUT);
        assertSuccess(fetch, "fetch branch " + branch);
        CommandResult checkout =
                run(sandbox, List.of("git", "checkout", branch), sandbox.repositoryPath(), QUICK_TIMEOUT);
        assertSuccess(checkout, "checkout branch " + branch);
    }

    public boolean hasUncommittedChanges(Sandbox sandbox) {
        CommandResult result =
                run(sandbox, List.of("git", "status", "--porcelain"), sandbox.repositoryPath(), QUICK_TIMEOUT);
        assertSuccess(result, "git status");
        return !result.stdout().isBlank();
    }

    public String status(Sandbox sandbox) {
        return run(sandbox, List.of("git", "status", "--porcelain"), sandbox.repositoryPath(), QUICK_TIMEOUT).stdout();
    }

    /** Unified diff of the working tree, including staged changes. */
    public String diff(Sandbox sandbox, int maxChars) {
        CommandResult result =
                run(sandbox, List.of("git", "diff", "HEAD", "--unified=3"), sandbox.repositoryPath(), QUICK_TIMEOUT);
        String diff = result.stdout();
        if (diff.length() > maxChars) {
            return diff.substring(0, maxChars) + "\n...[diff truncated]";
        }
        return diff;
    }

    /** Stages everything and commits. Returns false when there was nothing to commit. */
    public boolean commitAll(Sandbox sandbox, String message) {
        CommandResult add = run(sandbox, List.of("git", "add", "-A"), sandbox.repositoryPath(), QUICK_TIMEOUT);
        assertSuccess(add, "git add");

        if (!hasUncommittedChanges(sandbox)) {
            log.info("Nothing to commit in sandbox {}", sandbox.jiraTicket());
            return false;
        }

        CommandResult commit = run(
                sandbox,
                List.of("git", "commit", "-m", message),
                sandbox.repositoryPath(),
                QUICK_TIMEOUT,
                identityEnvironment());
        assertSuccess(commit, "git commit");
        return true;
    }

    /**
     * Pushes the agent branch.
     *
     * <p>The refspec is explicit ({@code HEAD:refs/heads/ai/...}) so that a mistaken local branch
     * name can never end up updating another ref.
     */
    public void push(Sandbox sandbox, String branch) {
        branchPolicy.assertAgentBranch(branch);
        branchPolicy.assertNotProtected(branch);

        CommandResult result = run(
                sandbox,
                List.of("git", "push", "--force-with-lease", "origin", "HEAD:refs/heads/" + branch),
                sandbox.repositoryPath(),
                PUSH_TIMEOUT);
        assertSuccess(result, "push " + branch);
        log.info("Branch pushed branch={} ticket={}", branch, sandbox.jiraTicket());
    }

    public String currentCommitSha(Sandbox sandbox) {
        CommandResult result =
                run(sandbox, List.of("git", "rev-parse", "HEAD"), sandbox.repositoryPath(), QUICK_TIMEOUT);
        assertSuccess(result, "git rev-parse");
        return result.stdout().trim();
    }

    private CommandResult run(Sandbox sandbox, List<String> command, String workingDir, Duration timeout) {
        return run(sandbox, command, workingDir, timeout, Map.of());
    }

    private CommandResult run(
            Sandbox sandbox, List<String> command, String workingDir, Duration timeout, Map<String, String> extraEnv) {
        Map<String, String> environment = new LinkedHashMap<>(authenticationEnvironment());
        environment.putAll(extraEnv);
        return sandboxManager.execute(sandbox, command, workingDir, timeout, environment);
    }

    /**
     * Supplies credentials to git without writing them anywhere on disk.
     *
     * <p>{@code GIT_TERMINAL_PROMPT=0} matters: without it a rejected token turns into a blocking
     * prompt and the command hangs until the sandbox timeout fires.
     */
    private Map<String, String> authenticationEnvironment() {
        String basic = Base64.getEncoder()
                .encodeToString(("oauth2:" + properties.apiToken()).getBytes(StandardCharsets.UTF_8));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_COUNT", "2");
        env.put("GIT_CONFIG_KEY_0", "http.extraheader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        env.put("GIT_CONFIG_KEY_1", "advice.detachedHead");
        env.put("GIT_CONFIG_VALUE_1", "false");
        return env;
    }

    private Map<String, String> identityEnvironment() {
        return Map.of(
                "GIT_AUTHOR_NAME", properties.botName(),
                "GIT_AUTHOR_EMAIL", properties.botEmail(),
                "GIT_COMMITTER_NAME", properties.botName(),
                "GIT_COMMITTER_EMAIL", properties.botEmail());
    }

    private static void assertSuccess(CommandResult result, String action) {
        if (!result.successful()) {
            throw new GitOperationException(
                    "Git operation failed (" + action + ", exit " + result.exitCode() + "): " + result.combinedOutput());
        }
    }

    /** Raised when a git command fails inside the sandbox. */
    public static class GitOperationException extends RuntimeException {
        public GitOperationException(String message) {
            super(message);
        }
    }
}
