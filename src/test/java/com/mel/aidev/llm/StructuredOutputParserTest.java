package com.mel.aidev.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mel.aidev.domain.RiskLevel;
import com.mel.aidev.domain.TicketAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parser is what stands between a self-hosted model's habits and the rest of the platform, so
 * the shapes tested here are the ones models actually produce, not the ones they should.
 */
class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser();

    @Test
    @DisplayName("parses a bare JSON object")
    void shouldParseBareJson() {
        String output =
                """
                {"ticketId":"BANK-1245","objective":"Suspend fees","acceptanceCriteria":["AC1"],"riskLevel":"HIGH"}
                """;

        TicketAnalysis analysis = parser.parse(output, TicketAnalysis.class);

        assertThat(analysis.ticketId()).isEqualTo("BANK-1245");
        assertThat(analysis.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(analysis.acceptanceCriteria()).containsExactly("AC1");
    }

    @Test
    @DisplayName("parses JSON wrapped in a markdown fence")
    void shouldParseFencedJson() {
        String output =
                """
                ```json
                {"ticketId":"BANK-1","objective":"x","acceptanceCriteria":[]}
                ```
                """;

        assertThat(parser.parse(output, TicketAnalysis.class).ticketId()).isEqualTo("BANK-1");
    }

    @Test
    @DisplayName("parses JSON surrounded by prose")
    void shouldParseJsonSurroundedByProse() {
        String output =
                """
                Here is my analysis of the ticket:

                {"ticketId":"BANK-2","objective":"y","acceptanceCriteria":["a","b"]}

                Let me know if you need more detail.
                """;

        assertThat(parser.parse(output, TicketAnalysis.class).acceptanceCriteria()).hasSize(2);
    }

    @Test
    @DisplayName("does not stop at a brace inside a string value")
    void shouldIgnoreBracesInsideStrings() {
        String output = "{\"ticketId\":\"BANK-3\",\"objective\":\"handle the } character\",\"acceptanceCriteria\":[]}";

        assertThat(parser.parse(output, TicketAnalysis.class).objective()).isEqualTo("handle the } character");
    }

    @Test
    @DisplayName("does not stop at an escaped quote inside a string value")
    void shouldIgnoreEscapedQuotes() {
        String output = "{\"ticketId\":\"BANK-4\",\"objective\":\"the \\\"fragile\\\" flag\",\"acceptanceCriteria\":[]}";

        assertThat(parser.parse(output, TicketAnalysis.class).objective()).isEqualTo("the \"fragile\" flag");
    }

    @Test
    @DisplayName("ignores unknown fields so a chattier model does not break the workflow")
    void shouldIgnoreUnknownFields() {
        String output = "{\"ticketId\":\"BANK-5\",\"objective\":\"z\",\"confidence\":0.9,\"acceptanceCriteria\":[]}";

        assertThat(parser.parse(output, TicketAnalysis.class).ticketId()).isEqualTo("BANK-5");
    }

    @Test
    @DisplayName("fails when the answer contains no JSON at all")
    void shouldFailWithoutJson() {
        assertThatThrownBy(() -> parser.parse("I cannot answer that.", TicketAnalysis.class))
                .isInstanceOf(LlmOutputParseException.class)
                .hasMessageContaining("No JSON value found");
    }

    @Test
    @DisplayName("fails when the JSON is truncated")
    void shouldFailOnUnbalancedJson() {
        assertThatThrownBy(() -> parser.parse("{\"ticketId\":\"BANK-6\",\"objective\":", TicketAnalysis.class))
                .isInstanceOf(LlmOutputParseException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    @DisplayName("fails on an empty answer")
    void shouldFailOnEmptyOutput() {
        assertThatThrownBy(() -> parser.parse("   ", TicketAnalysis.class))
                .isInstanceOf(LlmOutputParseException.class);
    }
}
