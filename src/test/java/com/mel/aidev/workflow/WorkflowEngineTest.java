package com.mel.aidev.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mel.aidev.agent.AcceptanceAgent;
import com.mel.aidev.agent.ArchitectAgent;
import com.mel.aidev.agent.DeveloperAgent;
import com.mel.aidev.agent.JiraAnalystAgent;
import com.mel.aidev.agent.ReviewerAgent;
import com.mel.aidev.agent.SecurityAgent;
import com.mel.aidev.agent.TestAgent;
import com.mel.aidev.config.GitLabProperties;
import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.config.SandboxProperties;
import com.mel.aidev.config.WorkflowProperties;
import com.mel.aidev.domain.AcceptanceCriterionResult;
import com.mel.aidev.domain.AcceptanceReport;
import com.mel.aidev.domain.AcceptanceStatus;
import com.mel.aidev.domain.ChangeType;
import com.mel.aidev.domain.CodeReview;
import com.mel.aidev.domain.DevelopmentResult;
import com.mel.aidev.domain.FileChange;
import com.mel.aidev.domain.RepositoryContext;
import com.mel.aidev.domain.RepositoryRules;
import com.mel.aidev.domain.ReviewDecision;
import com.mel.aidev.domain.ReviewFinding;
import com.mel.aidev.domain.RiskLevel;
import com.mel.aidev.domain.SecurityFinding;
import com.mel.aidev.domain.SecurityReport;
import com.mel.aidev.domain.Severity;
import com.mel.aidev.domain.TechnicalPlan;
import com.mel.aidev.domain.TestFailure;
import com.mel.aidev.domain.TestReport;
import com.mel.aidev.domain.TicketAnalysis;
import com.mel.aidev.git.GitOperations;
import com.mel.aidev.gitlab.GitLabClient;
import com.mel.aidev.gitlab.model.GitLabProject;
import com.mel.aidev.gitlab.model.MergeRequest;
import com.mel.aidev.jira.JiraClient;
import com.mel.aidev.jira.model.JiraIssue;
import com.mel.aidev.observability.PlatformMetrics;
import com.mel.aidev.observability.StepLogs;
import com.mel.aidev.persistence.entity.WorkflowEntity;
import com.mel.aidev.persistence.entity.WorkflowStepEntity;
import com.mel.aidev.persistence.entity.WorkflowStepLogEntity;
import com.mel.aidev.persistence.repository.MergeRequestRepository;
import com.mel.aidev.persistence.repository.ReviewResultRepository;
import com.mel.aidev.persistence.repository.WorkflowStepLogRepository;
import com.mel.aidev.project.ProjectConfiguration;
import com.mel.aidev.project.WorkflowLaunchConfiguration;
import com.mel.aidev.persistence.repository.TestResultRepository;
import com.mel.aidev.rules.RepositoryRulesLoader;
import com.mel.aidev.sandbox.Sandbox;
import com.mel.aidev.sandbox.SandboxManager;
import com.mel.aidev.security.BranchPolicy;
import com.mel.aidev.security.SecretRedactor;
import com.mel.aidev.settings.PlatformSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end test of the orchestrator with every external system mocked.
 *
 * <p>This is the test that matters most: it pins down the transitions, the bounded loops and the
 * rule that nothing reaches a human until the tests, the reviews and the acceptance check agree.
 */
class WorkflowEngineTest {

    private static final String TICKET = "BANK-1245";
    private static final String PROJECT = "bank/customer-management";
    private static final UUID PROJECT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private WorkflowStateStore stateStore;
    private JiraClient jiraClient;
    private GitLabClient gitLabClient;
    private SandboxManager sandboxManager;
    private GitOperations gitOperations;
    private RepositoryRulesLoader rulesLoader;
    private JiraAnalystAgent jiraAnalystAgent;
    private ArchitectAgent architectAgent;
    private DeveloperAgent developerAgent;
    private TestAgent testAgent;
    private ReviewerAgent reviewerAgent;
    private SecurityAgent securityAgent;
    private AcceptanceAgent acceptanceAgent;

    private WorkflowEngine engine;
    private WorkflowEntity workflow;
    private WorkflowStepLogRepository stepLogRepository;

    @BeforeEach
    void setUp() {
        stateStore = mock(WorkflowStateStore.class);
        jiraClient = mock(JiraClient.class);
        gitLabClient = mock(GitLabClient.class);
        sandboxManager = mock(SandboxManager.class);
        gitOperations = mock(GitOperations.class);
        rulesLoader = mock(RepositoryRulesLoader.class);
        jiraAnalystAgent = mock(JiraAnalystAgent.class);
        architectAgent = mock(ArchitectAgent.class);
        developerAgent = mock(DeveloperAgent.class);
        testAgent = mock(TestAgent.class);
        reviewerAgent = mock(ReviewerAgent.class);
        securityAgent = mock(SecurityAgent.class);
        acceptanceAgent = mock(AcceptanceAgent.class);

        GitLabProperties gitLabProperties = new GitLabProperties(
                "https://gitlab.company.com",
                "token",
                null,
                null,
                "ai/",
                "main",
                List.of("main", "master"),
                "AI-GENERATED",
                null,
                null,
                null,
                null);
        JiraProperties jiraProperties = new JiraProperties(
                "https://company.atlassian.net", "bot@company.com", "token", "3", null, null, null, null, null, null);
        WorkflowProperties workflowProperties = new WorkflowProperties(2, 2, 2, null, null, null, 1, true, false, null, null);
        SandboxProperties sandboxProperties = new SandboxProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        // The configuration a workflow of this project would be launched with, frozen as the
        // service does before the first step.
        ProjectConfiguration configuration = new ProjectConfiguration(
                PROJECT_ID,
                "Banque",
                PROJECT,
                "BANK",
                null,
                "main",
                "ai/",
                List.of("main", "master"),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        WorkflowLaunchConfiguration launchConfiguration = mock(WorkflowLaunchConfiguration.class);
        when(launchConfiguration.of(any())).thenReturn(configuration);

        workflow = new WorkflowEntity(UUID.randomUUID(), PROJECT_ID, TICKET, PROJECT, "main");
        workflow.setBranch("ai/" + TICKET);

        when(gitLabClient.getProject(PROJECT))
                .thenReturn(new GitLabProject(
                        1L, "customer-management", PROJECT, "main", "https://gitlab/x", "https://gitlab/x.git"));
        when(rulesLoader.loadContext(eq(PROJECT), anyString()))
                .thenReturn(new RepositoryContext(
                        PROJECT, "main", List.of("pom.xml"), "readme", "<project/>", List.of(), RepositoryRules.empty()));

        when(stateStore.claim(any())).thenAnswer(invocation -> Optional.of(workflow));
        when(stateStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateStore.beginStep(any(), any()))
                .thenAnswer(invocation ->
                        new WorkflowStepEntity(invocation.getArgument(0), 1, invocation.getArgument(1)));

        stepLogRepository = mock(WorkflowStepLogRepository.class);
        when(stepLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        engine = new WorkflowEngine(
                stateStore,
                new WorkflowStepLogService(stepLogRepository, new SecretRedactor(), workflowProperties),
                new WorkflowArtifactCodec(new ObjectMapper()),
                workflowProperties,
                gitLabProperties,
                PlatformSettings.builder().jira(jiraProperties).build(),
                sandboxProperties,
                launchConfiguration,
                jiraClient,
                gitLabClient,
                sandboxManager,
                gitOperations,
                rulesLoader,
                new BranchPolicy(gitLabProperties),
                new MergeRequestReportBuilder(jiraProperties),
                new PlatformMetrics(new SimpleMeterRegistry()),
                jiraAnalystAgent,
                architectAgent,
                developerAgent,
                testAgent,
                reviewerAgent,
                securityAgent,
                acceptanceAgent,
                mock(MergeRequestRepository.class),
                mock(TestResultRepository.class),
                mock(ReviewResultRepository.class));
    }

    @Test
    @DisplayName("a complete ticket runs from Jira to a merge request and then completes")
    void shouldRunUntilMergeRequestCreation() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSuccessfulDevelopment();

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DONE);
        assertThat(workflow.getMergeRequestIid()).isEqualTo(42L);
        assertThat(workflow.getBranch()).isEqualTo("ai/BANK-1245");
        assertThat(workflow.getCommitSha()).isEqualTo("abc123");

        verify(gitOperations).push(any(), eq("ai/BANK-1245"));
        verify(jiraClient).transitionTo(TICKET, "AI_READY_FOR_REVIEW");
        verify(jiraClient).addLabel(TICKET, "traitee-par-ia");
        // The sandbox is always destroyed, even on the happy path.
        verify(sandboxManager).destroySandbox(any());
    }

    @Test
    @DisplayName("the claim is refreshed before every step so a long run is never treated as abandoned")
    void shouldHeartbeatTheClaimBetweenSteps() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSuccessfulDevelopment();

        engine.advance(workflow.getId());

        // One heartbeat per step. Without them a run outliving stale-workflow-timeout is re-claimed
        // by another worker, which would create a second sandbox and push the same branch twice.
        verify(stateStore, times(countSteps())).heartbeat(workflow.getId());
    }

    @Test
    @DisplayName("every step stores its own log, including the output of the container")
    void shouldStoreOneLogPerStep() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSuccessfulDevelopment();
        // The developer agent is mocked, so the container output of the run is produced here: what
        // matters is that whatever the sandbox printed during a step ends up in that step's log.
        when(developerAgent.implement(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    StepLogs.current(workflow.getId()).line("[INFO] BUILD SUCCESS");
                    return new DevelopmentResult(true, List.of("Fee.java"), "done", List.of());
                });

        engine.advance(workflow.getId());

        ArgumentCaptor<WorkflowStepLogEntity> captor = ArgumentCaptor.forClass(WorkflowStepLogEntity.class);
        verify(stepLogRepository, times(countSteps())).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(entity -> assertThat(entity.text()).startsWith("=== step"));
        assertThat(captor.getAllValues()).anySatisfy(entity -> assertThat(entity.text()).contains("[INFO] BUILD SUCCESS"));
    }

    @Test
    @DisplayName("a step that throws still stores its log, which is the one worth reading")
    void shouldStoreTheLogOfAFailedStep() {
        givenAnalysableTicket();
        when(rulesLoader.loadContext(eq(PROJECT), anyString())).thenAnswer(invocation -> {
            StepLogs.current(workflow.getId()).line("fatal: repository not found");
            throw new IllegalStateException("GitLab refused the repository");
        });

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        ArgumentCaptor<WorkflowStepLogEntity> captor = ArgumentCaptor.forClass(WorkflowStepLogEntity.class);
        verify(stepLogRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(entity -> assertThat(entity.text()).contains("fatal: repository not found"));
    }

    /** Number of steps the happy path executes, taken from the steps actually begun. */
    private int countSteps() {
        return mockingDetails(stateStore).getInvocations().stream()
                .filter(invocation -> "beginStep".equals(invocation.getMethod().getName()))
                .toList()
                .size();
    }

    @Test
    @DisplayName("a direct request is analysed without reading or updating Jira")
    void shouldRunDirectRequestWithoutJira() {
        workflow.setSourceMessage("Suspend customer fees after a failed payment.");
        givenPlannableRepository();
        givenSuccessfulDevelopment();
        when(jiraAnalystAgent.analyzeMessage(eq(workflow.getId()), eq(TICKET), anyString(), any(), isNull()))
                .thenReturn(new TicketAnalysis(
                        TICKET, "Suspend fees", List.of("Fees are suspended after a failed payment"), List.of(), List.of(),
                        RiskLevel.LOW, "Suspend the affected customer fees."));

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DONE);
        verify(jiraClient, never()).getIssue(anyString());
        verify(jiraClient, never()).addComment(anyString(), anyString());
        verify(jiraClient, never()).transitionTo(anyString(), anyString());
        verify(rulesLoader).loadContext(PROJECT, "main");
        verify(jiraAnalystAgent).analyzeMessage(eq(workflow.getId()), eq(TICKET), anyString(), any(), isNull());
    }

    @Test
    @DisplayName("a direct request without formal acceptance criteria is not blocked")
    void shouldNotBlockDirectRequestWithoutAcceptanceCriteria() {
        workflow.setSourceMessage("Change the public site title to Jean Brun Immobilier.");
        givenPlannableRepository();
        givenSuccessfulDevelopment();
        when(jiraAnalystAgent.analyzeMessage(eq(workflow.getId()), eq(TICKET), anyString(), any(), isNull()))
                .thenReturn(new TicketAnalysis(
                        TICKET, "Change the public site title", List.of(), List.of(), List.of(), RiskLevel.LOW, null));

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DONE);
        verify(architectAgent).plan(any(), any(), any());
        verify(jiraClient, never()).transitionTo(anyString(), anyString());
    }

    @Test
    @DisplayName("a Jira ticket without formal acceptance criteria continues when the request is unambiguous")
    void shouldNotBlockJiraTicketWithoutFormalAcceptanceCriteria() {
        JiraIssue issueWithoutCriteria = new JiraIssue(
                TICKET, "Add a startup greeting", "Print the greeting once when the API starts.", List.of(),
                List.of(), List.of(), "Medium", "READY_FOR_AI", "Task", List.of(), null, null);
        when(jiraClient.getIssue(TICKET)).thenReturn(issueWithoutCriteria);
        givenPlannableRepository();
        givenSuccessfulDevelopment();
        when(jiraAnalystAgent.analyze(any(), any(), any(), isNull()))
                .thenReturn(new TicketAnalysis(
                        TICKET, "Add a startup greeting", List.of("The greeting is printed once when the API starts."),
                        List.of(), List.of(), RiskLevel.LOW, null));

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DONE);
        verify(jiraClient, never()).transitionTo(TICKET, "AI_NEEDS_CLARIFICATION");
        verify(architectAgent).plan(any(), any(), any());
    }

    @Test
    @DisplayName("an ambiguous ticket stops the workflow and asks a human")
    void shouldStopOnAmbiguousTicket() {
        when(jiraClient.getIssue(TICKET)).thenReturn(jiraIssue());
        when(jiraAnalystAgent.analyze(any(), any(), any(), isNull()))
                .thenReturn(new TicketAnalysis(
                        TICKET,
                        "Suspend fees",
                        List.of("AC1"),
                        List.of(),
                        List.of("Which fee types are in scope?"),
                        RiskLevel.HIGH,
                        null));

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION);
        verify(jiraClient).transitionTo(TICKET, "AI_NEEDS_CLARIFICATION");
        verify(jiraClient).addComment(eq(TICKET), anyString());
        // Nothing was built, nothing was pushed.
        verify(architectAgent, never()).plan(any(), any(), any());
        verify(sandboxManager, never()).createSandbox(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("failing tests send the work back to the developer and the loop is bounded")
    void shouldRetryOnFailingTestsThenGiveUp() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSandbox();
        when(developerAgent.implement(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new DevelopmentResult(true, List.of("Fee.java"), "done", List.of()));
        when(testAgent.runAndAnalyse(any(), any(), anyInt(), any(), any()))
                .thenReturn(new TestReport(
                        false,
                        4,
                        1,
                        0,
                        List.of(new TestFailure("FeeTest", "shouldSuspend", "expected SUSPENDED", null)),
                        List.of(),
                        ""));

        engine.advance(workflow.getId());

        // maxDevelopmentAttempts is 2 in this test: two attempts, then the workflow fails.
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(workflow.getFailureReason()).contains("2 attempts");
        verify(developerAgent, times(2)).implement(any(), any(), anyInt(), any(), any(), any(), any());
        verify(gitOperations, never()).push(any(), anyString());
        verify(jiraClient).transitionTo(TICKET, "AI_FAILED");
    }

    @Test
    @DisplayName("the test failures are handed back to the developer as feedback")
    void shouldFeedTestFailuresBackToDeveloper() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSandbox();
        when(developerAgent.implement(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new DevelopmentResult(true, List.of("Fee.java"), "done", List.of()));
        when(testAgent.runAndAnalyse(any(), any(), anyInt(), any(), any()))
                .thenReturn(new TestReport(
                        false,
                        4,
                        1,
                        0,
                        List.of(new TestFailure("FeeTest", "shouldSuspend", "expected SUSPENDED", null)),
                        List.of(),
                        ""))
                .thenReturn(new TestReport(true, 5, 0, 0, List.of(), List.of(), ""));
        givenPushableRepository();

        engine.advance(workflow.getId());

        // First attempt gets no feedback, the second one receives the failure.
        verify(developerAgent).implement(any(), any(), eq(1), any(), any(), any(), eq(null));
        verify(developerAgent)
                .implement(
                        any(),
                        any(),
                        eq(2),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.contains("FeeTest#shouldSuspend"));
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DONE);
    }

    @Test
    @DisplayName("a rejected review starts a fresh development budget instead of opening the gate")
    void shouldReturnToDevelopmentWhenReviewRejects() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSandbox();
        givenSuccessfulDevelopment();
        givenMergeRequest();

        when(reviewerAgent.review(any(), anyInt(), any(), anyString(), any()))
                .thenReturn(new CodeReview(
                        ReviewDecision.REQUEST_CHANGES,
                        List.of(new ReviewFinding(
                                Severity.CRITICAL, "Fee.java", 12, "correctness", "NPE on null customer", "guard it")),
                        "not ready"));

        when(acceptanceAgent.verify(any(), anyInt(), any(), anyString(), any()))
                .thenReturn(new AcceptanceReport(
                        List.of(new AcceptanceCriterionResult(
                                "AC1", AcceptanceStatus.PASS, List.of("FeeTest#shouldSuspend"), null)),
                        "covered"));

        givenPersistedAnalysis();
        workflow.setMergeRequestIid(42L);
        // Simulate an implementation cycle which already consumed the configured two attempts.
        workflow.incrementDevelopmentAttempts();
        workflow.incrementDevelopmentAttempts();
        workflow.setStatus(WorkflowStatus.CODE_REVIEW);
        engine.advance(workflow.getId());

        // The developer receives a fresh implementation budget together with the blocking finding.
        verify(developerAgent)
                .implement(
                        any(),
                        any(),
                        eq(1),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.contains("NPE on null customer"));
    }

    @Test
    @DisplayName("a blocking security finding sends the work back and nothing is pushed")
    void shouldNotPushWhenSecurityReviewRejects() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSuccessfulDevelopment();
        when(securityAgent.review(any(), anyInt(), any(), anyString(), any(), any()))
                .thenReturn(new SecurityReport(
                        ReviewDecision.REQUEST_CHANGES,
                        List.of(new SecurityFinding(
                                Severity.BLOCKER,
                                "secret",
                                "Fee.java",
                                7,
                                "An API token is hardcoded",
                                "read it from configuration",
                                "agent",
                                false)),
                        "not shippable"));

        engine.advance(workflow.getId());

        // The gate is before the push: the remote never sees the branch, and the developer is asked
        // to fix the finding.
        verify(gitOperations, never()).push(any(), anyString());
        verify(gitLabClient, never()).createMergeRequest(any());
        verify(developerAgent, org.mockito.Mockito.atLeastOnce())
                .implement(
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.contains("An API token is hardcoded"));
        // maxReviewAttempts is 2 in this test: the loop is bounded and hands the ticket to a human.
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
    }

    @Test
    @DisplayName("the security review reads the sandbox diff, before the branch exists on the remote")
    void shouldReviewSecurityFromTheSandboxBeforePush() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSuccessfulDevelopment();

        engine.advance(workflow.getId());

        var order = org.mockito.Mockito.inOrder(securityAgent, gitOperations);
        order.verify(securityAgent).review(any(), anyInt(), any(), anyString(), any(), any());
        order.verify(gitOperations).push(any(), eq("ai/" + TICKET));
        verify(gitOperations).diffAgainstBase(any(), eq("main"), anyInt());
        verify(gitLabClient, never()).getMergeRequestDiff(anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("uncovered acceptance criteria stop the workflow before a human is asked to review")
    void shouldNotReachHumanApprovalWithUncoveredCriteria() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSandbox();
        givenSuccessfulDevelopment();
        givenMergeRequest();
        givenApprovingReviews();

        when(acceptanceAgent.verify(any(), anyInt(), any(), anyString(), any()))
                .thenReturn(new AcceptanceReport(
                        List.of(new AcceptanceCriterionResult(
                                "AC1", AcceptanceStatus.FAIL, List.of(), "no test covers it")),
                        "not covered"));

        givenPersistedAnalysis();
        workflow.setMergeRequestIid(42L);
        workflow.setStatus(WorkflowStatus.ACCEPTANCE);
        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isNotEqualTo(WorkflowStatus.WAITING_HUMAN_APPROVAL);
        verify(jiraClient, never()).transitionTo(TICKET, "AI_READY_FOR_REVIEW");
    }

    @Test
    @DisplayName("a fully verified change waits for a human and is never merged automatically")
    void shouldStopAtHumanApproval() {
        givenAnalysableTicket();
        givenPlannableRepository();
        givenSandbox();
        givenSuccessfulDevelopment();
        givenMergeRequest();
        givenApprovingReviews();
        when(acceptanceAgent.verify(any(), anyInt(), any(), anyString(), any()))
                .thenReturn(new AcceptanceReport(
                        List.of(new AcceptanceCriterionResult(
                                "AC1", AcceptanceStatus.PASS, List.of("FeeTest#shouldSuspend"), null)),
                        "covered"));

        givenPersistedAnalysis();
        workflow.setMergeRequestIid(42L);
        workflow.setStatus(WorkflowStatus.CODE_REVIEW);
        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.WAITING_HUMAN_APPROVAL);
        verify(jiraClient).transitionTo(TICKET, "AI_READY_FOR_REVIEW");
        verify(gitLabClient).commentMergeRequest(eq(PROJECT), eq(42L), anyString());
    }

    @Test
    @DisplayName("a step that throws fails the workflow instead of leaving it half-done")
    void shouldFailWorkflowOnStepException() {
        when(jiraClient.getIssue(TICKET)).thenThrow(new IllegalStateException("Jira unreachable"));

        engine.advance(workflow.getId());

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(workflow.getFailureReason()).contains("ANALYZING_JIRA");
    }

    // ------------------------------------------------------------- fixtures

    private void givenAnalysableTicket() {
        when(jiraClient.getIssue(TICKET)).thenReturn(jiraIssue());
        when(jiraAnalystAgent.analyze(any(), any(), any(), isNull()))
                .thenReturn(new TicketAnalysis(
                        TICKET,
                        "Suspend the active fee when the customer becomes fragile",
                        List.of("When customer becomes fragile, the active fee must be suspended"),
                        List.of("fee-engine"),
                        List.of(),
                        RiskLevel.HIGH,
                        "Implement the suspension in FeeSuspensionService."));
    }

    /** Puts the analysis on the workflow row, for tests that start in the middle of the machine. */
    private void givenPersistedAnalysis() {
        workflow.setTicketAnalysisJson(new WorkflowArtifactCodec(new ObjectMapper())
                .write(new TicketAnalysis(
                        TICKET,
                        "Suspend the active fee when the customer becomes fragile",
                        List.of("When customer becomes fragile, the active fee must be suspended"),
                        List.of("fee-engine"),
                        List.of(),
                        RiskLevel.HIGH,
                        null)));
        workflow.setTechnicalPlanJson(new WorkflowArtifactCodec(new ObjectMapper())
                .write(new TechnicalPlan(
                        List.of(new FileChange("src/main/java/Fee.java", ChangeType.MODIFY, "suspension rule")),
                        List.of("Add suspendActiveFees"),
                        List.of("FeeTest#shouldSuspend"),
                        List.of(),
                        "notes")));
    }

    private void givenPlannableRepository() {
        when(gitLabClient.getProject(PROJECT))
                .thenReturn(new GitLabProject(
                        1L, "customer-management", PROJECT, "main", "https://gitlab/x", "https://gitlab/x.git"));
        when(rulesLoader.loadContext(eq(PROJECT), anyString()))
                .thenReturn(new RepositoryContext(
                        PROJECT, "main", List.of("pom.xml"), "readme", "<project/>", List.of(), RepositoryRules.empty()));
        when(rulesLoader.loadRules(eq(PROJECT), anyString())).thenReturn(RepositoryRules.empty());
        when(architectAgent.plan(any(), any(), any()))
                .thenReturn(new TechnicalPlan(
                        List.of(new FileChange("src/main/java/Fee.java", ChangeType.MODIFY, "suspension rule")),
                        List.of("Add suspendActiveFees"),
                        List.of("FeeTest#shouldSuspend"),
                        List.of(),
                        "notes"));
    }

    private void givenSandbox() {
        Sandbox sandbox = new Sandbox(
                UUID.randomUUID(),
                "cid",
                "/workspaces/" + TICKET,
                "/workspaces/" + TICKET + "/repo",
                workflow.getId(),
                TICKET,
                Instant.now());
        // The engine passes the image and the variables it resolved from the project, so the stub has
        // to be on the full signature; the three-argument overload is only a convenience default.
        when(sandboxManager.createSandbox(any(), eq(TICKET), any(), any(), any())).thenReturn(sandbox);
        when(gitLabClient.branchExists(eq(PROJECT), anyString())).thenReturn(false);
    }

    /** Everything needed to reach the remote: the security gate opens, then the branch is pushed. */
    private void givenPushableRepository() {
        givenApprovingSecurityReview();
        when(gitOperations.commitAll(any(), anyString())).thenReturn(true);
        when(gitOperations.currentCommitSha(any())).thenReturn("abc123");
        givenMergeRequest();
    }

    private void givenSuccessfulDevelopment() {
        givenSandbox();
        when(developerAgent.implement(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new DevelopmentResult(true, List.of("Fee.java"), "done", List.of()));
        when(testAgent.runAndAnalyse(any(), any(), anyInt(), any(), any()))
                .thenReturn(new TestReport(true, 12, 0, 0, List.of(), List.of(), ""));
        givenPushableRepository();
    }

    /** The security gate stands between the tests and the push, so the happy path must clear it. */
    private void givenApprovingSecurityReview() {
        when(gitOperations.diffAgainstBase(any(), anyString(), anyInt()))
                .thenReturn("--- a/Fee.java\n+++ b/Fee.java\n+ suspend();");
        when(securityAgent.review(any(), anyInt(), any(), anyString(), any(), any()))
                .thenReturn(new SecurityReport(ReviewDecision.APPROVE, List.of(), "no security issue"));
    }

    private void givenMergeRequest() {
        when(gitLabClient.createMergeRequest(any()))
                .thenReturn(new MergeRequest(
                        100L,
                        42L,
                        "BANK-1245 Suspend fees",
                        "desc",
                        "ai/" + TICKET,
                        "main",
                        "opened",
                        "https://gitlab/mr/42",
                        "sha",
                        false,
                        "mergeable"));
        when(gitLabClient.getMergeRequestDiff(eq(PROJECT), anyLong(), anyInt()))
                .thenReturn("--- a/Fee.java\n+++ b/Fee.java\n+ suspend();");
    }

    private void givenApprovingReviews() {
        when(reviewerAgent.review(any(), anyInt(), any(), anyString(), any()))
                .thenReturn(new CodeReview(ReviewDecision.APPROVE, List.of(), "good"));
        when(securityAgent.review(any(), anyInt(), any(), anyString(), any(), any()))
                .thenReturn(new SecurityReport(ReviewDecision.APPROVE, List.of(), "clean"));
    }

    private static JiraIssue jiraIssue() {
        return new JiraIssue(
                TICKET,
                "Suspend fees for fragile customers",
                "description",
                List.of("When customer becomes fragile, the active fee must be suspended"),
                List.of(),
                List.of("agent-ready"),
                "High",
                "READY_FOR_AI",
                "Story",
                List.of(),
                null,
                null);
    }
}
