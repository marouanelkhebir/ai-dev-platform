package com.mel.aidev.project;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mel.aidev.config.AiProperties;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.sandbox.CommandGuard;
import com.mel.aidev.security.ImagePolicy;
import com.mel.aidev.settings.PlatformSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a project must satisfy before it is stored.
 *
 * <p>The rules that matter most here are the ones about variables: their values reach the container
 * in clear text and are readable by the agents, so a credential placed there is a credential handed
 * to a model.
 */
class ProjectValidatorTest {

    private GitLabClient gitLabClient;
    private JiraClient jiraClient;
    private ProjectValidator validator;

    @BeforeEach
    void setUp() {
        gitLabClient = mock(GitLabClient.class);
        jiraClient = mock(JiraClient.class);

        SandboxProperties sandbox = new SandboxProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of("mvn", "npm", "python"), Map.of("MAVEN_OPTS", "-Xmx2g"), List.of(), false);
        AiProperties ai = new AiProperties(null, Map.of(ModelRole.CODING, "coder-model"), Map.of(), List.of("coder-model", "reasoning-model"));
        GitLabProperties gitlab = new GitLabProperties(
                "https://gitlab.test", "token", null, null, "ai/", "main", List.of("main", "release"), null, null, null, null, null);
        JiraProperties jira = new JiraProperties(
                "https://jira.test", "bot@test", "token", "3", null, null, null, null, null, null);

        PlatformSettings settings = PlatformSettings.builder()
                .sandbox(sandbox)
                .ai(ai)
                .gitlab(gitlab)
                .jira(jira)
                .build();

        validator = new ProjectValidator(
                gitLabClient, jiraClient, new CommandGuard(sandbox), new ImagePolicy(settings), settings);

        when(gitLabClient.getProject(anyString()))
                .thenReturn(new GitLabProject(1L, "cm", "bank/cm", "main", "url", "url.git"));
        when(jiraClient.projectExists(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("refuses a repository the current configuration cannot reach")
    void shouldRefuseUnreachableRepository() {
        when(gitLabClient.getProject("bank/ghost")).thenThrow(new GitLabException("HTTP 404"));

        assertThatThrownBy(() -> validator.validateGitLabProject("bank/ghost"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("bank/ghost");
    }

    @Test
    @DisplayName("refuses a Jira key that is not one, and one no Jira project carries")
    void shouldValidateJiraKey() {
        assertThatThrownBy(() -> validator.validateJiraProjectKey("bank-1245"))
                .isInstanceOf(ProjectValidationException.class);

        when(jiraClient.projectExists("GHOST")).thenReturn(false);
        assertThatThrownBy(() -> validator.validateJiraProjectKey("GHOST"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("GHOST");

        assertThatCode(() -> validator.validateJiraProjectKey("BANK")).doesNotThrowAnyException();
        // A project without a Jira key is legitimate: it only accepts free-form requests.
        assertThatCode(() -> validator.validateJiraProjectKey(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a command the sandbox would refuse at execution time")
    void shouldRefuseCommandTheSandboxRejects() {
        assertThatThrownBy(() -> validator.validateCommand("test", List.of("bash", "-c", "mvn test")))
                .isInstanceOf(ProjectValidationException.class);
        assertThatThrownBy(() -> validator.validateCommand("test", List.of("curl", "https://evil.test")))
                .isInstanceOf(ProjectValidationException.class);

        assertThatCode(() -> validator.validateCommand("test", List.of("mvn", "-B", "verify")))
                .doesNotThrowAnyException();
        // An empty command means "use the profile default", which is always acceptable.
        assertThatCode(() -> validator.validateCommand("test", List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a model outside the platform allowlist")
    void shouldRefuseModelOutsideAllowlist() {
        assertThatThrownBy(() -> validator.validateModels(Map.of(ModelRole.CODING, "some-other-model")))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("allowlist");

        assertThatCode(() -> validator.validateModels(Map.of(ModelRole.CODING, "coder-model")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a variable whose name or value looks like a secret")
    void shouldRefuseSecretLookingVariables() {
        assertThatThrownBy(() -> validator.validateVariable("GITLAB_TOKEN", "value"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("secret");
        assertThatThrownBy(() -> validator.validateVariable("API_KEY", "value"))
                .isInstanceOf(ProjectValidationException.class);
        assertThatThrownBy(() -> validator.validateVariable("BUILD_HINT", "glpat-abcdefghijklmnopqrst"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("credential");

        assertThatCode(() -> validator.validateVariable("TZ", "Europe/Paris")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a variable that would override the platform sandbox environment")
    void shouldRefuseOverridingPlatformVariable() {
        assertThatThrownBy(() -> validator.validateVariable("MAVEN_OPTS", "-Xmx8g"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("platform");
    }

    @Test
    @DisplayName("refuses a branch prefix that is not one, or that targets a protected namespace")
    void shouldValidateBranchPrefix() {
        assertThatThrownBy(() -> validator.validateBranchPrefix("ai"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("/");
        assertThatThrownBy(() -> validator.validateBranchPrefix("release/"))
                .isInstanceOf(ProjectValidationException.class)
                .hasMessageContaining("protected");

        assertThatCode(() -> validator.validateBranchPrefix("bot/ai/")).doesNotThrowAnyException();
    }
}
