package com.mel.aidev.jira;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Flattens an Atlassian Document Format node into readable text.
 *
 * <p>Jira Cloud returns rich text as ADF, Jira Server returns wiki markup as a plain string. Feeding
 * raw ADF to a model wastes thousands of tokens on structural noise and measurably degrades the
 * analysis, so the conversion happens here once.
 */
final class AtlassianDocumentFormat {

    private AtlassianDocumentFormat() {}

    static String toPlainText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        StringBuilder sb = new StringBuilder();
        append(node, sb, 0);
        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void append(JsonNode node, StringBuilder sb, int listDepth) {
        String type = node.path("type").asText("");
        switch (type) {
            case "text" -> {
                String text = node.path("text").asText("");
                if (isCode(node)) {
                    sb.append('`').append(text).append('`');
                } else {
                    sb.append(text);
                }
            }
            case "hardBreak" -> sb.append('\n');
            case "paragraph" -> {
                appendChildren(node, sb, listDepth);
                sb.append("\n\n");
            }
            case "heading" -> {
                int level = node.path("attrs").path("level").asInt(1);
                sb.append("\n").append("#".repeat(Math.min(level, 6))).append(' ');
                appendChildren(node, sb, listDepth);
                sb.append('\n');
            }
            case "bulletList", "orderedList" -> {
                sb.append('\n');
                appendChildren(node, sb, listDepth + 1);
            }
            case "listItem" -> {
                sb.append("  ".repeat(Math.max(listDepth - 1, 0))).append("- ");
                appendChildren(node, sb, listDepth);
            }
            case "codeBlock" -> {
                String language = node.path("attrs").path("language").asText("");
                sb.append("\n```").append(language).append('\n');
                appendChildren(node, sb, listDepth);
                sb.append("\n```\n");
            }
            case "rule" -> sb.append("\n---\n");
            case "table" -> {
                sb.append('\n');
                appendChildren(node, sb, listDepth);
            }
            case "tableRow" -> {
                appendChildren(node, sb, listDepth);
                sb.append('\n');
            }
            case "tableHeader", "tableCell" -> {
                sb.append("| ");
                appendChildren(node, sb, listDepth);
                sb.append(' ');
            }
            case "mention" -> sb.append('@').append(node.path("attrs").path("text").asText(""));
            case "inlineCard" -> sb.append(node.path("attrs").path("url").asText(""));
            default -> appendChildren(node, sb, listDepth);
        }
    }

    private static void appendChildren(JsonNode node, StringBuilder sb, int listDepth) {
        JsonNode content = node.path("content");
        if (content.isArray()) {
            content.forEach(child -> append(child, sb, listDepth));
        }
    }

    private static boolean isCode(JsonNode textNode) {
        JsonNode marks = textNode.path("marks");
        if (!marks.isArray()) {
            return false;
        }
        for (JsonNode mark : marks) {
            if ("code".equals(mark.path("type").asText())) {
                return true;
            }
        }
        return false;
    }
}
