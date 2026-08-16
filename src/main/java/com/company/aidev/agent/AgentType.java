package com.company.aidev.agent;

import com.company.aidev.llm.ModelRole;

/**
 * The agents composing the autonomous development team.
 *
 * <p>Each agent declares the model role it uses by default. The mapping can still be overridden per
 * agent in configuration ({@code ai.agents.<agent>.model-role}).
 */
public enum AgentType {

    JIRA_ANALYST(ModelRole.ANALYSIS),
    ARCHITECT(ModelRole.ANALYSIS),
    DEVELOPER(ModelRole.CODING),
    TEST(ModelRole.CODING),
    REVIEWER(ModelRole.REVIEW),
    SECURITY(ModelRole.REVIEW),
    ACCEPTANCE(ModelRole.ANALYSIS);

    private final ModelRole defaultModelRole;

    AgentType(ModelRole defaultModelRole) {
        this.defaultModelRole = defaultModelRole;
    }

    public ModelRole defaultModelRole() {
        return defaultModelRole;
    }
}
