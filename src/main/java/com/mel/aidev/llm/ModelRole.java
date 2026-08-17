package com.mel.aidev.llm;

/**
 * Logical model roles. Agents never reference a physical model name: they reference a role, and the
 * role is mapped to a configured model name through configuration ({@code ai.models.*}).
 */
public enum ModelRole {

    /** Long-context reasoning: ticket analysis, planning, acceptance. */
    ANALYSIS,

    /** Code generation and code editing. */
    CODING,

    /** Critical reading: code review, security review. */
    REVIEW,

    /** Cheap and fast: summarisation, classification, formatting. */
    FAST
}
