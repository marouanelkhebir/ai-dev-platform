package com.mel.aidev.sandbox;

import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.domain.BuildProfile;
import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.security.ImagePolicy;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Docker implementation of {@link SandboxManager}: one ephemeral container per ticket.
 *
 * <p>Hardening applied to every container:
 *
 * <ul>
 *   <li>no shell is ever spawned — commands go through {@code execCreate} as an argv array;
 *   <li>the executable must pass {@link CommandGuard};
 *   <li>all Linux capabilities are dropped and {@code no-new-privileges} is set;
 *   <li>memory, CPU and PID limits are enforced;
 *   <li>only the environment declared in {@code sandbox.environment} is injected, which is where the
 *       absence of production credentials is enforced.
 * </ul>
 */
@Component
public class DockerSandboxManager implements SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);

    private static final String LABEL_PLATFORM = "com.mel.aidev";
    private static final String LABEL_WORKFLOW = "com.mel.aidev.workflow";
    private static final String LABEL_TICKET = "com.mel.aidev.ticket";

    private final SandboxProperties properties;
    private final CommandGuard commandGuard;
    private final ImagePolicy imagePolicy;
    private final PlatformMetrics metrics;

    private volatile DockerClient dockerClient;

    public DockerSandboxManager(
            SandboxProperties properties,
            CommandGuard commandGuard,
            ImagePolicy imagePolicy,
            PlatformMetrics metrics) {
        this.properties = properties;
        this.commandGuard = commandGuard;
        this.imagePolicy = imagePolicy;
        this.metrics = metrics;
    }

    @Override
    public Sandbox createSandbox(
            UUID workflowId, String jiraTicket, BuildProfile profile, String image, Map<String, String> environment) {
        String workspacePath = properties.workspacePathFor(jiraTicket);
        String containerName = "aidev-" + sanitize(jiraTicket) + "-" + UUID.randomUUID().toString().substring(0, 8);

        // The project image wins; without one, the administrator-configured image of the profile
        // applies. The allowlist is checked here and not only when the project was saved: it may
        // have changed since, and the check that counts is the one made before the container runs.
        String resolvedImage = image == null || image.isBlank() ? properties.imageFor(profile) : image.trim();
        imagePolicy.assertAllowed(resolvedImage);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(properties.memoryLimitBytes())
                .withCpuQuota(properties.cpuQuota())
                .withPidsLimit(properties.pidsLimit())
                .withNetworkMode(properties.networkEnabled() ? "bridge" : "none")
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withReadonlyRootfs(properties.readOnlyRootFilesystem())
                .withAutoRemove(false);

        // Platform variables first: a project variable that collides with one of them is already
        // refused when the project is saved, so this order never silently overrides the platform.
        Map<String, String> effectiveEnvironment = new java.util.LinkedHashMap<>(properties.environment());
        if (environment != null) {
            effectiveEnvironment.putAll(environment);
        }
        List<String> env = effectiveEnvironment.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();

        try {
            CreateContainerResponse container = client().createContainerCmd(resolvedImage)
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    // Keep the container alive; every real command is an exec into it.
                    .withEntrypoint("sleep")
                    .withCmd("infinity")
                    .withWorkingDir(properties.workspaceRoot())
                    .withEnv(env)
                    .withLabels(Map.of(
                            LABEL_PLATFORM, "true",
                            LABEL_WORKFLOW, workflowId.toString(),
                            LABEL_TICKET, jiraTicket))
                    .exec();

            client().startContainerCmd(container.getId()).exec();

            Sandbox sandbox = new Sandbox(
                    UUID.randomUUID(),
                    container.getId(),
                    workspacePath,
                    workspacePath + "/repo",
                    workflowId,
                    jiraTicket,
                    Instant.now());

            // mkdir is intentionally not in the allowlist: the workspace is created by the platform,
            // never by an agent.
            rawExecute(sandbox, List.of("mkdir", "-p", workspacePath), properties.workspaceRoot(), Duration.ofSeconds(30));

            log.info(
                    "Sandbox created profile={} image={} containerId={} name={} workspace={} ticket={}",
                    profile,
                    resolvedImage,
                    shortId(container.getId()),
                    containerName,
                    workspacePath,
                    jiraTicket);
            return sandbox;
        } catch (RuntimeException e) {
            throw new SandboxException("Unable to create sandbox for ticket " + jiraTicket, e);
        }
    }

    @Override
    public CommandResult execute(Sandbox sandbox, List<String> command) {
        return execute(sandbox, command, sandbox.repositoryPath(), properties.commandTimeout());
    }

    @Override
    public CommandResult execute(Sandbox sandbox, List<String> command, String workingDirectory, Duration timeout) {
        commandGuard.validate(command);
        return rawExecute(sandbox, command, workingDirectory, timeout, Map.of());
    }

    @Override
    public CommandResult execute(
            Sandbox sandbox,
            List<String> command,
            String workingDirectory,
            Duration timeout,
            Map<String, String> environment) {
        commandGuard.validate(command);
        return rawExecute(sandbox, command, workingDirectory, timeout, environment);
    }

    private CommandResult rawExecute(Sandbox sandbox, List<String> command, String workingDirectory, Duration timeout) {
        return rawExecute(sandbox, command, workingDirectory, timeout, Map.of());
    }

    /** Executes without the allowlist check. Reserved for platform-issued setup commands. */
    private CommandResult rawExecute(
            Sandbox sandbox,
            List<String> command,
            String workingDirectory,
            Duration timeout,
            Map<String, String> environment) {
        Instant start = Instant.now();
        boolean success = false;
        try {
            var execCmd = client().execCreateCmd(sandbox.containerId())
                    .withCmd(command.toArray(new String[0]))
                    .withWorkingDir(workingDirectory)
                    .withAttachStdout(true)
                    .withAttachStderr(true);
            if (!environment.isEmpty()) {
                execCmd.withEnv(environment.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList());
            }
            ExecCreateCmdResponse exec = execCmd.exec();

            BoundedOutputCollector collector = new BoundedOutputCollector(properties.maxOutputChars());
            boolean completed;
            try {
                completed = client().execStartCmd(exec.getId())
                        .exec(collector)
                        .awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxException("Interrupted while running " + command, e);
            }

            if (!completed) {
                log.warn("Command timed out after {} in container {}: {}", timeout, shortId(sandbox.containerId()), command);
                return new CommandResult(
                        command,
                        124,
                        collector.stdout(),
                        collector.stderr(),
                        Duration.between(start, Instant.now()),
                        true);
            }

            Long exitCode = client().inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            int code = exitCode == null ? -1 : exitCode.intValue();
            success = code == 0;
            return new CommandResult(
                    command, code, collector.stdout(), collector.stderr(), Duration.between(start, Instant.now()), false);
        } catch (SandboxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SandboxException("Command failed in sandbox: " + command, e);
        } finally {
            metrics.sandboxCommand(command.isEmpty() ? "unknown" : command.get(0), Duration.between(start, Instant.now()), success);
        }
    }

    @Override
    public String readFile(Sandbox sandbox, String relativePath) {
        String absolutePath = WorkspacePaths.resolve(sandbox, relativePath);
        try (InputStream tar = client().copyArchiveFromContainerCmd(sandbox.containerId(), absolutePath).exec();
                TarArchiveInputStream tarStream = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry = tarStream.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()) {
                    return new String(tarStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                entry = tarStream.getNextEntry();
            }
            throw new SandboxException("Not a regular file: " + relativePath);
        } catch (NotFoundException e) {
            throw new SandboxException("File not found: " + relativePath, e);
        } catch (IOException e) {
            throw new SandboxException("Unable to read " + relativePath, e);
        }
    }

    @Override
    public void writeFile(Sandbox sandbox, String relativePath, String content) {
        String absolutePath = WorkspacePaths.resolve(sandbox, relativePath);
        WorkspacePaths.assertWritable(absolutePath);

        String parent = absolutePath.substring(0, absolutePath.lastIndexOf('/'));
        String fileName = absolutePath.substring(absolutePath.lastIndexOf('/') + 1);
        byte[] payload = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);

        byte[] archive = buildTar(fileName, payload);
        try (InputStream in = new ByteArrayInputStream(archive)) {
            // Docker refuses to extract into a missing directory, so create it first.
            rawExecute(sandbox, List.of("mkdir", "-p", parent), sandbox.workspacePath(), Duration.ofSeconds(30));
            client().copyArchiveToContainerCmd(sandbox.containerId())
                    .withRemotePath(parent)
                    .withTarInputStream(in)
                    .exec();
        } catch (IOException e) {
            throw new SandboxException("Unable to write " + relativePath, e);
        }
    }

    @Override
    public boolean exists(Sandbox sandbox, String relativePath) {
        String absolutePath = WorkspacePaths.resolve(sandbox, relativePath);
        return rawExecute(sandbox, List.of("test", "-e", absolutePath), sandbox.workspacePath(), Duration.ofSeconds(30))
                .successful();
    }

    @Override
    public void destroySandbox(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            client().removeContainerCmd(sandbox.containerId())
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("Sandbox destroyed containerId={} ticket={}", shortId(sandbox.containerId()), sandbox.jiraTicket());
        } catch (NotFoundException e) {
            log.debug("Sandbox already gone containerId={}", shortId(sandbox.containerId()));
        } catch (RuntimeException e) {
            // Never propagate: a leaked container must not fail the workflow, the janitor will get it.
            log.warn("Unable to destroy sandbox containerId={}: {}", shortId(sandbox.containerId()), e.toString());
        }
    }

    /**
     * Removes containers left behind by a crash or by a workflow that exceeded the maximum lifetime.
     *
     * @return number of containers removed
     */
    @Override
    public int cleanupStaleSandboxes() {
        Instant threshold = Instant.now().minus(properties.maxLifetime());
        int removed = 0;
        try {
            List<Container> containers = client().listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_PLATFORM, "true"))
                    .exec();
            for (Container container : containers) {
                Instant created = Instant.ofEpochSecond(container.getCreated());
                if (created.isBefore(threshold)) {
                    try {
                        client().removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
                        removed++;
                        log.info("Removed stale sandbox containerId={} createdAt={}", shortId(container.getId()), created);
                    } catch (RuntimeException e) {
                        log.warn("Unable to remove stale sandbox {}: {}", shortId(container.getId()), e.toString());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Sandbox janitor could not list containers: {}", e.toString());
        }
        return removed;
    }

    private DockerClient client() {
        DockerClient current = dockerClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (dockerClient == null) {
                dockerClient = buildClient();
            }
            return dockerClient;
        }
    }

    private DockerClient buildClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(properties.dockerHost())
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(properties.commandTimeout().plusMinutes(5))
                .build();
        log.info("Docker client initialised host={} image={}", properties.dockerHost(), properties.image());
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @PreDestroy
    void close() {
        DockerClient current = dockerClient;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("Docker client close failed: {}", e.toString());
            }
        }
    }

    private static byte[] buildTar(String fileName, byte[] payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(fileName);
            entry.setSize(payload.length);
            entry.setMode(0644);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to build tar archive", e);
        }
        return buffer.toByteArray();
    }

    private static String sanitize(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return cleaned.isBlank() ? "ticket" : cleaned;
    }

    private static String shortId(String containerId) {
        return containerId == null || containerId.length() < 12 ? String.valueOf(containerId) : containerId.substring(0, 12);
    }

    /** Collects stdout and stderr while capping memory usage. */
    private static final class BoundedOutputCollector extends ResultCallback.Adapter<Frame> {

        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private final int maxChars;

        private BoundedOutputCollector(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void onNext(Frame frame) {
            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
            StringBuilder target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
            if (target.length() < maxChars) {
                target.append(text);
            }
        }

        private String stdout() {
            return truncate(stdout);
        }

        private String stderr() {
            return truncate(stderr);
        }

        private String truncate(StringBuilder builder) {
            if (builder.length() <= maxChars) {
                return builder.toString();
            }
            return builder.substring(0, maxChars) + "\n...[output truncated]";
        }
    }

    /** Exposed for the janitor scheduler and tests. */
    List<String> allowedExecutables() {
        return new ArrayList<>(properties.allowedExecutables());
    }
}
