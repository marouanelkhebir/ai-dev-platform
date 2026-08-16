package com.company.aidev.settings;

/** Sections of the settings screen, in display order. */
public enum SettingsGroup {
    PLATFORM("Plateforme", "Clé d'API de la plateforme et accès à la console."),
    JIRA("Jira", "Connexion au Jira de l'équipe et déclencheurs de ticket."),
    GITLAB("GitLab", "Connexion GitLab, branches autorisées et pipeline."),
    LLM("LLM", "API compatible OpenAI et modèles par rôle."),
    AGENTS("Agents", "Réglages d'échantillonnage de chaque agent."),
    WORKFLOW("Workflow", "Garde-fous de l'orchestrateur."),
    SANDBOX("Sandbox", "Conteneurs jetables dans lesquels tourne l'agent développeur.");

    private final String label;
    private final String description;

    SettingsGroup(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
