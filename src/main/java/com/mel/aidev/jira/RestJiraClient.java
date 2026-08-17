package com.mel.aidev.jira;

import com.mel.aidev.config.JiraProperties;
import com.mel.aidev.config.RestClientConfig;
import com.mel.aidev.jira.model.JiraComment;
import com.mel.aidev.jira.model.JiraIssue;
import com.mel.aidev.jira.model.JiraIssueLink;
import com.mel.aidev.jira.model.JiraTransition;
import com.mel.aidev.settings.PlatformSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** {@link JiraClient} over the Jira REST API, tolerant to both Cloud (v3/ADF) and Server (v2). */
@Component
public class RestJiraClient implements JiraClient {

    private static final Logger log = LoggerFactory.getLogger(RestJiraClient.class);

    /** Matches "AC1: ...", "AC 1 - ...", "- ..." inside an acceptance criteria section. */
    private static final Pattern AC_LINE = Pattern.compile("^\\s*(?:AC\\s*\\d+\\s*[:.)-]|[-*•]|\\d+[.)])\\s*(.+)$");

    private static final Pattern AC_SECTION =
            Pattern.compile("(?im)^#*\\s*(acceptance\\s+criteria|crit[eè]res?\\s+d'acceptation)\\s*:?\\s*$");

    private final PlatformSettings settings;
    private final ObjectMapper objectMapper;

    public RestJiraClient(PlatformSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /**
     * Effective Jira settings, read at the point of use.
     *
     * <p>Never cached in a field: the URL, the technical account and the token can all change from
     * the settings screen while the platform is running.
     */
    private JiraProperties properties() {
        return settings.jira();
    }

    private RestClient restClient() {
        try {
            return RestClientConfig.jiraRestClient(properties());
        } catch (IllegalStateException e) {
            throw new JiraException(e.getMessage(), e);
        }
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public JiraIssue getIssue(String issueKey) {
        String fields = String.join(
                ",",
                concat(
                        List.of(
                                "summary",
                                "description",
                                "labels",
                                "priority",
                                "status",
                                "issuetype",
                                "issuelinks",
                                "assignee",
                                "reporter",
                                "comment"),
                        properties().acceptanceCriteriaFields()));

        JsonNode root = get("/issue/{key}?fields={fields}", issueKey, fields);
        return mapIssue(root);
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public boolean projectExists(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return false;
        }
        try {
            restClient().get().uri("/project/{key}", projectKey).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new JiraException(
                    "Unable to read Jira project " + projectKey + " (HTTP " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public void addComment(String issueKey, String comment) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (properties().usesAtlassianDocumentFormat()) {
            payload.set("body", adfParagraphs(comment));
        } else {
            payload.put("body", comment);
        }
        try {
            restClient()
                    .post()
                    .uri("/issue/{key}/comment", issueKey)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Comment added to Jira issue {}", issueKey);
        } catch (RestClientResponseException e) {
            throw new JiraException(
                    "Unable to comment Jira issue " + issueKey + " (HTTP " + e.getStatusCode().value() + ")", e);
        }
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public void addLabel(String issueKey, String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("update").putArray("labels").addObject().put("add", label);
        try {
            restClient()
                    .put()
                    .uri("/issue/{key}", issueKey)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Label '{}' added to Jira issue {}", label, issueKey);
        } catch (RestClientResponseException e) {
            throw new JiraException(
                    "Unable to add label '" + label + "' to Jira issue " + issueKey + " (HTTP "
                            + e.getStatusCode().value() + ")",
                    e);
        }
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public List<JiraTransition> getTransitions(String issueKey) {
        JsonNode root = get("/issue/{key}/transitions", issueKey);
        List<JiraTransition> transitions = new ArrayList<>();
        for (JsonNode node : root.path("transitions")) {
            transitions.add(new JiraTransition(
                    node.path("id").asText(),
                    node.path("name").asText(),
                    node.path("to").path("name").asText()));
        }
        return transitions;
    }

    @Override
    @Retry(name = "jira")
    @CircuitBreaker(name = "jira")
    public boolean transitionTo(String issueKey, String targetStatusName) {
        if (targetStatusName == null || targetStatusName.isBlank()) {
            return false;
        }
        List<JiraTransition> transitions = getTransitions(issueKey);
        JiraTransition match = transitions.stream()
                .filter(t -> equalsIgnoreCase(t.targetStatusName(), targetStatusName)
                        || equalsIgnoreCase(t.name(), targetStatusName))
                .findFirst()
                .orElseGet(() -> semanticFallback(transitions, targetStatusName));

        if (match == null) {
            // Not an exception: a Jira project may simply not define the AI statuses yet, and that
            // must not fail the whole workflow.
            log.warn(
                    "No Jira transition to '{}' available on {} (available: {})",
                    targetStatusName,
                    issueKey,
                    transitions.stream().map(JiraTransition::targetStatusName).toList());
            return false;
        }
        if (!equalsIgnoreCase(match.targetStatusName(), targetStatusName)
                && !equalsIgnoreCase(match.name(), targetStatusName)) {
            log.info(
                    "Jira transition '{}' is not available on {}; using available transition '{}' to status '{}' instead",
                    targetStatusName,
                    issueKey,
                    match.name(),
                    match.targetStatusName());
        }

        ObjectNode transition = objectMapper.createObjectNode();
        transition.putObject("transition").put("id", match.id());
        try {
            restClient()
                    .post()
                    .uri("/issue/{key}/transitions", issueKey)
                    .body(transition)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Jira issue {} transitioned to {}", issueKey, match.targetStatusName());
            return true;
        } catch (RestClientResponseException e) {
            throw new JiraException(
                    "Unable to transition Jira issue " + issueKey + " to " + targetStatusName + " (HTTP "
                            + e.getStatusCode().value() + ")",
                    e);
        }
    }

    private JsonNode get(String uriTemplate, Object... variables) {
        try {
            JsonNode body = restClient().get().uri(uriTemplate, variables).retrieve().body(JsonNode.class);
            if (body == null) {
                throw new JiraException("Empty Jira response for " + uriTemplate);
            }
            return body;
        } catch (RestClientResponseException e) {
            throw new JiraException(
                    "Jira call failed " + uriTemplate + " (HTTP " + e.getStatusCode().value() + ")", e);
        }
    }

    private JiraIssue mapIssue(JsonNode root) {
        JsonNode fields = root.path("fields");
        String description = AtlassianDocumentFormat.toPlainText(fields.path("description"));

        List<String> acceptanceCriteria = extractAcceptanceCriteria(fields, description);

        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(node -> labels.add(node.asText()));

        List<JiraComment> comments = new ArrayList<>();
        for (JsonNode node : fields.path("comment").path("comments")) {
            comments.add(new JiraComment(
                    node.path("id").asText(),
                    node.path("author").path("displayName").asText("unknown"),
                    AtlassianDocumentFormat.toPlainText(node.path("body")),
                    parseInstant(node.path("created").asText(null))));
        }

        List<JiraIssueLink> links = new ArrayList<>();
        for (JsonNode node : fields.path("issuelinks")) {
            String type = node.path("type").path("name").asText("relates to");
            if (node.has("inwardIssue")) {
                links.add(toLink(type, "inward", node.path("inwardIssue")));
            }
            if (node.has("outwardIssue")) {
                links.add(toLink(type, "outward", node.path("outwardIssue")));
            }
        }

        return new JiraIssue(
                root.path("key").asText(),
                fields.path("summary").asText(""),
                description,
                acceptanceCriteria,
                comments,
                labels,
                fields.path("priority").path("name").asText("Unknown"),
                fields.path("status").path("name").asText("Unknown"),
                fields.path("issuetype").path("name").asText("Unknown"),
                links,
                fields.path("assignee").path("displayName").asText(null),
                fields.path("reporter").path("displayName").asText(null));
    }

    private static JiraIssueLink toLink(String type, String direction, JsonNode issue) {
        return new JiraIssueLink(
                type,
                direction,
                issue.path("key").asText(),
                issue.path("fields").path("summary").asText(""),
                issue.path("fields").path("status").path("name").asText(""));
    }

    /**
     * Reads the acceptance criteria from the configured custom fields, and falls back to parsing an
     * "Acceptance criteria" section of the description. Teams rarely fill the custom field
     * consistently, and a missing criterion silently disables the acceptance agent.
     */
    List<String> extractAcceptanceCriteria(JsonNode fields, String description) {
        Set<String> criteria = new LinkedHashSet<>();
        for (String fieldName : properties().acceptanceCriteriaFields()) {
            JsonNode node = fields.path(fieldName);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isArray()) {
                node.forEach(item -> addCriterion(criteria, AtlassianDocumentFormat.toPlainText(item)));
            } else {
                splitCriteria(AtlassianDocumentFormat.toPlainText(node)).forEach(c -> addCriterion(criteria, c));
            }
        }
        if (criteria.isEmpty() && description != null) {
            criteria.addAll(parseFromDescription(description));
        }
        return List.copyOf(criteria);
    }

    private static List<String> parseFromDescription(String description) {
        Matcher sectionMatcher = AC_SECTION.matcher(description);
        if (!sectionMatcher.find()) {
            return List.of();
        }
        String tail = description.substring(sectionMatcher.end());
        List<String> criteria = new ArrayList<>();
        for (String line : tail.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                break; // next section
            }
            Matcher matcher = AC_LINE.matcher(trimmed);
            if (matcher.matches()) {
                criteria.add(matcher.group(1).trim());
            } else if (!criteria.isEmpty()) {
                // continuation of the previous criterion (Given/When/Then style)
                criteria.set(criteria.size() - 1, criteria.get(criteria.size() - 1) + " " + trimmed);
            }
        }
        return criteria;
    }

    private static List<String> splitCriteria(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher matcher = AC_LINE.matcher(trimmed);
            result.add(matcher.matches() ? matcher.group(1).trim() : trimmed);
        }
        return result;
    }

    private static void addCriterion(Set<String> criteria, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            criteria.add(candidate.trim());
        }
    }

    private ArrayNode adfContent(String text) {
        ArrayNode content = objectMapper.createArrayNode();
        for (String paragraph : text.split("\\R")) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "paragraph");
            ArrayNode inner = node.putArray("content");
            if (!paragraph.isEmpty()) {
                inner.addObject().put("type", "text").put("text", paragraph);
            }
            content.add(node);
        }
        return content;
    }

    private ObjectNode adfParagraphs(String text) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        doc.set("content", adfContent(text == null ? "" : text));
        return doc;
    }

    /** Jira serialises timestamps as {@code 2026-01-15T10:30:00.000+0000}, which is not ISO-8601. */
    private static final DateTimeFormatter JIRA_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, JIRA_TIMESTAMP).toInstant();
            } catch (DateTimeParseException e) {
                log.debug("Unparseable Jira timestamp: {}", value);
                return null;
            }
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    /**
     * Maps the platform's generic lifecycle to statuses that the current Jira workflow actually
     * exposes. A fallback is intentionally limited to unambiguous states: review and development.
     * Failures and clarification requests must never be disguised as a normal Jira status.
     */
    private static JiraTransition semanticFallback(List<JiraTransition> transitions, String requestedStatus) {
        String requested = normalizedStatus(requestedStatus);
        if (requested.contains("review") || requested.contains("revue")) {
            return transitions.stream()
                    .filter(transition -> containsAny(transition.targetStatusName(), "review", "revue"))
                    .findFirst()
                    .orElse(null);
        }
        if (requested.contains("in progress") || requested.contains("en cours")) {
            return transitions.stream()
                    .filter(transition -> {
                        String target = normalizedStatus(transition.targetStatusName());
                        return (target.equals("en cours") || target.equals("in progress"))
                                || (containsAny(target, "en cours", "in progress")
                                        && !containsAny(target, "review", "revue"));
                    })
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static boolean containsAny(String value, String... candidates) {
        String normalized = normalizedStatus(value);
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedStatus(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('_', ' ')
                .replace('-', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
