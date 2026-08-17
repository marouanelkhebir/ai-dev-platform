package com.mel.aidev.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mel.aidev.config.AiProperties;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.GitLabException;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.llm.ModelRole;
import com.mel.aidev.persistence.entity.ProjectEntity;
import com.mel.aidev.persistence.entity.ProjectModelEntity;
import com.mel.aidev.persistence.entity.ProjectVariableEntity;
import com.mel.aidev.persistence.repository.ProjectModelRepository;
import com.mel.aidev.persistence.repository.ProjectVariableRepository;
import com.mel.aidev.settings.PlatformSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Precedence of the project values over the platform ones.
 *
 * <p>One rule everywhere: the project wins, otherwise the platform configuration applies, and in no
 * case does an agent decide. The one deliberate exception is the protected branch list, where the
 * platform is a floor rather than a default.
 */
class ProjectConfigurationResolverTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();

    private ProjectVariableRepository variableRepository;
    private ProjectModelRepository modelRepository;
    private GitLabClient gitLabClient;
    private ProjectConfigurationResolver resolver;

    @BeforeEach
    void setUp() {
        variableRepository = mock(ProjectVariableRepository.class);
        modelRepository = mock(ProjectModelRepository.class);
        gitLabClient = mock(GitLabClient.class);

        SandboxProperties sandbox = new SandboxProperties(
                null, "ai-dev-sandbox:21", null, null, null, null, null, null, null, null, null, null, null,
                null, Map.of("MAVEN_OPTS", "-Xmx2g"), List.of(), false);
        GitLabProperties gitlab = new GitLabProperties(
                "https://gitlab.test", "token", null, null, "ai/", "main", List.of("main", "release"), null, null, null, null, null);
        AiProperties ai = new AiProperties(
                null, Map.of(ModelRole.CODING, "coder-model"), Map.of(), List.of("coder-model", "special-coder"));

        PlatformSettings settings =
                PlatformSettings.builder().sandbox(sandbox).gitlab(gitlab).ai(ai).build();

        resolver = new ProjectConfigurationResolver(
                variableRepository, modelRepository, new ArgvCodec(new ObjectMapper()), gitLabClient, settings);

        when(variableRepository.findByProjectIdOrderByNameAsc(PROJECT_ID)).thenReturn(List.of());
        when(modelRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(gitLabClient.getProject(anyString()))
                .thenReturn(new GitLabProject(1L, "cm", "bank/cm", "develop", "url", "url.git"));
    }

    @Test
    @DisplayName("falls back to the platform configuration for every unset field")
    void shouldFallBackToPlatformConfiguration() {
        ProjectConfiguration configuration = resolver.resolve(project());

        assertThat(configuration.sandboxImage()).isNull(); // the profile image is resolved at sandbox time
        assertThat(configuration.branchPrefix()).isEqualTo("ai/");
        assertThat(configuration.protectedBranches()).containsExactly("main", "release");
        assertThat(configuration.testCommand()).isEmpty();
        assertThat(configuration.models()).isEmpty();
        assertThat(configuration.variables()).containsExactly(Map.entry("MAVEN_OPTS", "-Xmx2g"));
        // No branch on the project: the default branch reported by the repository wins.
        assertThat(configuration.baseBranch()).isEqualTo("develop");
    }

    @Test
    @DisplayName("prefers every value the project sets")
    void shouldPreferProjectValues() {
        ProjectEntity project = project();
        project.setDockerImage("registry.test/team/sandbox:21");
        project.setDefaultBranch("integration");
        project.setBranchPrefix("bot/");
        project.setTestCommand("[\"mvn\",\"-B\",\"verify\"]");
        when(variableRepository.findByProjectIdOrderByNameAsc(PROJECT_ID))
                .thenReturn(List.of(new ProjectVariableEntity(PROJECT_ID, "TZ", "Europe/Paris")));
        when(modelRepository.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(new ProjectModelEntity(PROJECT_ID, ModelRole.CODING, "special-coder")));

        ProjectConfiguration configuration = resolver.resolve(project);

        assertThat(configuration.sandboxImage()).isEqualTo("registry.test/team/sandbox:21");
        assertThat(configuration.baseBranch()).isEqualTo("integration");
        assertThat(configuration.branchPrefix()).isEqualTo("bot/");
        assertThat(configuration.testCommand()).containsExactly("mvn", "-B", "verify");
        assertThat(configuration.variables()).containsEntry("TZ", "Europe/Paris").containsKey("MAVEN_OPTS");
        assertThat(configuration.modelFor(ModelRole.CODING)).isEqualTo("special-coder");
        assertThat(configuration.branchFor("BANK-1245")).isEqualTo("bot/BANK-1245");
    }

    /** The global list is a floor: a project may protect more branches, never fewer. */
    @Test
    @DisplayName("adds the project protected branches to the platform ones")
    void shouldUnionProtectedBranches() {
        ProjectEntity project = project();
        project.setProtectedBranches("integration, main");

        assertThat(resolver.resolve(project).protectedBranches())
                .containsExactlyInAnyOrder("main", "release", "integration");
    }

    /** Dropping the pin is the safe direction: the platform mapping then applies. */
    @Test
    @DisplayName("ignores a pinned model that left the allowlist")
    void shouldIgnoreModelNoLongerAllowed() {
        when(modelRepository.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(new ProjectModelEntity(PROJECT_ID, ModelRole.CODING, "retired-model")));

        assertThat(resolver.resolve(project()).models()).isEmpty();
    }

    /**
     * Resolution happens when a workflow is created; refusing to create it because GitLab hiccuped
     * would be worse than starting on the configured default branch.
     */
    @Test
    @DisplayName("falls back to the configured target branch when GitLab is unavailable")
    void shouldFallBackWhenGitLabFails() {
        when(gitLabClient.getProject(anyString())).thenThrow(new GitLabException("unreachable"));

        assertThat(resolver.resolve(project()).baseBranch()).isEqualTo("main");
    }

    private static ProjectEntity project() {
        return new ProjectEntity(PROJECT_ID, "Banque", "bank/cm");
    }
}
