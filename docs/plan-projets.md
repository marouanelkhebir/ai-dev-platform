# Plan — domaine `Project`

Objectif : un workflow appartient obligatoirement à un projet, et il hérite automatiquement de son
dépôt GitLab, de son projet Jira, de son image Docker et de sa configuration d'exécution.

Ce document reprend le plan initial et y intègre quatre ajouts : **configuration par projet**,
**tableau de bord projet**, **clonage de projet**, **archivage et rétention**.

---

## 0. Ce que le code impose aujourd'hui

Points relevés dans la base actuelle, qui contraignent le plan :

| Constat | Fichier | Conséquence |
| --- | --- | --- |
| `workflow.gitlab_project` est un `varchar` libre, fourni par l'appelant | [V1__initial_schema.sql:11](../src/main/resources/db/migration/V1__initial_schema.sql) | c'est exactement ce que `project_id` remplace |
| L'image Docker vient de `SandboxProperties.imageFor(BuildProfile)` — config globale, trois profils en dur | [SandboxProperties.java:54](../src/main/java/com/company/aidev/config/SandboxProperties.java) | l'override projet s'insère ici, pas dans `DockerSandboxManager` |
| Le préfixe de branche et les branches protégées sont globaux | [BranchPolicy.java](../src/main/java/com/company/aidev/security/BranchPolicy.java) | `BranchPolicy` doit devenir paramétrable par projet, sans perdre son rôle de « second verrou » |
| `CommandGuard` valide l'exécutable contre `sandbox.allowed-executables` global | [CommandGuard.java:50](../src/main/java/com/company/aidev/sandbox/CommandGuard.java) | les commandes de test par projet doivent **passer par** ce garde, jamais le contourner |
| `SettingsCatalog` est une liste blanche de propriétés Spring appliquées avec la précédence la plus haute | [SettingsCatalog.java:22](../src/main/java/com/company/aidev/settings/SettingsCatalog.java) | la config projet est de la **donnée**, pas un override Spring : ne pas réutiliser ce mécanisme |
| `llm_execution.workflow_id` n'a **pas** de clé étrangère ni de cascade | [V1__initial_schema.sql:100](../src/main/resources/db/migration/V1__initial_schema.sql) | supprimer un workflow laisse ces lignes orphelines — à traiter explicitement (§7) |
| L'index unique partiel `ux_workflow_active_ticket` est global au ticket | [V1__initial_schema.sql:46](../src/main/resources/db/migration/V1__initial_schema.sql) | décision à prendre : rester global ou devenir `(project_id, jira_ticket)` |
| Le webhook Jira résout le dépôt via un label `gitlab-project:<x>` sur le ticket | [JiraWebhookController.java:80](../src/main/java/com/company/aidev/jira/JiraWebhookController.java) | avec les projets, la résolution se fait par la clé Jira — le label devient un repli |
| `workflow.updated_at` est touché à chaque transition, il n'y a pas de `finished_at` | [WorkflowEntity.java:129](../src/main/java/com/company/aidev/persistence/entity/WorkflowEntity.java) | la durée moyenne du tableau de bord serait fausse sans une colonne dédiée |
| `llm_execution` enregistre les tokens mais aucun coût | [LlmExecutionEntity.java](../src/main/java/com/company/aidev/persistence/entity/LlmExecutionEntity.java) | le coût doit être calculé et figé à l'enregistrement (§5) |
| Dernière migration : `V7` | `src/main/resources/db/migration/` | les nouvelles migrations partent de `V8` |

---

## 1. Domaine `Project`

### 1.1 Table principale

```sql
create table project (
    id                    uuid primary key,
    name                  varchar(128) not null,
    description           text,
    gitlab_project        varchar(512) not null,   -- chemin ou ID GitLab
    jira_project_key      varchar(32),             -- ex. BANK ; null = projet sans Jira
    docker_image          varchar(512),            -- null = repli sur la config globale par profil
    default_branch        varchar(255),            -- null = branche par défaut du dépôt GitLab
    -- ---------------------------------------------------------------- configuration (§1.2)
    branch_prefix         varchar(64),
    protected_branches    text,                    -- CSV, comme gitlab.protected-branches
    build_command         text,                    -- argv JSON, ex. ["mvn","-B","-ntp","test-compile"]
    test_command          text,
    lint_command          text,
    -- ---------------------------------------------------------------- rétention (§8)
    retention_days        integer,
    -- ----------------------------------------------------------------
    active                boolean      not null default true,
    archived_at           timestamptz,
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null,
    version               bigint       not null default 0
);

create unique index ux_project_name on project (lower(name));
create index ix_project_gitlab on project (gitlab_project);
create index ix_project_jira_key on project (jira_project_key) where jira_project_key is not null;
```

**`gitlab_project` n'est volontairement pas unique.** Deux projets peuvent viser le même dépôt avec
des configurations différentes (branche cible, image, commandes) — c'est précisément ce que rend
utile le clonage (§6). L'unicité porte sur le nom.

### 1.2 Configuration par projet *(ajout)*

Trois formes de configuration, chacune stockée selon sa nature :

**a) Règles de branches et commandes** — colonnes scalaires ci-dessus.

- `branch_prefix` : préfixe des branches IA (défaut `gitlab.branch-prefix`). Validé : non vide,
  se termine par `/`, ne matche aucune branche protégée.
- `protected_branches` : s'**ajoute** à la liste globale, ne la remplace jamais. Le verrou global
  reste un plancher ; un projet peut protéger davantage, jamais moins.
- `default_branch` : branche cible des merge requests, si absent → branche par défaut GitLab, puis
  `gitlab.default-target-branch`.
- `build_command` / `test_command` / `lint_command` : stockés en **tableau JSON d'arguments**, jamais
  en chaîne shell. C'est la seule forme compatible avec `CommandGuard`, qui interdit structurellement
  le shell. Validées à l'enregistrement par `CommandGuard.validate(...)` : une commande refusée au
  moment de la création vaut mieux qu'un workflow qui casse en cours d'exécution.

**b) Variables non sensibles** — table dédiée, injectées dans l'environnement du conteneur :

```sql
create table project_variable (
    id         uuid primary key,
    project_id uuid          not null references project (id) on delete cascade,
    name       varchar(128)  not null,
    value      varchar(2048) not null,
    created_at timestamptz   not null,
    constraint ux_project_variable unique (project_id, name)
);
```

Garde-fou obligatoire : ces valeurs partent en clair dans `HostConfig`/`withEnv` du sandbox et sont
visibles par les agents. Le service **refuse** (400) tout nom correspondant à
`(?i).*(secret|token|password|passwd|credential|api[_-]?key|private[_-]?key).*`, toute valeur
ressemblant à un jeton (préfixes `glpat-`, `ghp_`, `sk-`, chaîne base64 > 40 caractères), et refuse
d'écraser une clé de `sandbox.environment`. Les secrets restent dans `SettingsCatalog`, chiffrés par
`SettingsEncryptor`, hors de portée du conteneur.

**c) Modèles LLM autorisés** — deux niveaux, sur le modèle de l'image Docker :

```sql
create table project_model (
    project_id uuid         not null references project (id) on delete cascade,
    model_role varchar(32)  not null,   -- ANALYSIS | CODING | REVIEW | FAST
    model_name varchar(128) not null,
    primary key (project_id, model_role)
);
```

- Un réglage **plateforme** `ai.models.allowed` (nouvelle entrée `LIST` dans `SettingsCatalog`)
  définit l'ensemble des modèles utilisables.
- Le projet **épingle** un modèle par rôle, obligatoirement pris dans cette liste.
- `LlmModelProvider` continue d'exposer `modelFor(AgentType)` ; la résolution consulte d'abord le
  projet du workflow courant, puis `ai.models.*`.
- **L'agent ne choisit jamais son modèle**, exactement comme il ne choisit jamais son image.

### 1.3 Rattachement du workflow

```sql
alter table workflow add column project_id uuid references project (id);
create index ix_workflow_project_created on workflow (project_id, created_at desc);
```

Plus les colonnes de **gel** (§4) et de cycle de vie (§7, §8) :

```sql
alter table workflow add column launch_config text;          -- instantané JSON de la config utilisée
alter table workflow add column sandbox_image varchar(512);  -- image réellement lancée, requêtable
alter table workflow add column finished_at   timestamptz;   -- horodatage terminal fiable
alter table workflow add column archived_at   timestamptz;
alter table workflow add column purged_at     timestamptz;
alter table workflow add column audit_summary text;
```

`launch_config` suit la convention posée en V1 : les artefacts d'audit sont du `text` JSON, lus en
entier, jamais interrogés sur leur structure interne. `sandbox_image` est sorti en colonne parce
qu'il est filtré et audité (« quels workflows ont tourné sur telle image ? »).

### 1.4 Migration des workflows existants

En trois migrations, sans interruption :

1. `V8` — crée `project`, `project_variable`, `project_model` ; ajoute `workflow.project_id`
   **nullable** et les colonnes ci-dessus.
2. `V9` — backfill : un projet par valeur distincte de `workflow.gitlab_project`, nommé
   `Historique — <gitlab_project>`, `active = false`, `jira_project_key` déduit du préfixe majoritaire
   des tickets du groupe (ignoré si les tickets sont des `MSG-*`). `update workflow set project_id = ...`.
   La migration échoue bruyamment s'il reste un `project_id` nul.
3. `V10` — `alter table workflow alter column project_id set not null` + suppression de la colonne
   `gitlab_project` **différée** : elle reste en place une version, en lecture seule, le temps que
   les clients legacy migrent (§3).

### 1.5 Décision : portée de `ux_workflow_active_ticket`

**Recommandation : la laisser globale au ticket.** La garantie recherchée est « un ticket Jira ne
produit jamais deux merge requests concurrentes », et elle porte sur le ticket, pas sur la
configuration. La passer à `(project_id, jira_ticket)` autoriserait deux workflows simultanés sur le
même ticket via deux projets pointant le même dépôt — exactement ce que l'index existe pour empêcher.

---

## 2. CRUD des projets

Nouvelles classes, alignées sur les conventions de packages du dépôt :

```
persistence/entity/ProjectEntity.java
persistence/entity/ProjectVariableEntity.java
persistence/entity/ProjectModelEntity.java
persistence/repository/ProjectRepository.java
project/ProjectService.java              -- règles métier, validations
project/ProjectConfiguration.java        -- record : configuration résolue (§4)
project/ProjectConfigurationResolver.java
project/ProjectNotFoundException.java
project/ProjectValidationException.java
api/ProjectController.java
api/dto/CreateProjectRequest.java, UpdateProjectRequest.java, CloneProjectRequest.java,
         ProjectResponse.java, ProjectDetailResponse.java, ProjectDashboardResponse.java
security/ImagePolicy.java                -- liste blanche de registries, jumeau de BranchPolicy
```

### API

| Méthode | Chemin | Rôle |
| --- | --- | --- |
| `POST` | `/api/projects` | créer |
| `GET` | `/api/projects` | lister — `?q=&active=&page=&size=` |
| `GET` | `/api/projects/{id}` | consulter (config + compteurs) |
| `PUT` | `/api/projects/{id}` | modifier |
| `DELETE` | `/api/projects/{id}` | archiver, ou supprimer si `?force=true` et zéro workflow |
| `POST` | `/api/projects/{id}/clone` | cloner (§6) |
| `GET` | `/api/projects/{id}/workflows` | workflows du projet, paginés et filtrés |
| `GET` | `/api/projects/{id}/dashboard` | métriques (§5) |
| `GET` | `/api/projects/{id}/variables` · `PUT` · `DELETE /{name}` | variables non sensibles |

### Validations

- **GitLab** : `gitLabClient.getProject(gitlabProject)` doit répondre avec la configuration courante.
  Erreur 422 avec le message GitLab en cas d'échec — un projet non joignable ne doit pas être créé.
- **Jira** : `jira_project_key` matche `^[A-Z][A-Z0-9]{1,9}$` ; si Jira est configuré, vérifier que
  le projet existe côté Jira.
- **Image Docker** : validée par `ImagePolicy` (§4).
- **Commandes** : validées par `CommandGuard`.
- **Modèles** : chaque `model_name` appartient à `ai.models.allowed`.
- **Suppression** : refusée s'il existe un workflow non terminal. L'archivage (`active = false`,
  `archived_at`) est le comportement par défaut ; la suppression définitive n'est possible que si le
  projet ne porte plus aucun workflow.

Un projet archivé reste lisible et ne peut plus démarrer de workflow (409).

---

## 3. Créer les workflows depuis un projet

```
POST /api/projects/{projectId}/workflows          { "jiraTicket": "BANK-1245" }
POST /api/projects/{projectId}/workflows/message  { "message": "..." }
```

`DevelopmentWorkflowService.createOrGetActive(...)` et `createFromMessage(...)` prennent un
`ProjectEntity` au lieu d'un `gitlabProjectId`, et :

1. refusent un projet archivé ou inactif ;
2. vérifient que le ticket appartient au projet Jira configuré (`BANK-*`), en 422 sinon ;
3. utilisent `project.gitlabProject()` ;
4. résolvent la configuration complète (§4) et **la figent** dans `launch_config` + `sandbox_image` ;
5. associent le workflow au projet.

### Endpoints legacy

`POST /api/workflows` et `/api/workflows/message` sont conservés une version, avec un pont explicite :
le `gitlabProjectId` fourni est résolu vers **le** projet actif qui le référence.

- 0 correspondance → 409 « aucun projet ne référence ce dépôt, créez-le via `POST /api/projects` » ;
- 2+ correspondances → 409 « plusieurs projets référencent ce dépôt, utilisez
  `POST /api/projects/{id}/workflows` ».

Réponse en 410 après la migration, puis suppression.

### Webhook Jira

`JiraWebhookController` résout aujourd'hui le dépôt via un label `gitlab-project:<x>` posé sur le
ticket. Nouvel ordre de résolution, du plus fiable au plus ancien :

1. projet actif dont `jira_project_key` = préfixe de la clé du ticket (`BANK-1245` → `BANK`) ;
2. si plusieurs, désambiguïsation par le label existant ;
3. si zéro, comportement inchangé (ticket ignoré, log d'avertissement).

C'est le principal gain fonctionnel de la fonctionnalité : les tickets n'ont plus besoin d'être
étiquetés à la main.

---

## 4. Configuration résolue et gel au lancement

### Résolution

`ProjectConfigurationResolver` produit un record immuable :

```java
public record ProjectConfiguration(
        UUID projectId,
        String gitlabProject,
        String jiraProjectKey,
        String sandboxImage,
        String baseBranch,
        String branchPrefix,
        List<String> protectedBranches,
        List<String> buildCommand,
        List<String> testCommand,
        List<String> lintCommand,
        Map<String, String> variables,
        Map<ModelRole, String> models) {}
```

Ordre de précédence, identique pour tous les champs :

1. valeur du projet ;
2. sinon configuration globale (`SettingsCatalog` / `application.yml`) — pour l'image, le repli reste
   `SandboxProperties.imageFor(buildProfile)`, c'est-à-dire le comportement actuel ;
3. jamais l'agent.

### Image Docker

- Si `project.docker_image` est renseigné, il gagne, quel que soit le `BuildProfile` détecté.
- Sinon, `imageFor(profile)` comme aujourd'hui.
- Dans les deux cas, l'image est validée par `ImagePolicy` **au démarrage du sandbox** aussi, pas
  seulement à la création du projet : la liste blanche de registries peut avoir changé entre-temps.
- La valeur réellement utilisée est écrite dans `workflow.sandbox_image`.

`ImagePolicy`, jumeau de `BranchPolicy` (même rôle de second verrou testé) :

- registry ∈ `sandbox.allowed-registries` (nouveau réglage `LIST`) ;
- référence par digest recommandée et **exigible** via `sandbox.require-image-digest` (nouveau
  réglage `BOOLEAN`) : `registry.example.com/team/image@sha256:...` ;
- tag `latest` refusé quand le digest n'est pas exigé, parce qu'il rend l'audit faux.

### Gel

`launch_config` reçoit l'instantané JSON de `ProjectConfiguration` au moment du démarrage. Modifier
le projet ensuite ne change pas l'historique d'un workflow déjà lancé — y compris lors d'un `retry`,
qui **réutilise** le `launch_config` figé plutôt que de re-résoudre. La fiche workflow affiche la
configuration figée et signale visuellement qu'elle diverge de la configuration actuelle du projet.

### Points de branchement

| Élément | Où | Modification |
| --- | --- | --- |
| Image | `DockerSandboxManager.createSandbox` | prend l'image résolue en paramètre au lieu d'appeler `imageFor` |
| Variables | idem, `withEnv` | fusion `sandbox.environment` + variables projet (projet ne peut pas écraser) |
| Préfixe / branches | `BranchPolicy` | méthodes prenant la `ProjectConfiguration` ; la liste globale reste un plancher |
| Commandes | `MavenTools`, `NpmTools`, `PythonTools` | si le projet définit une commande, elle remplace la commande par défaut, après `CommandGuard.validate` |
| Modèles | `LlmModelProvider` / `OpenAiModelProvider` | résolution par rôle avec surcharge projet |

---

## 5. Tableau de bord projet *(ajout)*

### Coût : ce qui manque

`llm_execution` porte les tokens mais aucun coût, et aucun `project_id`.

```sql
alter table llm_execution add column project_id  uuid;
alter table llm_execution add column cost_micros bigint;
create index ix_llm_execution_project on llm_execution (project_id, created_at);

create table model_price (
    model                    varchar(128) primary key,
    prompt_micros_per_1k     bigint       not null,
    completion_micros_per_1k bigint       not null,
    currency                 varchar(3)   not null default 'USD',
    updated_at               timestamptz  not null
);
```

Deux décisions :

- **`project_id` est dénormalisé sur `llm_execution`.** La table n'a pas de clé étrangère vers
  `workflow` ; la dénormalisation rend l'agrégation de coût indépendante de la suppression (§7) et de
  la purge (§8) des workflows.
- **Le coût est calculé et figé à l'enregistrement**, dans `LlmExecutionRecorder`, à partir du
  `model_price` en vigueur. Recalculer a posteriori donnerait des historiques qui changent quand les
  tarifs changent. Modèle sans tarif → `cost_micros` nul, et le tableau de bord affiche
  « n modèles non tarifés » plutôt qu'un total silencieusement faux.

### Endpoint

```
GET /api/projects/{id}/dashboard?from=2026-07-01&to=2026-08-01
```

```json
{
  "period": { "from": "...", "to": "..." },
  "workflows": {
    "total": 128, "byStatus": { "DONE": 92, "FAILED": 21, "CANCELLED": 8, "DEVELOPING": 7 },
    "successRate": 0.814, "inError": 21
  },
  "duration": { "averageMs": 1843000, "p50Ms": 1512000, "p95Ms": 4210000 },
  "llm": {
    "promptTokens": 18422910, "completionTokens": 2210043, "totalTokens": 20632953,
    "costMicros": 41822000, "currency": "USD", "unpricedModels": ["mistral-local"],
    "byModel": [ { "model": "...", "totalTokens": 0, "costMicros": 0 } ],
    "byAgent": [ { "agent": "DEVELOPER", "totalTokens": 0, "costMicros": 0 } ]
  },
  "failures": [ { "workflowId": "...", "jiraTicket": "BANK-1245", "status": "FAILED",
                  "failureReason": "...", "finishedAt": "..." } ]
}
```

Définitions à figer dans le code et la doc, sinon deux écrans afficheront deux chiffres :

- **taux de succès** = `DONE / (DONE + FAILED)`. Les `CANCELLED` et `NEEDS_CLARIFICATION` sont
  exclus du dénominateur : ce sont des décisions humaines, pas des échecs de la plateforme.
- **durée** = `finished_at - created_at`, sur les seuls workflows terminaux. D'où la colonne
  `finished_at` (§1.3) : `updated_at` bouge à chaque transition et ne mesure rien de stable.
- **workflows en erreur** = les 20 derniers `FAILED`, avec `failure_reason`.

### Implémentation

Quatre requêtes d'agrégation JPQL/natives derrière `ProjectDashboardService`, appuyées sur
`ix_workflow_project_created` et `ix_llm_execution_project`. Les percentiles se calculent en SQL
(`percentile_cont`) côté PostgreSQL.

Pas de table de rollup au départ : à quelques milliers de workflows par projet, les agrégats
tiennent. Si le volume l'impose plus tard, ajouter une `project_daily_metric` alimentée par le même
planificateur que la rétention — mais ne pas la construire par anticipation.

Côté Micrometer, `PlatformMetrics` étiquette déjà par `project` (le chemin GitLab) : basculer le tag
sur le nom du projet pour que Grafana et le tableau de bord parlent de la même chose.

---

## 6. Clonage de projet *(ajout)*

```
POST /api/projects/{id}/clone
{ "name": "BANK — intégration", "gitlabProject": "...", "jiraProjectKey": "...", "dockerImage": "..." }
```

- Seul `name` est obligatoire ; les autres champs, s'ils sont fournis, surchargent la source.
- Sont copiés : toute la configuration, les variables (`project_variable`), les modèles épinglés
  (`project_model`), `retention_days`.
- Ne sont **jamais** copiés : `id`, `created_at`/`updated_at`/`version`, `archived_at`, et **aucun
  workflow**. Un clone démarre avec un historique vide.
- `active` est repris de la source ; cloner un projet archivé produit un clone archivé, à réactiver
  explicitement.
- Le clone repasse par **l'intégralité** des validations de création. Un clone n'hérite pas de la
  validité de sa source : le dépôt, la clé Jira ou l'image peuvent avoir été surchargés, et la liste
  blanche de registries peut avoir changé depuis la création de l'original.
- Réponse `201 Created` avec l'en-tête `Location` du nouveau projet.

C'est ce qui justifie l'absence d'unicité sur `gitlab_project` (§1.1) : cloner pour n'ajuster que la
branche cible ou l'image est le cas d'usage principal.

---

## 7. Suppression de workflow

```
DELETE /api/projects/{projectId}/workflows/{workflowId}
```

Règles :

- **409** si le workflow est non terminal — l'utilisateur annule d'abord (`POST /cancel`).
- **404** si le workflow n'appartient pas à `projectId` : jamais 403, pour ne pas révéler l'existence
  d'un workflow d'un autre projet.
- Cascade : `workflow_step`, `agent_execution`, `tool_execution`, `merge_request`, `test_result`,
  `review_result` sont déjà en `on delete cascade` dans `V1`.
- **`llm_execution` n'est pas supprimée.** Ces lignes portent la comptabilité de coût du projet ;
  avec `project_id` dénormalisé (§5), elles restent agrégeables. `workflow_id` devient un identifiant
  historique sans cible — ce que la table permet déjà, faute de clé étrangère.
- **La merge request GitLab distante n'est jamais supprimée.** La plateforme n'a pas à effacer ce
  qu'un humain relit ; seule la ligne locale `merge_request` disparaît.
- Journalisation : une entrée dédiée (`qui`, `quand`, `workflowId`, `projectId`, `jiraTicket`,
  `status`, `mergeRequestUrl`), au niveau `INFO`, via `LogContext`.

### Alternative recommandée : archiver

`POST /api/projects/{projectId}/workflows/{workflowId}/archive` positionne `archived_at`. Le workflow
disparaît des listes par défaut (`?includeArchived=true` pour le voir) mais conserve son audit
complet. C'est le geste à mettre en avant dans l'interface ; la suppression définitive reste
disponible, mais en action secondaire confirmée.

---

## 8. Archivage et rétention *(ajout)*

Objectif : les payloads détaillés — prompts système et utilisateur, sorties brutes des modèles,
arguments et résultats d'outils — représentent l'essentiel du volume et **contiennent du code source
du dépôt**. Ils doivent avoir une durée de vie bornée, sans faire disparaître la trace d'audit.

### Politique

- `project.retention_days` par projet ; à défaut, réglages plateforme
  `workflow.retention.detail-days` (défaut 90) et `workflow.retention.enabled`.
- Ne s'applique qu'aux workflows **terminaux** dont `finished_at` est antérieur au seuil.
- `retention_days = 0` → jamais de purge pour ce projet (projets sensibles ou audités).

### Ce qui est purgé, ce qui reste

| Purgé | Conservé |
| --- | --- |
| `agent_execution.system_prompt`, `user_prompt`, `raw_output` | la ligne `agent_execution` : agent, modèle, tentative, durée, succès |
| `tool_execution.arguments`, `result` | la ligne : nom de l'outil, succès, durée |
| `workflow_step.detail`, `error` | la ligne : transition, durée, succès |
| `workflow.ticket_analysis`, `technical_plan`, `test_report`, `code_review`, `security_report`, `acceptance_report`, `pending_feedback` | `workflow` en entier par ailleurs, `merge_request`, `llm_execution` |

Avant purge, `RetentionService` écrit dans `workflow.audit_summary` un JSON compact : nombre d'appels
par agent, tentatives, durées par étape, totaux de tokens et de coût, décisions finales de revue et
d'acceptation, URL de la merge request. Puis il positionne `purged_at`.

Une colonne plutôt qu'une table : le résumé est lu avec le workflow, jamais interrogé sur sa
structure — même raisonnement que les artefacts en `text` de `V1`.

### Exécution

`RetentionScheduler`, calqué sur `WorkflowScheduler` :

- `@Scheduled(cron = "${workflow.retention.cron:0 30 3 * * *}")`, désactivable par
  `@ConditionalOnProperty` ;
- traitement **par lots** (200 workflows par tour, plafond par exécution) pour ne jamais tenir une
  transaction longue sur des tables chaudes ;
- **idempotent** : `purged_at is null` est la condition d'éligibilité, une purge interrompue reprend
  au tour suivant ;
- journalise le nombre de workflows purgés et l'espace estimé libéré par projet.

L'interface indique « détails purgés le … » sur une fiche workflow purgée, plutôt que d'afficher des
sections vides.

---

## 9. Listing et interface

Écrans à ajouter dans `src/main/resources/static/`, dans le style des pages existantes
(`index.html`, `workflow.html`, `settings.html`) :

- **`projects.html`** — liste : nom, dépôt GitLab, clé Jira, image, nombre de workflows, dernier
  workflow, statut actif. Actions : créer, cloner, archiver.
- **`project.html`** — fiche : onglet *Configuration* (éditable, §1.2), onglet *Workflows* (liste
  paginée, filtres statut / ticket Jira / période), onglet *Tableau de bord* (§5).
- **`workflow.html`** — ajouter le lien vers le projet, la configuration figée (`launch_config`) avec
  signalement de divergence, et le bandeau « détails purgés » le cas échéant.
- **`index.html`** — colonne projet et filtre par projet ; les workflows archivés sont masqués par
  défaut.

Côté API, `GET /api/projects/{id}/workflows` accepte `status`, `jiraTicket`, `from`, `to`,
`includeArchived`, `page`, `size`.

---

## 10. Sécurité

- **Image** : `ImagePolicy` (registries autorisés, digest exigible) appliqué à la création du projet
  **et** au démarrage du sandbox.
- **Modèles** : liste blanche plateforme + épinglage projet ; l'agent ne choisit rien.
- **Variables** : refus des noms et valeurs ressemblant à des secrets ; impossibilité d'écraser
  `sandbox.environment` ; les secrets restent chiffrés dans `platform_setting`.
- **Commandes** : `CommandGuard` reste l'unique porte d'entrée ; la config projet fournit des `argv`,
  jamais une ligne de shell.
- **Branches** : la liste globale de branches protégées est un plancher que le projet ne peut
  qu'étendre. `BranchPolicy.assertNotProtected` conserve son rôle de second verrou.
- **Suppression** : refus sur workflow actif, jamais d'action sur la merge request distante,
  journalisation systématique.

---

## 11. Tests

**Migrations** — Testcontainers PostgreSQL : `V8`→`V10` sur un jeu de workflows préexistants, dont un
`MSG-*` et deux dépôts distincts ; vérifier `project_id not null` et le nombre de projets créés.

**Unitaires**
- `ProjectServiceTest` : création, modification, archivage, suppression refusée avec workflows,
  suppression acceptée sans workflow, unicité du nom.
- `ProjectConfigurationResolverTest` : précédence projet → global, chaque champ.
- `ImagePolicyTest` : registry non autorisé refusé, `latest` refusé, digest accepté, digest exigé.
- `ProjectVariableValidationTest` : noms et valeurs de type secret refusés, collision avec
  `sandbox.environment` refusée.
- `ProjectCloneServiceTest` : config et variables copiées, workflows non copiés, validations rejouées
  sur les champs surchargés.
- `RetentionServiceTest` : seuls les terminaux au-delà du seuil sont purgés, `audit_summary` écrit
  avant purge, seconde exécution sans effet (idempotence), `retention_days = 0` exempté.
- `ProjectDashboardServiceTest` : taux de succès (`CANCELLED` hors dénominateur), durée sur
  `finished_at`, modèles non tarifés remontés séparément.

**Intégration (`*IT`)**
- `ProjectControllerIT` : CRUD complet, pagination, recherche, 409 sur suppression avec workflow actif.
- `ProjectWorkflowControllerIT` : création par projet, ticket hors clé Jira → 422, projet archivé → 409.
- `WorkflowDeletionIT` : cascade effective, `llm_execution` conservée, workflow d'un autre projet → 404,
  workflow en cours → 409.
- `LegacyWorkflowApiIT` : 0 et 2+ projets pour un même dépôt → 409 avec le bon message.
- `JiraWebhookProjectResolutionIT` (WireMock) : résolution par clé Jira, repli sur le label.

**Régression**
- Un workflow créé depuis un projet utilise bien le dépôt, la clé Jira, l'image, le préfixe de
  branche et les commandes du projet.
- Modifier le projet après lancement ne change pas `launch_config` ; un `retry` réutilise le gel.

---

## 12. Découpage

| Lot | Contenu | Livrable |
| --- | --- | --- |
| 1 | `V8`–`V10`, entités, CRUD, validations GitLab/Jira | projets créables et listables, workflows rattachés |
| 2 | Création depuis projet, pont legacy, résolution webhook Jira | plus besoin d'étiqueter les tickets |
| 3 | `ProjectConfiguration`, `ImagePolicy`, gel `launch_config`, branchements sandbox/branches/commandes/modèles | l'exécution obéit au projet |
| 4 | Suppression et archivage de workflow, journalisation | cycle de vie complet |
| 5 | `model_price`, coût figé, tableau de bord | métriques par projet |
| 6 | Clonage | duplication de configuration |
| 7 | `RetentionService` + planificateur | volume borné, audit préservé |
| 8 | Écrans `projects.html`, `project.html`, mises à jour des pages existantes | interface |
| 9 | Retrait des endpoints legacy (410 puis suppression), `drop column workflow.gitlab_project` | nettoyage |

Les lots 1 à 4 forment le socle indispensable ; 5 à 7 sont les ajouts et peuvent être livrés dans
n'importe quel ordre ; 8 suit ce qui est disponible ; 9 attend la fin de la migration des clients.

---

## 13. Écarts entre le plan et l'implémentation

Cinq décisions ont changé pendant l'implémentation, toutes pour une raison remontée par le code ou
par PostgreSQL.

**Un seul `ProjectRequest` au lieu de `CreateProjectRequest` + `UpdateProjectRequest`.** Les deux
records auraient été identiques. Un projet est décrit en entier à chaque fois : avec une mise à jour
partielle, « pas de valeur » et « retirer la valeur » deviennent indistinguables pour chaque champ
optionnel.

**Les listings filtrés passent par des `Specification`, pas par une requête JPQL à paramètres
nullables.** Le motif `(:status is null or w.status = :status)` lie le même paramètre deux fois, dont
une sans contexte de type. PostgreSQL refuse alors l'instruction (`could not determine data type of
parameter`), là où H2 l'accepte : la panne ne serait apparue qu'en production. Même cause pour
`lower(concat('%', :query, '%'))`, planifié en `bytea` — d'où `SearchPattern`, qui construit toujours
un motif LIKE, `%` quand rien n'est filtré. Les deux ont été trouvés en exécutant les tests
d'intégration contre un vrai PostgreSQL.

**`ProjectRuntimeContext`, un `ThreadLocal` ouvert par le moteur pour toute la durée d'un run.**
Trois collaborateurs ont besoin du projet sans pouvoir le recevoir en paramètre : le fournisseur de
modèles est appelé depuis LangChain4j, l'écouteur d'audit LLM est un callback sans contexte métier,
et les outils de build sont instanciés par exécution d'agent. Tout le reste — image du bac à sable,
politique de branches — reçoit la configuration explicitement.

**L'image du profil est résolue au démarrage du bac à sable, pas à la création du workflow.** Elle
dépend du `BuildProfile`, qui n'est connu qu'après l'analyse du dépôt. `launch_config` porte donc
l'image du projet ou `null`, et `workflow.sandbox_image` reçoit la valeur réellement lancée.

**`AiProperties` refusait une `Map` vide.** `new EnumMap<>(source)` lève `IllegalArgumentException`
sur une map vide qui n'est pas déjà un `EnumMap` — invisible tant que seul le binder Spring
construisait le record, révélé par le premier test qui l'instancie à la main. Corrigé par une copie
clé par clé.

Le lot 9 (retrait des endpoints legacy, `drop column workflow.gitlab_project`) n'est volontairement
pas fait : il attend la migration des clients.
