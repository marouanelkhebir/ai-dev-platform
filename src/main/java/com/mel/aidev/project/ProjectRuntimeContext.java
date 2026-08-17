package com.mel.aidev.project;

import com.mel.aidev.llm.ModelRole;
import java.util.List;
import java.util.UUID;

/**
 * Thread-local view of the project configuration of the workflow being advanced.
 *
 * <p>Opened once by the engine for the whole run, closed with try-with-resources. It exists because
 * three collaborators need the project without being able to receive it as a parameter: the model
 * provider is called from inside LangChain4j, the LLM audit listener is a callback with no business
 * context, and the build tools are instantiated per agent execution. Everything else — the sandbox
 * image, the branch policy — receives the configuration explicitly, which is always preferable.
 */
public final class ProjectRuntimeContext implements AutoCloseable {

    private static final ThreadLocal<ProjectRuntimeContext> CURRENT = new ThreadLocal<>();

    private final ProjectConfiguration configuration;
    private final ProjectRuntimeContext previous;

    private ProjectRuntimeContext(ProjectConfiguration configuration, ProjectRuntimeContext previous) {
        this.configuration = configuration;
        this.previous = previous;
    }

    public static ProjectRuntimeContext open(ProjectConfiguration configuration) {
        ProjectRuntimeContext context = new ProjectRuntimeContext(configuration, CURRENT.get());
        CURRENT.set(context);
        return context;
    }

    public static ProjectConfiguration current() {
        ProjectRuntimeContext context = CURRENT.get();
        return context == null ? null : context.configuration;
    }

    public static UUID currentProjectId() {
        ProjectConfiguration configuration = current();
        return configuration == null ? null : configuration.projectId();
    }

    /** Model pinned by the current project for a role, or null when none applies. */
    public static String currentModel(ModelRole role) {
        ProjectConfiguration configuration = current();
        return configuration == null ? null : configuration.modelFor(role);
    }

    /** Test command of the current project, empty when the profile default applies. */
    public static List<String> currentTestCommand() {
        ProjectConfiguration configuration = current();
        return configuration == null ? List.of() : configuration.testCommand();
    }

    public static List<String> currentBuildCommand() {
        ProjectConfiguration configuration = current();
        return configuration == null ? List.of() : configuration.buildCommand();
    }

    public static List<String> currentLintCommand() {
        ProjectConfiguration configuration = current();
        return configuration == null ? List.of() : configuration.lintCommand();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
