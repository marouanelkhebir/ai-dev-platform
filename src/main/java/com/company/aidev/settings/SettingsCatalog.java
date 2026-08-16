package com.company.aidev.settings;

import com.company.aidev.agent.AgentType;
import com.company.aidev.llm.ModelRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The list of settings the administration screen is allowed to change.
 *
 * <p>An allowlist rather than "anything that looks like a Spring property": an override is applied
 * with the highest precedence over the whole environment, so a free-form key would let the screen
 * rewrite the datasource URL or the logging configuration of a running platform.
 *
 * <p>Connection details of the database are deliberately absent for the same reason — they are what
 * the settings themselves are stored in, and they belong to the deployment.
 */
public final class SettingsCatalog {

    private static final Map<String, SettingDefinition> DEFINITIONS = index(build());

    private SettingsCatalog() {}

    public static List<SettingDefinition> all() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static List<SettingDefinition> of(SettingsGroup group) {
        return DEFINITIONS.values().stream()
                .filter(definition -> definition.group() == group)
                .toList();
    }

    public static Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(DEFINITIONS.get(key == null ? null : key.trim()));
    }

    public static boolean isEditable(String key) {
        return find(key).isPresent();
    }

    /** Keys whose value must never leave the process. */
    public static boolean isSecret(String key) {
        return find(key).map(SettingDefinition::isSecret).orElse(false);
    }

    private static Map<String, SettingDefinition> index(List<SettingDefinition> definitions) {
        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        for (SettingDefinition definition : definitions) {
            byKey.put(definition.key(), definition);
        }
        return byKey;
    }

    private static List<SettingDefinition> build() {
        List<SettingDefinition> definitions = new ArrayList<>();

        // ------------------------------------------------------------------ platform
        definitions.add(SettingDefinition.of(
                "platform.api-key",
                SettingsGroup.PLATFORM,
                "Clé d'API",
                SettingType.SECRET,
                "Attendue dans l'en-tête X-Api-Key de tous les appels /api/**, console comprise."
                        + " La changer déconnecte les sessions ouvertes."));

        // ---------------------------------------------------------------------- Jira
        definitions.add(SettingDefinition.of(
                "jira.base-url",
                SettingsGroup.JIRA,
                "URL Jira",
                SettingType.URL,
                "Racine de l'instance, par exemple https://company.atlassian.net."));
        definitions.add(SettingDefinition.of(
                "jira.email",
                SettingsGroup.JIRA,
                "Compte technique",
                SettingType.TEXT,
                "Adresse du compte propriétaire du jeton d'API."));
        definitions.add(SettingDefinition.of(
                "jira.api-token",
                SettingsGroup.JIRA,
                "Jeton d'API",
                SettingType.SECRET,
                "Jeton Atlassian du compte technique."));
        definitions.add(SettingDefinition.options(
                "jira.api-version",
                SettingsGroup.JIRA,
                "Version d'API",
                "3 pour Jira Cloud (format ADF), 2 pour Jira Server / Data Center.",
                List.of("3", "2")));
        definitions.add(SettingDefinition.of(
                "jira.trigger.label",
                SettingsGroup.JIRA,
                "Label déclencheur",
                SettingType.TEXT,
                "Label qui rend un ticket éligible à l'équipe d'agents."));
        definitions.add(SettingDefinition.of(
                "jira.trigger.status",
                SettingsGroup.JIRA,
                "Statut déclencheur",
                SettingType.TEXT,
                "Statut Jira qui rend un ticket éligible."));
        definitions.add(SettingDefinition.of(
                "jira.statuses.in-progress",
                SettingsGroup.JIRA,
                "Statut « en cours »",
                SettingType.TEXT,
                "Statut poussé sur le ticket quand le workflow démarre."));
        definitions.add(SettingDefinition.of(
                "jira.statuses.needs-clarification",
                SettingsGroup.JIRA,
                "Statut « à clarifier »",
                SettingType.TEXT,
                "Statut poussé quand l'analyse rend la main à un humain."));
        definitions.add(SettingDefinition.of(
                "jira.statuses.ready-for-review",
                SettingsGroup.JIRA,
                "Statut « prêt pour revue »",
                SettingType.TEXT,
                "Statut poussé quand la merge request est ouverte."));
        definitions.add(SettingDefinition.of(
                "jira.statuses.failed",
                SettingsGroup.JIRA,
                "Statut « échec »",
                SettingType.TEXT,
                "Statut poussé quand le workflow abandonne."));
        definitions.add(SettingDefinition.of(
                "jira.acceptance-criteria-fields",
                SettingsGroup.JIRA,
                "Champs critères d'acceptation",
                SettingType.LIST,
                "Champs personnalisés à lire, séparés par des virgules. La description sert de repli."));
        definitions.add(SettingDefinition.of(
                "jira.webhook.secret",
                SettingsGroup.JIRA,
                "Secret webhook",
                SettingType.SECRET,
                "Valeur attendue dans l'en-tête X-Webhook-Token des webhooks Jira."));
        definitions.add(SettingDefinition.of(
                "jira.connect-timeout",
                SettingsGroup.JIRA,
                "Timeout de connexion",
                SettingType.DURATION,
                "Durée ISO-8601, par exemple PT5S."));
        definitions.add(SettingDefinition.of(
                "jira.read-timeout",
                SettingsGroup.JIRA,
                "Timeout de lecture",
                SettingType.DURATION,
                "Durée ISO-8601, par exemple PT30S."));

        // -------------------------------------------------------------------- GitLab
        definitions.add(SettingDefinition.of(
                "gitlab.base-url",
                SettingsGroup.GITLAB,
                "URL GitLab",
                SettingType.URL,
                "Racine de l'instance, par exemple https://gitlab.company.com."));
        definitions.add(SettingDefinition.of(
                "gitlab.api-token",
                SettingsGroup.GITLAB,
                "Jeton d'API",
                SettingType.SECRET,
                "Jeton du compte technique, sans droit mainteneur sur les branches protégées."));
        definitions.add(SettingDefinition.of(
                "gitlab.branch-prefix",
                SettingsGroup.GITLAB,
                "Préfixe de branche",
                SettingType.TEXT,
                "Les agents n'écrivent que sur des branches commençant par ce préfixe."));
        definitions.add(SettingDefinition.of(
                "gitlab.default-target-branch",
                SettingsGroup.GITLAB,
                "Branche cible par défaut",
                SettingType.TEXT,
                "Branche visée par les merge requests quand le projet n'en déclare pas."));
        definitions.add(SettingDefinition.of(
                "gitlab.protected-branches",
                SettingsGroup.GITLAB,
                "Branches protégées",
                SettingType.LIST,
                "Branches interdites en écriture, séparées par des virgules. Second verrou après GitLab."));
        definitions.add(SettingDefinition.of(
                "gitlab.merge-request-label",
                SettingsGroup.GITLAB,
                "Label des merge requests",
                SettingType.TEXT,
                "Label posé sur toute merge request ouverte par la plateforme."));
        definitions.add(SettingDefinition.of(
                "gitlab.bot-name",
                SettingsGroup.GITLAB,
                "Nom du bot",
                SettingType.TEXT,
                "Auteur des commits produits par les agents."));
        definitions.add(SettingDefinition.of(
                "gitlab.bot-email",
                SettingsGroup.GITLAB,
                "Email du bot",
                SettingType.TEXT,
                "Email des commits produits par les agents."));
        definitions.add(SettingDefinition.of(
                "gitlab.webhook.secret",
                SettingsGroup.GITLAB,
                "Secret webhook",
                SettingType.SECRET,
                "Valeur attendue dans l'en-tête X-Gitlab-Token."));
        definitions.add(SettingDefinition.of(
                "gitlab.connect-timeout",
                SettingsGroup.GITLAB,
                "Timeout de connexion",
                SettingType.DURATION,
                "Durée ISO-8601, par exemple PT5S."));
        definitions.add(SettingDefinition.of(
                "gitlab.read-timeout",
                SettingsGroup.GITLAB,
                "Timeout de lecture",
                SettingType.DURATION,
                "Durée ISO-8601, par exemple PT30S."));
        definitions.add(SettingDefinition.of(
                "gitlab.pipeline.timeout",
                SettingsGroup.GITLAB,
                "Timeout pipeline",
                SettingType.DURATION,
                "Au-delà, le workflow arrête d'attendre la CI."));
        definitions.add(SettingDefinition.of(
                "gitlab.pipeline.poll-interval",
                SettingsGroup.GITLAB,
                "Intervalle de scrutation pipeline",
                SettingType.DURATION,
                "Filet de sécurité quand le webhook de pipeline n'arrive pas."));

        // ----------------------------------------------------------------------- LLM
        definitions.add(SettingDefinition.of(
                "ai.openai.base-url",
                SettingsGroup.LLM,
                "URL API compatible OpenAI",
                SettingType.URL,
                "URL de base de l'API, par exemple https://api.openai.com/v1."));
        definitions.add(SettingDefinition.of(
                "ai.openai.api-key",
                SettingsGroup.LLM,
                "Jeton d'API",
                SettingType.SECRET,
                "Jeton Bearer utilisé pour appeler l'API compatible OpenAI."));
        definitions.add(SettingDefinition.of(
                "ai.openai.timeout",
                SettingsGroup.LLM,
                "Timeout d'appel",
                SettingType.DURATION,
                "Un appel d'agent est long : PT5M est un ordre de grandeur réaliste."));
        definitions.add(SettingDefinition.of(
                "ai.openai.max-retries",
                SettingsGroup.LLM,
                "Tentatives",
                SettingType.INTEGER,
                "Nombre de reprises sur erreur de la passerelle."));
        definitions.add(SettingDefinition.of(
                "ai.openai.log-requests",
                SettingsGroup.LLM,
                "Journaliser les requêtes",
                SettingType.BOOLEAN,
                "À n'activer que pour un diagnostic : les prompts partent dans les logs."));
        definitions.add(SettingDefinition.of(
                "ai.openai.log-responses",
                SettingsGroup.LLM,
                "Journaliser les réponses",
                SettingType.BOOLEAN,
                "Même réserve : volumineux et bavard."));
        for (ModelRole role : ModelRole.values()) {
            definitions.add(SettingDefinition.of(
                    "ai.models." + role.name().toLowerCase(Locale.ROOT),
                    SettingsGroup.LLM,
                    "Modèle « " + role.name().toLowerCase(Locale.ROOT) + " »",
                    SettingType.TEXT,
                    "Nom du modèle utilisé pour le rôle " + role.name() + "."));
        }

        // -------------------------------------------------------------------- agents
        List<String> roles = List.of("ANALYSIS", "CODING", "REVIEW", "FAST");
        for (AgentType agent : AgentType.values()) {
            String prefix = "ai.agents." + agent.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".";
            String label = agent.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            definitions.add(SettingDefinition.options(
                    prefix + "model-role",
                    SettingsGroup.AGENTS,
                    label + " — rôle de modèle",
                    "Rôle utilisé par l'agent " + label + " (défaut : " + agent.defaultModelRole() + ").",
                    roles));
            definitions.add(SettingDefinition.of(
                    prefix + "temperature",
                    SettingsGroup.AGENTS,
                    label + " — température",
                    SettingType.DECIMAL,
                    "0 pour un comportement déterministe."));
            definitions.add(SettingDefinition.of(
                    prefix + "max-tokens",
                    SettingsGroup.AGENTS,
                    label + " — tokens max",
                    SettingType.INTEGER,
                    "Plafond de tokens générés par appel."));
            definitions.add(SettingDefinition.of(
                    prefix + "max-tool-calls",
                    SettingsGroup.AGENTS,
                    label + " — appels d'outils max",
                    SettingType.INTEGER,
                    "Borne la boucle outil de l'agent."));
        }

        // ------------------------------------------------------------------ workflow
        definitions.add(SettingDefinition.of(
                "workflow.max-development-attempts",
                SettingsGroup.WORKFLOW,
                "Tentatives de développement",
                SettingType.INTEGER,
                "Au-delà, le ticket repart vers un humain."));
        definitions.add(SettingDefinition.of(
                "workflow.max-pipeline-attempts",
                SettingsGroup.WORKFLOW,
                "Tentatives de pipeline",
                SettingType.INTEGER,
                "Nombre de corrections tentées après un échec CI."));
        definitions.add(SettingDefinition.of(
                "workflow.max-review-attempts",
                SettingsGroup.WORKFLOW,
                "Tentatives de revue",
                SettingType.INTEGER,
                "Nombre d'allers-retours de revue avant abandon."));
        definitions.add(SettingDefinition.of(
                "workflow.step-timeout",
                SettingsGroup.WORKFLOW,
                "Timeout d'étape",
                SettingType.DURATION,
                "Durée maximale d'une étape du workflow."));
        definitions.add(SettingDefinition.of(
                "workflow.human-approval-timeout",
                SettingsGroup.WORKFLOW,
                "Attente de validation humaine",
                SettingType.DURATION,
                "Délai laissé au relecteur humain, par exemple P7D."));
        definitions.add(SettingDefinition.of(
                "workflow.stale-workflow-timeout",
                SettingsGroup.WORKFLOW,
                "Workflow considéré abandonné",
                SettingType.DURATION,
                "Un workflow réclamé depuis plus longtemps est repris par la boucle de reprise."));
        definitions.add(SettingDefinition.of(
                "workflow.auto-start-from-jira-webhook",
                SettingsGroup.WORKFLOW,
                "Démarrage auto depuis Jira",
                SettingType.BOOLEAN,
                "Quand c'est faux, seul un appel POST /api/workflows démarre un ticket."));
        definitions.add(SettingDefinition.restartRequired(
                "workflow.executor-pool-size",
                SettingsGroup.WORKFLOW,
                "Workflows en parallèle",
                SettingType.INTEGER,
                "Taille du pool d'exécution. Le pool est créé au démarrage : redémarrage nécessaire."));

        // ------------------------------------------------------------------- sandbox
        definitions.add(SettingDefinition.of(
                "sandbox.image",
                SettingsGroup.SANDBOX,
                "Image du bac à sable",
                SettingType.TEXT,
                "Image Docker dans laquelle tournent build et tests."));
        definitions.add(SettingDefinition.restartRequired(
                "sandbox.docker-host",
                SettingsGroup.SANDBOX,
                "Hôte Docker",
                SettingType.TEXT,
                "Socket ou URL du démon Docker. Le client est créé au démarrage : redémarrage nécessaire."));
        definitions.add(SettingDefinition.of(
                "sandbox.memory-limit-bytes",
                SettingsGroup.SANDBOX,
                "Mémoire par conteneur (octets)",
                SettingType.INTEGER,
                "Par exemple 4294967296 pour 4 Gio."));
        definitions.add(SettingDefinition.of(
                "sandbox.cpu-quota",
                SettingsGroup.SANDBOX,
                "Quota CPU",
                SettingType.INTEGER,
                "100000 = 1 CPU, sur la période Docker par défaut de 100 ms."));
        definitions.add(SettingDefinition.of(
                "sandbox.command-timeout",
                SettingsGroup.SANDBOX,
                "Timeout de commande",
                SettingType.DURATION,
                "Durée maximale d'une commande dans le conteneur, par exemple PT20M."));
        definitions.add(SettingDefinition.of(
                "sandbox.max-lifetime",
                SettingsGroup.SANDBOX,
                "Durée de vie maximale",
                SettingType.DURATION,
                "Au-delà, le conteneur est détruit par le nettoyeur."));
        definitions.add(SettingDefinition.of(
                "sandbox.network-enabled",
                SettingsGroup.SANDBOX,
                "Réseau activé",
                SettingType.BOOLEAN,
                "Nécessaire pour git et Maven Central."));
        definitions.add(SettingDefinition.of(
                "sandbox.allowed-executables",
                SettingsGroup.SANDBOX,
                "Exécutables autorisés",
                SettingType.LIST,
                "Liste blanche des binaires appelables par un agent, séparés par des virgules."));

        return definitions;
    }
}
