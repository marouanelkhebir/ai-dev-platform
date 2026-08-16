package com.company.aidev.workflow;

import com.company.aidev.agent.AcceptanceAgent;
import com.company.aidev.agent.ArchitectAgent;
import com.company.aidev.agent.DeveloperAgent;
import com.company.aidev.agent.JiraAnalystAgent;
import com.company.aidev.agent.ReviewerAgent;
import com.company.aidev.agent.SecurityAgent;
import com.company.aidev.agent.TestAgent;
import com.company.aidev.config.GitLabProperties;
import com.company.aidev.config.JiraProperties;
import com.company.aidev.config.WorkflowProperties;
import com.company.aidev.domain.AcceptanceReport;
import com.company.aidev.domain.BuildProfile;
import com.company.aidev.domain.CodeReview;
import com.company.aidev.domain.DevelopmentResult;
import com.company.aidev.domain.RepositoryContext;
import com.company.aidev.domain.SecurityReport;
import com.company.aidev.domain.TechnicalPlan;
import com.company.aidev.domain.TestReport;
import com.company.aidev.domain.TicketAnalysis;
import com.company.aidev.git.GitOperations;
import com.company.aidev.gitlab.GitLabClient;
import com.company.aidev.gitlab.model.CreateMergeRequestCommand;
import com.company.aidev.gitlab.model.GitLabProject;
import com.company.aidev.gitlab.model.MergeRequest;
import com.company.aidev.jira.JiraClient;
import com.company.aidev.jira.model.JiraIssue;
import com.company.aidev.observability.LogContext;
import com.company.aidev.observability.PlatformMetrics;
import com.company.aidev.persistence.entity.MergeRequestEntity;
import com.company.aidev.persistence.entity.ReviewResultEntity;
import com.company.aidev.persistence.entity.TestResultEntity;
import com.company.aidev.persistence.entity.WorkflowEntity;
import com.company.aidev.persistence.entity.WorkflowStepEntity;
import com.company.aidev.persistence.repository.MergeRequestRepository;
import com.company.aidev.persistence.repository.ReviewResultRepository;
import com.company.aidev.persistence.repository.TestResultRepository;
import com.company.aidev.rules.RepositoryRulesLoader;
import com.company.aidev.sandbox.Sandbox;
import com.company.aidev.sandbox.SandboxManager;
import com.company.aidev.security.BranchPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The orchestrator: a persisted state machine over the agent team.
 *
 * <p>One call to {@link #advance(UUID)} executes as many steps as possible and stops as soon as the
 * workflow has to wait for a human. Every
 * transition is written to the database before the next one starts, so a restart resumes from the
 * last completed step rather than from the beginning.
 *
 * <p>Three loops are bounded, and each has its own counter so that a ticket cannot burn the budget of
 * another: development attempts and review attempts.
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private static final int MAX_DIFF_CHARS = 120_000;
    private static final int MAX_SCANNER_REPORT_CHARS = 40_000;
    private static final int MAX_STEPS_PER_RUN = 40;

    /** Guards against two threads of the same instance advancing the same workflow. */
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    private final WorkflowStateStore stateStore;
    private final WorkflowArtifactCodec codec;
    private final WorkflowProperties workflowProperties;
    private final GitLabProperties gitLabProperties;
    private final JiraProperties jiraProperties;

    private final JiraClient jiraClient;
    private final GitLabClient gitLabClient;
    private final SandboxManager sandboxManager;
    private final GitOperations gitOperations;
    private final RepositoryRulesLoader rulesLoader;
    private final BranchPolicy branchPolicy;
    private final MergeRequestReportBuilder reportBuilder;
    private final PlatformMetrics metrics;

    private final JiraAnalystAgent jiraAnalystAgent;
    private final ArchitectAgent architectAgent;
    private final DeveloperAgent developerAgent;
    private final TestAgent testAgent;
    private final ReviewerAgent reviewerAgent;
    private final SecurityAgent securityAgent;
    private final AcceptanceAgent acceptanceAgent;

    private final MergeRequestRepository mergeRequestRepository;
    private final TestResultRepository testResultRepository;
    private final ReviewResultRepository reviewResultRepository;

    @SuppressWarnings("java:S107") // an orchestrator legitimately depends on every collaborator
    public WorkflowEngine(
            WorkflowStateStore stateStore,
            WorkflowArtifactCodec codec,
            WorkflowProperties workflowProperties,
            GitLabProperties gitLabProperties,
            JiraProperties jiraProperties,
            JiraClient jiraClient,
            GitLabClient gitLabClient,
            SandboxManager sandboxManager,
            GitOperations gitOperations,
            RepositoryRulesLoader rulesLoader,
            BranchPolicy branchPolicy,
            MergeRequestReportBuilder reportBuilder,
            PlatformMetrics metrics,
            JiraAnalystAgent jiraAnalystAgent,
            ArchitectAgent architectAgent,
            DeveloperAgent developerAgent,
            TestAgent testAgent,
            ReviewerAgent reviewerAgent,
            SecurityAgent securityAgent,
            AcceptanceAgent acceptanceAgent,
            MergeRequestRepository mergeRequestRepository,
            TestResultRepository testResultRepository,
            ReviewResultRepository reviewResultRepository) {
        this.stateStore = stateStore;
        this.codec = codec;
        this.workflowProperties = workflowProperties;
        this.gitLabProperties = gitLabProperties;
        this.jiraProperties = jiraProperties;
        this.jiraClient = jiraClient;
        this.gitLabClient = gitLabClient;
        this.sandboxManager = sandboxManager;
        this.gitOperations = gitOperations;
        this.rulesLoader = rulesLoader;
        this.branchPolicy = branchPolicy;
        this.reportBuilder = reportBuilder;
        this.metrics = metrics;
        this.jiraAnalystAgent = jiraAnalystAgent;
        this.architectAgent = architectAgent;
        this.developerAgent = developerAgent;
        this.testAgent = testAgent;
        this.reviewerAgent = reviewerAgent;
        this.securityAgent = securityAgent;
        this.acceptanceAgent = acceptanceAgent;
        this.mergeRequestRepository = mergeRequestRepository;
        this.testResultRepository = testResultRepository;
        this.reviewResultRepository = reviewResultRepository;
    }

    /**
     * Advances a workflow until it reaches a terminal state or has to wait for an external event.
     *
     * <p>Never throws: a failure is recorded on the workflow and surfaced through its status, because
     * the caller is usually a webhook or a scheduler that can do nothing useful with an exception.
     */
    public void advance(UUID workflowId) {
        if (inFlight.putIfAbsent(workflowId, Boolean.TRUE) != null) {
            log.debug("Workflow {} is already being advanced in this instance", workflowId);
            return;
        }
        try {
            Optional<WorkflowEntity> claimed = stateStore.claim(workflowId);
            if (claimed.isEmpty()) {
                return;
            }
            WorkflowRun run = new WorkflowRun(claimed.get());
            try (var ignored = LogContext.of()
                    .workflow(workflowId)
                    .jiraTicket(run.workflow().getJiraTicket())
                    .gitlabProject(run.workflow().getGitlabProject())
                    .branch(run.workflow().getBranch())
                    .apply()) {
                runSteps(run);
            } finally {
                destroySandbox(run);
                stateStore.release(workflowId);
            }
        } catch (RuntimeException e) {
            log.error("Unrecoverable error while advancing workflow {}", workflowId, e);
        } finally {
            inFlight.remove(workflowId);
        }
    }

    private void runSteps(WorkflowRun run) {
        int executed = 0;
        while (run.workflow().getStatus().isRunnable() && executed++ < MAX_STEPS_PER_RUN) {
            WorkflowStatus from = run.workflow().getStatus();
            WorkflowStepEntity step = stateStore.beginStep(run.workflow().getId(), from);
            try {
                StepOutcome outcome = executeStep(run, from);
                applyOutcome(run, outcome);
                stateStore.completeStep(step, outcome.nextStatus(), true, outcome.detail(), null);
            } catch (RuntimeException e) {
                log.error("Step {} failed for workflow {}", from, run.workflow().getId(), e);
                fail(run, "Step " + from + " failed: " + e.getMessage());
                stateStore.completeStep(step, WorkflowStatus.FAILED, false, null, e.toString());
                return;
            }
        }
        if (executed >= MAX_STEPS_PER_RUN) {
            // A run that never settles means the state machine is oscillating; stopping loudly is
            // better than looping until the sandbox lifetime expires.
            fail(run, "Workflow exceeded " + MAX_STEPS_PER_RUN + " steps in a single run");
        }
    }

    private StepOutcome executeStep(WorkflowRun run, WorkflowStatus status) {
        return switch (status) {
            case CREATED -> StepOutcome.to(WorkflowStatus.ANALYZING_JIRA, "Workflow started");
            case ANALYZING_JIRA -> analyzeJira(run);
            case PLANNING -> plan(run);
            case DEVELOPING -> develop(run);
            case RUNNING_LOCAL_TESTS -> runLocalTests(run);
            case PUSHING -> push(run);
            case CREATING_MERGE_REQUEST -> createMergeRequest(run);
            case CODE_REVIEW -> codeReview(run);
            case SECURITY_REVIEW -> securityReview(run);
            case ACCEPTANCE -> acceptance(run);
            case WAITING_HUMAN_APPROVAL, DONE, FAILED, CANCELLED, NEEDS_CLARIFICATION ->
                throw new IllegalStateException("Status " + status + " is not runnable");
        };
    }

    // ------------------------------------------------------------------ steps

    private StepOutcome analyzeJira(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        GitLabProject project = gitLabClient.getProject(workflow.getGitlabProject());
        String baseBranch = project.defaultBranch() == null || project.defaultBranch().isBlank()
                ? gitLabProperties.defaultTargetBranch()
                : project.defaultBranch();
        workflow.setBaseBranch(baseBranch);

        // Load the project before the analyst decides that human input is necessary. This gives the
        // analyst both a repository summary and read-only file tools, so technical scope can be
        // established from the code instead of being turned into a clarification question.
        RepositoryContext context = rulesLoader.loadContext(workflow.getGitlabProject(), baseBranch);
        run.repositoryContext(context);
        run.rules(context.rules());
        BuildProfile buildProfile = BuildProfile.detect(context);
        workflow.setBuildProfile(buildProfile);
        if (buildProfile == BuildProfile.UNSUPPORTED) {
            return StepOutcome.to(
                    WorkflowStatus.FAILED,
                    "Unsupported repository type: expected angular.json or pom.xml/mvnw at the repository root");
        }

        TicketAnalysis analysis;
        if (workflow.isJiraBacked()) {
            JiraIssue issue = jiraClient.getIssue(workflow.getJiraTicket());
            analysis = jiraAnalystAgent.analyze(workflow.getId(), issue, context, workflow.getHumanClarification());
        } else {
            analysis = jiraAnalystAgent.analyzeMessage(
                    workflow.getId(), workflow.getJiraTicket(), workflow.getSourceMessage(), context, workflow.getHumanClarification());
        }
        workflow.setTicketAnalysisJson(codec.write(analysis));

        boolean missingAcceptanceCriteria = analysis.acceptanceCriteria().isEmpty();
        boolean needsClarification = !analysis.ambiguities().isEmpty()
                || (workflow.isJiraBacked() && missingAcceptanceCriteria);
        if (needsClarification) {
            String reason = workflow.isJiraBacked() && missingAcceptanceCriteria
                    ? "The ticket has no acceptance criteria, so nothing can be verified."
                    : "The ticket contains ambiguities that must be resolved by a human.";
            if (workflow.isJiraBacked()) {
                commentJiraSafely(
                        workflow.getJiraTicket(),
                        """
                        The AI development team stopped on this ticket.

                        %s

                        Points to clarify:
                        %s
                        """
                                .formatted(reason, bullets(analysis.ambiguities())));
                transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().needsClarification());
            }
            return StepOutcome.to(WorkflowStatus.NEEDS_CLARIFICATION, reason);
        }

        if (workflow.isJiraBacked()) {
            transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().inProgress());
        }
        return StepOutcome.to(
                WorkflowStatus.PLANNING, analysis.acceptanceCriteria().size() + " acceptance criteria identified");
    }

    private StepOutcome plan(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);

        GitLabProject project = gitLabClient.getProject(workflow.getGitlabProject());
        String baseBranch = project.defaultBranch() == null || project.defaultBranch().isBlank()
                ? gitLabProperties.defaultTargetBranch()
                : project.defaultBranch();
        workflow.setBaseBranch(baseBranch);

        RepositoryContext context = run.repositoryContext();
        if (context == null) {
            context = rulesLoader.loadContext(workflow.getGitlabProject(), baseBranch);
            run.repositoryContext(context);
        }
        run.rules(context.rules());

        TechnicalPlan technicalPlan = architectAgent.plan(workflow.getId(), analysis, context);
        workflow.setTechnicalPlanJson(codec.write(technicalPlan));

        return StepOutcome.to(
                WorkflowStatus.DEVELOPING, technicalPlan.filesToModify().size() + " file(s) planned on " + baseBranch);
    }

    private StepOutcome develop(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        int attempt = workflow.incrementDevelopmentAttempts();
        metrics.developmentAttempt(workflow.getGitlabProject(), attempt);

        if (attempt > workflowProperties.maxDevelopmentAttempts()) {
            return StepOutcome.to(
                    WorkflowStatus.FAILED,
                    "Development gave up after " + workflowProperties.maxDevelopmentAttempts() + " attempts");
        }

        TicketAnalysis analysis = requireAnalysis(workflow);
        TechnicalPlan technicalPlan = codec.readRequired(workflow.getTechnicalPlanJson(), TechnicalPlan.class, "technical plan");

        ensureSandbox(run);
        DevelopmentResult result = developerAgent.implement(
                workflow.getId(),
                run.sandbox(),
                attempt,
                analysis,
                technicalPlan,
                run.rules(),
                workflow.getPendingFeedback());

        workflow.setPendingFeedback(null);
        String detail = "Attempt " + attempt + ": " + result.changedFiles().size() + " file(s) changed";
        return StepOutcome.to(WorkflowStatus.RUNNING_LOCAL_TESTS, detail);
    }

    private StepOutcome runLocalTests(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);
        ensureSandbox(run);

        TestReport report =
                testAgent.runAndAnalyse(workflow.getId(), run.sandbox(), workflow.getDevelopmentAttempts(), analysis, run.rules());
        workflow.setTestReportJson(codec.write(report));
        testResultRepository.save(new TestResultEntity(
                workflow.getId(),
                workflow.getDevelopmentAttempts(),
                report.successful(),
                report.totalTests(),
                report.failedTests(),
                codec.write(report)));

        if (!report.successful()) {
            workflow.setPendingFeedback(report.toFeedback());
            String detail = report.failedTests() == 0
                    ? "Build failed without a test failure, retrying"
                    : report.failedTests() + " test(s) failing, retrying";
            return StepOutcome.to(WorkflowStatus.DEVELOPING, detail);
        }
        return StepOutcome.to(WorkflowStatus.PUSHING, report.totalTests() + " test(s) passing");
    }

    private StepOutcome push(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);
        ensureSandbox(run);

        boolean committed = gitOperations.commitAll(run.sandbox(), reportBuilder.commitMessage(analysis));
        if (!committed && workflow.getCommitSha() == null) {
            return StepOutcome.to(
                    WorkflowStatus.FAILED, "The developer agent produced no change; there is nothing to push");
        }
        if (committed) {
            workflow.setCommitSha(gitOperations.currentCommitSha(run.sandbox()));
        }
        gitOperations.push(run.sandbox(), workflow.getBranch());
        return StepOutcome.to(WorkflowStatus.CREATING_MERGE_REQUEST, "Pushed " + workflow.getBranch());
    }

    private StepOutcome createMergeRequest(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);

        // The sandbox is no longer needed: reviews read the diff from GitLab, not from the container.
        destroySandbox(run);

        if (workflow.getMergeRequestIid() != null) {
            return StepOutcome.to(
                    WorkflowStatus.DONE, "Merge request !" + workflow.getMergeRequestIid() + " updated");
        }

        TechnicalPlan technicalPlan =
                codec.readRequired(workflow.getTechnicalPlanJson(), TechnicalPlan.class, "technical plan");
        TestReport testReport = codec.read(workflow.getTestReportJson(), TestReport.class)
                .orElseGet(() -> new TestReport(true, 0, 0, 0, List.of(), List.of(), ""));

        String description = reportBuilder.initialDescription(workflow, analysis, technicalPlan, testReport);
        MergeRequest mergeRequest = gitLabClient.createMergeRequest(new CreateMergeRequestCommand(
                workflow.getGitlabProject(),
                workflow.getBranch(),
                workflow.getBaseBranch(),
                reportBuilder.title(analysis),
                description,
                List.of(gitLabProperties.mergeRequestLabel()),
                false,
                false));

        workflow.setMergeRequestIid(mergeRequest.iid());
        workflow.setMergeRequestUrl(mergeRequest.webUrl());
        mergeRequestRepository.save(new MergeRequestEntity(
                workflow.getId(),
                workflow.getGitlabProject(),
                mergeRequest.iid(),
                mergeRequest.webUrl(),
                mergeRequest.sourceBranch(),
                mergeRequest.targetBranch(),
                mergeRequest.title()));
        metrics.mergeRequestCreated(workflow.getGitlabProject());

        if (workflow.isJiraBacked()) {
            commentJiraSafely(
                    workflow.getJiraTicket(),
                    "The AI development team opened a merge request: " + mergeRequest.webUrl());
        }

        return StepOutcome.to(WorkflowStatus.DONE, "Merge request !" + mergeRequest.iid() + " created");
    }

    private StepOutcome codeReview(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);
        String diff = currentDiff(workflow);

        CodeReview review = reviewerAgent.review(
                workflow.getId(), workflow.getReviewAttempts() + 1, analysis, diff, loadRules(run));
        workflow.setCodeReviewJson(codec.write(review));
        reviewResultRepository.save(new ReviewResultEntity(
                workflow.getId(),
                ReviewResultEntity.ReviewType.CODE,
                workflow.getReviewAttempts() + 1,
                review.decision().name(),
                codec.write(review)));

        if (!review.approved()) {
            metrics.reviewRejected(workflow.getGitlabProject(), "code");
            return requestChanges(workflow, review.toFeedback(), "Code review requested changes");
        }
        return StepOutcome.to(WorkflowStatus.SECURITY_REVIEW, "Code review approved");
    }

    private StepOutcome securityReview(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);
        String diff = currentDiff(workflow);

        var scannerReports = workflow.getPipelineId() == null
                ? List.<com.company.aidev.gitlab.model.ScannerReport>of()
                : gitLabClient.getSecurityReports(
                        workflow.getGitlabProject(), workflow.getPipelineId(), MAX_SCANNER_REPORT_CHARS);

        SecurityReport report = securityAgent.review(
                workflow.getId(), workflow.getReviewAttempts() + 1, analysis, diff, scannerReports, loadRules(run));
        workflow.setSecurityReportJson(codec.write(report));
        reviewResultRepository.save(new ReviewResultEntity(
                workflow.getId(),
                ReviewResultEntity.ReviewType.SECURITY,
                workflow.getReviewAttempts() + 1,
                report.decision().name(),
                codec.write(report)));

        if (!report.approved()) {
            metrics.reviewRejected(workflow.getGitlabProject(), "security");
            return requestChanges(workflow, report.toFeedback(), "Security review requested changes");
        }
        return StepOutcome.to(WorkflowStatus.ACCEPTANCE, "Security review approved");
    }

    private StepOutcome acceptance(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        TicketAnalysis analysis = requireAnalysis(workflow);
        String diff = currentDiff(workflow);
        TestReport testReport = codec.read(workflow.getTestReportJson(), TestReport.class)
                .orElseGet(() -> new TestReport(true, 0, 0, 0, List.of(), List.of(), ""));

        AcceptanceReport report =
                acceptanceAgent.verify(workflow.getId(), workflow.getReviewAttempts() + 1, analysis, diff, testReport);
        workflow.setAcceptanceReportJson(codec.write(report));
        reviewResultRepository.save(new ReviewResultEntity(
                workflow.getId(),
                ReviewResultEntity.ReviewType.ACCEPTANCE,
                workflow.getReviewAttempts() + 1,
                report.passedCriteria() + "/" + report.totalCriteria(),
                codec.write(report)));

        if (!report.fullyCovered()) {
            metrics.reviewRejected(workflow.getGitlabProject(), "acceptance");
            StepOutcome outcome = requestChanges(workflow, report.toFeedback(), "Acceptance criteria not fully covered");
            if (outcome.nextStatus() == WorkflowStatus.FAILED) {
                // The merge request stays open with the full report: a human can finish the work.
                publishFinalReport(workflow, analysis, testReport);
            }
            return outcome;
        }

        publishFinalReport(workflow, analysis, testReport);
        if (workflow.isJiraBacked()) {
            transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().readyForReview());
        }
        return StepOutcome.to(
                WorkflowStatus.WAITING_HUMAN_APPROVAL,
                report.passedCriteria() + "/" + report.totalCriteria() + " acceptance criteria covered");
    }

    /** Called when a human approved or merged the merge request. */
    public void onHumanApproval(WorkflowEntity workflow, String approver) {
        workflow.setStatus(WorkflowStatus.DONE);
        stateStore.save(workflow);
        metrics.workflowSucceeded(workflow.getGitlabProject());
        if (workflow.isJiraBacked()) {
            commentJiraSafely(
                    workflow.getJiraTicket(),
                    "The merge request " + workflow.getMergeRequestUrl() + " was approved by " + approver + ".");
        }
        log.info("Workflow {} approved by {}", workflow.getId(), approver);
    }

    // ------------------------------------------------------------- internals

    private StepOutcome requestChanges(WorkflowEntity workflow, String feedback, String detail) {
        int attempt = workflow.incrementReviewAttempts();
        if (attempt > workflowProperties.maxReviewAttempts()) {
            return StepOutcome.to(
                    WorkflowStatus.FAILED,
                    detail + " and the review loop was exhausted after " + workflowProperties.maxReviewAttempts()
                            + " rounds");
        }
        workflow.resetDevelopmentAttempts();
        workflow.setPendingFeedback(feedback);
        return StepOutcome.to(WorkflowStatus.DEVELOPING, detail + " (round " + attempt + ")");
    }

    private void publishFinalReport(WorkflowEntity workflow, TicketAnalysis analysis, TestReport testReport) {
        if (workflow.getMergeRequestIid() == null) {
            return;
        }
        CodeReview codeReview = codec.read(workflow.getCodeReviewJson(), CodeReview.class).orElse(null);
        SecurityReport securityReport =
                codec.read(workflow.getSecurityReportJson(), SecurityReport.class).orElse(null);
        AcceptanceReport acceptanceReport =
                codec.read(workflow.getAcceptanceReportJson(), AcceptanceReport.class).orElse(null);

        String report = reportBuilder.finalReport(
                workflow, analysis, testReport, codeReview, securityReport, acceptanceReport);
        try {
            gitLabClient.commentMergeRequest(workflow.getGitlabProject(), workflow.getMergeRequestIid(), report);
        } catch (RuntimeException e) {
            // Losing the report must not lose the work: the merge request already exists.
            log.warn("Unable to publish the final report on merge request !{}: {}", workflow.getMergeRequestIid(), e.toString());
        }
    }

    private String currentDiff(WorkflowEntity workflow) {
        if (workflow.getMergeRequestIid() == null) {
            return "";
        }
        return gitLabClient.getMergeRequestDiff(
                workflow.getGitlabProject(), workflow.getMergeRequestIid(), MAX_DIFF_CHARS);
    }

    private com.company.aidev.domain.RepositoryRules loadRules(WorkflowRun run) {
        if (!run.rules().isEmpty()) {
            return run.rules();
        }
        var rules = rulesLoader.loadRules(run.workflow().getGitlabProject(), run.workflow().getBaseBranch());
        run.rules(rules);
        return rules;
    }

    /** Creates the sandbox and puts the repository in the right state, once per run. */
    private void ensureSandbox(WorkflowRun run) {
        if (run.hasSandbox()) {
            return;
        }
        WorkflowEntity workflow = run.workflow();
        GitLabProject project = gitLabClient.getProject(workflow.getGitlabProject());

        if (workflow.getBranch() == null) {
            workflow.setBranch(branchPolicy.branchFor(workflow.getJiraTicket()));
        }
        branchPolicy.assertAgentBranch(workflow.getBranch());

        BuildProfile buildProfile = resolveBuildProfile(run);
        if (buildProfile == BuildProfile.UNSUPPORTED) {
            throw new IllegalStateException("Cannot create a sandbox for an unsupported repository type");
        }
        Sandbox sandbox = sandboxManager.createSandbox(workflow.getId(), workflow.getJiraTicket(), buildProfile);
        run.sandbox(sandbox);

        gitOperations.cloneRepository(sandbox, project, workflow.getBaseBranch());
        if (gitLabClient.branchExists(workflow.getGitlabProject(), workflow.getBranch())) {
            // The branch already exists: this is a fix round, keep the previous work.
            gitOperations.checkoutExistingBranch(sandbox, workflow.getBranch());
        } else {
            gitOperations.createBranch(sandbox, workflow.getBranch());
        }
        if (run.rules().isEmpty()) {
            run.rules(rulesLoader.loadRules(workflow.getGitlabProject(), workflow.getBaseBranch()));
        }
    }

    private BuildProfile resolveBuildProfile(WorkflowRun run) {
        WorkflowEntity workflow = run.workflow();
        if (workflow.getBuildProfile() != null) {
            return workflow.getBuildProfile();
        }
        RepositoryContext context = run.repositoryContext();
        if (context == null) {
            context = rulesLoader.loadContext(workflow.getGitlabProject(), workflow.getBaseBranch());
            run.repositoryContext(context);
            run.rules(context.rules());
        }
        BuildProfile profile = BuildProfile.detect(context);
        workflow.setBuildProfile(profile);
        return profile;
    }

    private void destroySandbox(WorkflowRun run) {
        if (run.hasSandbox()) {
            sandboxManager.destroySandbox(run.sandbox());
            run.sandbox(null);
        }
    }

    private void applyOutcome(WorkflowRun run, StepOutcome outcome) {
        WorkflowEntity workflow = run.workflow();
        WorkflowStatus previous = workflow.getStatus();
        workflow.setStatus(outcome.nextStatus());

        if (outcome.nextStatus() == WorkflowStatus.FAILED) {
            workflow.setFailureReason(outcome.detail());
            metrics.workflowFailed(workflow.getGitlabProject(), previous.name());
            if (workflow.isJiraBacked()) {
                transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().failed());
                commentJiraSafely(
                        workflow.getJiraTicket(), "The AI development team stopped: " + outcome.detail());
            }
        } else if (outcome.nextStatus() == WorkflowStatus.DONE) {
            metrics.workflowSucceeded(workflow.getGitlabProject());
            if (workflow.isJiraBacked()) {
                transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().readyForReview());
            }
        }
        run.workflow(stateStore.save(workflow));
        log.info("Workflow {} moved {} -> {} ({})", workflow.getId(), previous, outcome.nextStatus(), outcome.detail());
    }

    private void fail(WorkflowRun run, String reason) {
        WorkflowEntity workflow = run.workflow();
        workflow.setStatus(WorkflowStatus.FAILED);
        workflow.setFailureReason(reason);
        metrics.workflowFailed(workflow.getGitlabProject(), "exception");
        try {
            run.workflow(stateStore.save(workflow));
        } catch (RuntimeException e) {
            log.error("Unable to persist the failure of workflow {}", workflow.getId(), e);
        }
        if (workflow.isJiraBacked()) {
            transitionJiraSafely(workflow.getJiraTicket(), jiraProperties.statuses().failed());
            commentJiraSafely(workflow.getJiraTicket(), "The AI development team stopped: " + reason);
        }
    }

    private TicketAnalysis requireAnalysis(WorkflowEntity workflow) {
        return codec.readRequired(workflow.getTicketAnalysisJson(), TicketAnalysis.class, "ticket analysis");
    }

    /** Jira problems must never fail a workflow: the code and the merge request are what matter. */
    private void transitionJiraSafely(String ticket, String status) {
        try {
            jiraClient.transitionTo(ticket, status);
        } catch (RuntimeException e) {
            log.warn("Unable to move Jira issue {} to {}: {}", ticket, status, e.toString());
        }
    }

    private void commentJiraSafely(String ticket, String comment) {
        try {
            jiraClient.addComment(ticket, comment);
        } catch (RuntimeException e) {
            log.warn("Unable to comment Jira issue {}: {}", ticket, e.toString());
        }
    }

    private static String bullets(List<String> items) {
        return items.isEmpty() ? "- (none reported)" : items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    /** Result of one step: where the workflow goes next, and why. */
    record StepOutcome(WorkflowStatus nextStatus, String detail) {
        static StepOutcome to(WorkflowStatus nextStatus, String detail) {
            return new StepOutcome(nextStatus, detail);
        }
    }
}
