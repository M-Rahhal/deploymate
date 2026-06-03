# DeployMate — Complete Project Reference

> **This file is the authoritative reference for the entire DeployMate codebase.**
> Every architectural decision, file, class, API endpoint, type, hook, config property, test, and operational detail is documented here.

---

## Table of Contents

1. [What DeployMate Does](#1-what-deploymate-does)
2. [System Architecture](#2-system-architecture)
3. [Monorepo Layout](#3-monorepo-layout)
4. [Tech Stack — All Versions](#4-tech-stack--all-versions)
5. [Environment Configuration](#5-environment-configuration)
6. [Backend — Spring Boot (Java 21)](#6-backend--spring-boot-java-21)
   - [Entry Point](#61-entry-point)
   - [Config Layer](#62-config-layer)
   - [DTOs (Java Records)](#63-dtos-java-records)
   - [Domain Models](#64-domain-models)
   - [Services](#65-services)
   - [Controllers — REST API](#66-controllers--rest-api)
   - [Logging System](#67-logging-system)
   - [Orchestration Engine](#68-orchestration-engine)
7. [Frontend — React + Vite](#7-frontend--react--vite)
   - [Entry Point & Root Component](#71-entry-point--root-component)
   - [TypeScript Types](#72-typescript-types)
   - [Zustand Store](#73-zustand-store)
   - [API Layer](#74-api-layer)
   - [Hooks](#75-hooks)
   - [Utility Helpers](#76-utility-helpers)
   - [Components](#77-components)
   - [Vite Config](#78-vite-config)
8. [MCP Server — Claude Integration](#8-mcp-server--claude-integration)
9. [Deployment Flow Logic (Complete)](#9-deployment-flow-logic-complete)
10. [UI Design System](#10-ui-design-system)
11. [Testing](#11-testing)
12. [Docker & CI](#12-docker--ci)
13. [Running Locally](#13-running-locally)
14. [Security Rules](#14-security-rules)
15. [Code Quality Standards](#15-code-quality-standards)
16. [Implementation Status](#16-implementation-status)
17. [Troubleshooting](#17-troubleshooting)

---

## 1. What DeployMate Does

DeployMate is a **self-hosted, locally-run web application** that automates staging deployments across multiple microservice repositories. A single Jira ticket typically requires changes across N repositories — some are SDKs/packages, some are real services. Today this is done manually. DeployMate eliminates that.

### What the user does

1. Opens `http://localhost:8080`
2. Enters a Jira ticket number (e.g. `PROJ-123`) — this auto-fills the Default Source Branch
3. Adds rows for every affected repository — display name, repo name, type (`SDK` or `SERVICE`), **stage number**
4. Configures per-row **source branch**, **target branch**, **Jenkins job**
5. Configures which **Steps to execute** per row (all independently togglable):
   - **Merge branch** — verify + merge source → target
   - **Create tag** — fetch latest GitHub tag, compute next tag name, create GitHub pre-release
   - **Trigger pipeline** — trigger Jenkins with `git_branch=<tagName>` (SERVICE — tag must be set) or `git_branch=origin/<targetBranch>` (SDK)
   - **Post Jira comment** — post structured ADF comments at each step
6. Clicks **Deploy All** (or uses per-row step buttons for granular control)
7. Rows with the **same stage number** run **in parallel**; stages execute sequentially

### What the tool does automatically (per row, using its steps config)

Each configured step runs in sequence per row:

1. **Merge branch** (if enabled): verifies source branch exists, merges source → target. Conflict halts that row.
2. **Create tag** (if enabled): gets HEAD SHA of target branch, creates a git tag + GitHub pre-release (`prerelease: true`).
3. **Trigger pipeline** (if enabled): fires Jenkins with parameter `git_branch`. Value is determined by **row type**:
   - `SERVICE` → `git_branch=<tagName>` (pre-release tag; tagName must be set or the step fails)
   - `SDK` → `git_branch=origin/<targetBranch>` (branch ref; no tag involved)
4. Polls Jenkins every 5 seconds for build status
5. **Post Jira comment** (if enabled): posts structured ADF comment at each step event
6. Writes all events to `logs/deploymate.log` (append-only)

### Deploy All execution order

- All rows use **Stage number** (1-based) to control ordering
- Rows with the **same stage number** run **in parallel**
- Stages execute **sequentially** — Stage 2 does not start until all Stage 1 rows are DONE
- If any row in a stage has a conflict or failure, Deploy All **halts before the next stage starts**
- Already-DONE rows are **idempotent** — re-running skips DONE/SKIPPED steps
- `type` (SDK / SERVICE) affects default step configuration and display only — not execution order

---

## 2. System Architecture

```
┌───────────────────────────────────────────────────────────────────────────┐
│                           Developer's Machine                              │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │              DeployMate  (Spring Boot 3, port 8080)                  │  │
│  │                                                                      │  │
│  │  ┌──────────────────────┐    ┌────────────────────────────────────┐  │  │
│  │  │  React SPA           │    │  Spring Boot REST Controllers       │  │  │
│  │  │  (served from        │◄──►│  POST /api/merge                   │  │  │
│  │  │  /static in jar)     │    │  POST /api/tag                     │  │  │
│  │  │                      │    │  POST /api/pipeline/trigger        │  │  │
│  │  │  Zustand + TanStack  │    │  GET  /api/pipeline/status         │  │  │
│  │  │  polling every 5s    │    │  POST /api/jira/comment            │  │  │
│  │  └──────────────────────┘    │  POST /api/deploy/all              │  │  │
│  │                              │  GET  /api/log                     │  │  │
│  │                              └──────────────┬─────────────────────┘  │  │
│  │                                             │                        │  │
│  │  ┌────────────────────────────┐             │                        │  │
│  │  │  MCP Server (Node stdio)   │─────────────┘                        │  │
│  │  │  Calls http://localhost    │  (calls same REST endpoints)         │  │
│  │  │  :8080/api/...             │                                      │  │
│  │  └────────────────────────────┘                                      │  │
│  │                                                                      │  │
│  │  ┌──────────────────────┐  ┌──────────────────────────────────────┐  │  │
│  │  │  OrchestratorService │  │  SLF4J + Logback                     │  │  │
│  │  │  Stage-aware, Java   │  │  logs/deploymate.log (append-only)   │  │  │
│  │  │  21 virtual threads  │  │  MDC: service name + stage label     │  │  │
│  │  └──────────────────────┘  └──────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  .env (gitignored) — all credentials live here                            │
│                                                                            │
└─────────────────────────────────┬──────────────────────────────────────────┘
                                  │
               ┌──────────────────┼───────────────────┐
               ▼                  ▼                    ▼
        GitHub REST API    Jenkins REST API       Jira REST API v3
        • verify branch    • get CSRF crumb       • POST ADF comment
        • POST merge       • trigger build          on issue
        • create tag ref   • poll queue item
        • create release   • poll build status
        (pre-release)      • stream build log
```

### How the SPA is served in production

The Vite build outputs to `frontend/dist/` which `vite.config.ts` redirects to `backend/src/main/resources/static/`. Spring Boot's static resource handler serves those files at `/`. `SpaController` forwards any unknown path to `index.html` so React Router works.

### How development works (two terminals)

```
Terminal 1: cd backend && ./mvnw spring-boot:run   → Spring Boot on :8080
Terminal 2: cd frontend && npm run dev             → Vite on :5173, proxying /api → :8080
```

---

## 3. Monorepo Layout

```
deploymate/                       ← git root
│
├── .env.example                  ← Template — copy to .env and fill in
├── .env                          ← Your credentials (GITIGNORED, NEVER COMMIT)
├── .gitignore
├── .sdkmanrc                     ← Pins java=21.0.11-amzn for sdkman users
├── Dockerfile                    ← 3-stage: node:22-alpine → eclipse-temurin:21-jdk-alpine → 21-jre-alpine
├── docker-compose.yml            ← Single container, named volumes for logs + data
├── postman_collection.json       ← Postman collection covering all 12 backend endpoints
├── README.md
│
├── backend/                      ← Spring Boot 3.3.5, Java 21, Maven
│   ├── mvnw                      ← Maven wrapper
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/deploymate/
│       │   │   ├── DeployMateApplication.java
│       │   │   ├── config/
│       │   │   │   ├── AppProperties.java          ← @ConfigurationProperties("deploymate")
│       │   │   │   ├── GlobalExceptionHandler.java ← @RestControllerAdvice
│       │   │   │   └── WebConfig.java              ← CORS for dev (:5173 → :8080)
│       │   │   ├── controller/
│       │   │   │   ├── MergeController.java        ← POST /api/merge
│       │   │   │   ├── TagController.java          ← POST /api/tag, GET /api/tag/next
│       │   │   │   ├── PipelineController.java     ← POST /api/pipeline/trigger, GET /api/pipeline/status
│       │   │   │   ├── JiraController.java         ← POST /api/jira/comment
│       │   │   │   ├── DeployController.java       ← POST /api/deploy/all
│       │   │   │   ├── JenkinsConfigController.java ← GET/POST /api/jenkins/categories, /api/jenkins/service-names
│       │   │   │   ├── LogController.java          ← GET /api/log
│       │   │   │   └── SpaController.java          ← GET /** → forward:/index.html
│       │   │   ├── dto/
│       │   │   │   ├── MergeRequest.java
│       │   │   │   ├── MergeResponse.java
│       │   │   │   ├── TagRequest.java
│       │   │   │   ├── TagResponse.java
│       │   │   │   ├── PipelineTriggerRequest.java (jenkinsJob + gitBranch — no ParamType enum)
│       │   │   │   ├── PipelineTriggerResponse.java
│       │   │   │   ├── PipelineStatusResponse.java (contains BuildState enum)
│       │   │   │   ├── JiraCommentRequest.java
│       │   │   │   ├── ServiceRowDto.java          (contains ServiceType enum: SDK | SERVICE)
│       │   │   │   └── DeployAllRequest.java
│       │   │   ├── entity/
│       │   │   │   ├── JenkinsCategory.java        ← JPA entity: jenkins_categories table
│       │   │   │   └── JenkinsServiceEntry.java    ← JPA entity: jenkins_service_entries (unique: category+name)
│       │   │   ├── model/
│       │   │   │   ├── DeployException.java        ← unchecked; carries ErrorCode + optional repo
│       │   │   │   └── ErrorCode.java              ← CONFLICT, NOT_FOUND, AUTH_FAILED, NETWORK, INVALID_INPUT, RATE_LIMITED
│       │   │   ├── repository/
│       │   │   │   ├── JenkinsCategoryRepository.java
│       │   │   │   └── JenkinsServiceEntryRepository.java
│       │   │   └── service/
│       │   │       ├── GitHubService.java          ← GitHub REST API via OkHttp
│       │   │       ├── JenkinsService.java         ← Jenkins REST API via OkHttp
│       │   │       ├── JenkinsConfigService.java   ← Idempotent save/list of Jenkins categories + service names (H2)
│       │   │       ├── JiraService.java            ← Jira REST API v3 via OkHttp
│       │   │       ├── OrchestratorService.java    ← Stage-aware Deploy All
│       │   │       ├── LogService.java             ← SLF4J MDC wrapper
│       │   │       └── TagGeneratorService.java    ← computeNextTag() — rc/staging/semver increment rules
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-test.yml            ← In-memory H2 for tests (ddl-auto: create-drop)
│       │       └── static/                         ← Frontend build output (GITIGNORED)
│       └── test/java/com/deploymate/
│           ├── controller/
│           │   ├── MergeControllerTest.java
│           │   ├── TagControllerTest.java
│           │   ├── PipelineControllerTest.java
│           │   ├── JiraControllerTest.java
│           │   ├── JenkinsConfigControllerTest.java
│           │   └── DeployControllerTest.java
│           └── service/
│               ├── GitHubServiceTest.java
│               ├── JenkinsServiceTest.java
│               ├── JiraServiceTest.java
│               ├── OrchestratorServiceTest.java
│               └── TagGeneratorServiceTest.java
│
├── frontend/                     ← React 19, Vite 6, TypeScript 5, Tailwind 3
│   ├── package.json
│   ├── vite.config.ts            ← proxy /api→:8080; build outDir→backend/static
│   ├── tailwind.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── App.tsx               ← <Providers><DeploymentDashboard /></Providers>
│       ├── main.tsx              ← React.StrictMode root
│       ├── index.css
│       ├── types/
│       │   └── index.ts          ← ALL TypeScript types + computeOverallState()
│       ├── hooks/
│       │   ├── useDeployStore.ts ← Zustand+Immer store (global state)
│       │   └── useDeployActions.ts ← useMergeAction, useTagAction, usePipelineAction, useAllStepsAction
│       ├── lib/
│       │   ├── api.ts            ← Typed fetch wrappers for every endpoint
│       │   └── utils.ts          ← cn(), shortSha(), formatTimestamp(), generateTagName()
│       ├── components/
│       │   ├── DeploymentDashboard.tsx ← Root layout (header, sections)
│       │   ├── GlobalConfig.tsx        ← Ticket + branch config card
│       │   ├── StageVisualizer.tsx     ← Animated pill flow: [S1]→[S2∥]→[Services]
│       │   ├── ServiceTable.tsx        ← Renders list of ServiceRow cards
│       │   ├── ServiceRow.tsx          ← Full card: fields + step badges + action buttons
│       │   ├── StatusBadge.tsx         ← Overall status badge (IDLE/MERGING/DONE/etc.)
│       │   ├── StepBadge.tsx           ← Per-step badge (Merge/Tag/Pipeline)
│       │   ├── ConflictPanel.tsx       ← Orange warning with retry button
│       │   ├── ActivityLog.tsx         ← Scrollable in-memory log panel
│       │   ├── BuildLogDrawer.tsx      ← Drawer showing Jenkins build log fragment
│       │   ├── DeployAllButton.tsx     ← Sticky Deploy All button in header
│       │   ├── EmptyState.tsx          ← Empty table CTA
│       │   └── providers.tsx           ← QueryClientProvider + Toaster wrappers
│       └── components/ui/            ← shadcn/ui components (generated)
│           ├── badge.tsx, button.tsx, card.tsx, dialog.tsx, input.tsx
│           ├── label.tsx, scroll-area.tsx, select.tsx, separator.tsx
│           ├── switch.tsx, tooltip.tsx
│
├── mcp/                          ← MCP stdio server (Node.js + TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── server.ts                 ← 6 tools, rate limiter, credential sanitizer
│
├── scripts/
│   └── smoke-test.sh             ← 18 checks against a live server (curl + jq)
│
└── logs/
    ├── .gitignore                ← *.log is gitignored
    └── deploymate.log            ← Runtime log (append-only, never truncated)
```

---

## 4. Tech Stack — All Versions

### Backend

| Package | Version | Purpose |
|---|---|---|
| Java | 21 LTS | Runtime (uses virtual threads, records, sealed interfaces) |
| Spring Boot | 3.3.5 | Framework |
| spring-boot-starter-web | 3.3.5 | REST controllers, embedded Tomcat |
| spring-boot-starter-validation | 3.3.5 | Bean Validation (`@Valid`, `@NotBlank`, etc.) |
| spring-boot-starter-data-jpa | 3.3.5 | JPA/Hibernate for Jenkins config persistence |
| spring-boot-starter-logging | 3.3.5 | SLF4J + Logback |
| spring-boot-configuration-processor | 3.3.5 | `@ConfigurationProperties` annotation processing |
| spring-boot-devtools | 3.3.5 | Hot reload in dev (runtime, optional) |
| h2 | (runtime) | Embedded H2 database — file-based in prod, in-memory in tests |
| okhttp3 | 4.12.0 | HTTP client for GitHub, Jenkins, Jira calls |
| okhttp3-mockwebserver | 4.12.0 | Test dependency for service unit tests |
| jackson-databind | (transitive) | JSON serialization/deserialization |
| lombok | latest | Boilerplate reduction |
| spring-boot-starter-test | 3.3.5 | JUnit 5, Mockito, MockMvc, AssertJ |

### Frontend

| Package | Version | Purpose |
|---|---|---|
| react | ^19.0.0 | UI library |
| react-dom | ^19.0.0 | DOM renderer |
| typescript | ^5.7.2 | Type safety |
| vite | ^6.0.5 | Build tool and dev server |
| @vitejs/plugin-react | ^4.3.4 | React plugin for Vite |
| tailwindcss | ^3.4.17 | Utility CSS |
| @tailwindcss/typography | ^0.5.15 | Prose styles for log output |
| shadcn/ui | latest (CLI) | Pre-built accessible components (via Radix UI) |
| lucide-react | ^0.468.0 | Icon set |
| clsx | ^2.1.1 | Conditional class names |
| tailwind-merge | ^2.5.5 | Merge Tailwind classes without conflicts |
| class-variance-authority | ^0.7.1 | Component variant styling |
| framer-motion | ^11.15.0 | Animations (row enter/exit, stage pills) |
| @tanstack/react-query | ^5.62.7 | Server state (used for log polling) |
| zustand | ^5.0.2 | Client state (service rows, global config) |
| immer | ^10.1.1 | Immutable updates in Zustand |
| uuid | ^11.0.3 | UUID generation for row IDs |
| sonner | ^1.7.1 | Toast notifications |
| @radix-ui/* | various | Badge, Button, Dialog, Input, Label, ScrollArea, Select, Separator, Slot, Switch, Tooltip |

### MCP Server

| Package | Version | Purpose |
|---|---|---|
| @modelcontextprotocol/sdk | ^1.12.0 | MCP server (stdio transport) |
| axios | ^1.7.9 | HTTP calls to Spring Boot API |
| zod | ^3.24.0 | Tool argument validation/schemas |
| typescript | ^5.7.2 | Type safety |
| tsx | ^4.19.2 | Run TypeScript directly (dev) |

---

## 5. Environment Configuration

### `.env` file (gitignored — never commit)

Copy `.env.example` to `.env` in the project root. All values are **required** unless noted otherwise.

```dotenv
# ── GitHub ──────────────────────────────────────────────────────────────────
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
# Classic PAT, required scopes: repo + workflow
# Create at: GitHub → Settings → Developer settings → Personal access tokens → Classic

GITHUB_ORG=your-org-name
# The GitHub org or username owning ALL repos you'll deploy.
# e.g. if your repo is github.com/acme-corp/my-service → GITHUB_ORG=acme-corp

# ── Jenkins ─────────────────────────────────────────────────────────────────
JENKINS_URL=https://jenkins.your-company.com
# No trailing slash. Must be http:// or https:// (validated on startup)

JENKINS_USER=your-username
# Your Jenkins login username

JENKINS_TOKEN=your-api-token
# Jenkins API token (NOT your password)
# Generate at: https://<jenkins>/user/<username>/configure → API Token

# ── Jira ────────────────────────────────────────────────────────────────────
JIRA_URL=https://your-company.atlassian.net
# No trailing slash. Must be http:// or https://

JIRA_EMAIL=you@company.com
# Email address of the Atlassian account that owns the token

JIRA_TOKEN=your-jira-api-token
# Generate at: https://id.atlassian.com/manage-profile/security/api-tokens

# ── Defaults (optional — these have defaults if omitted) ─────────────────────
DEFAULT_TARGET_BRANCH=env/staging
# Default "merge INTO" branch for all new rows. Overridable per row in the UI.

TAG_PREFIX=env-stag
# Prefix for auto-generated tag names.
# Tag format: <TAG_PREFIX>-<YYYYMMDD>-<repo-name>-001
# Example: env-stag-20260601-payment-service-001
```

### `backend/src/main/resources/application.yml` (actual file)

```yaml
server:
  port: 8080

spring:
  application:
    name: deploymate
  jackson:
    default-property-inclusion: non_null  # Omits null fields from JSON responses

deploymate:
  github:
    token: ${GITHUB_TOKEN}
    org: ${GITHUB_ORG}
  jenkins:
    url: ${JENKINS_URL}
    user: ${JENKINS_USER}
    token: ${JENKINS_TOKEN}
  jira:
    url: ${JIRA_URL}
    email: ${JIRA_EMAIL}
    token: ${JIRA_TOKEN}
  defaults:
    target-branch: ${DEFAULT_TARGET_BRANCH:env/staging}
    tag-prefix: ${TAG_PREFIX:env-stag}

spring:
  datasource:
    url: jdbc:h2:file:./data/deploymate;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    open-in-view: false

logging:
  file:
    name: logs/deploymate.log       # Append-only; Spring Boot/Logback never truncates
  pattern:
    file: "[%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ}] [%-7level] [%-20X{service}] [%-10X{stage}] %msg%n"
    console: "%clr(%d{HH:mm:ss.SSS}){faint} %clr(%-5level){highlight} %clr([%-20X{service}]){cyan} %msg%n"
  level:
    root: INFO
    com.deploymate: DEBUG
```

---

## 6. Backend — Spring Boot (Java 21)

### 6.1 Entry Point

**`DeployMateApplication.java`**
```java
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DeployMateApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeployMateApplication.class, args);
    }
}
```

### 6.2 Config Layer

#### `config/AppProperties.java`

Typed configuration using `@ConfigurationProperties(prefix = "deploymate")`. **This is the ONLY class that reads credentials.** No other class calls `System.getenv()`.

```java
@ConfigurationProperties(prefix = "deploymate")
public record AppProperties(
    GitHub github,
    Jenkins jenkins,
    Jira jira,
    Defaults defaults
) {
    public record GitHub(String token, String org) {}
    public record Jenkins(String url, String user, String token) {}
    public record Jira(String url, String email, String token) {}
    public record Defaults(
        @DefaultValue("env/staging") String targetBranch,
        @DefaultValue("env-stag")   String tagPrefix
    ) {}
}
```

#### `config/WebConfig.java`

Configures CORS to allow the Vite dev server (`localhost:5173`) to call the Spring Boot API (`localhost:8080`). In production the SPA is served from Spring Boot itself so CORS is not needed — this only applies in dev.

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
    }
}
```

#### `config/GlobalExceptionHandler.java`

`@RestControllerAdvice` that handles:
- `DeployException` → maps `ErrorCode` to HTTP status (CONFLICT→409, NOT_FOUND→404, AUTH_FAILED→401, INVALID_INPUT→400, others→500)
- `MethodArgumentNotValidException` → HTTP 400 with `{error: "INVALID_INPUT", details: [...field errors]}`
- `ConstraintViolationException` → HTTP 400 (for `@Validated` `@RequestParam` violations)
- `MissingServletRequestParameterException` → HTTP 400 with field name in message
- `Exception` (catch-all) → HTTP 500 with generic message; stack traces **never** exposed to client

Response body shape for errors:
```json
{ "error": "CONFLICT", "message": "Merge conflict detected" }
```
```json
{ "error": "INVALID_INPUT", "details": ["repo: must not be blank", "..."] }
```

### 6.3 DTOs (Java Records)

All request DTOs use Jakarta Bean Validation. All response DTOs are plain records.

#### `dto/MergeRequest.java`
`org` is **not** a field — the backend reads it from `AppProperties.github().org()`.
```java
public record MergeRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$")   String repo,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")  String sourceBranch,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")  String targetBranch,
    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$")        String ticket
) {}
```

#### `dto/MergeResponse.java`
```java
public record MergeResponse(boolean success, boolean conflict, String sha, String message) {}
```

#### `dto/TagRequest.java`
`org` is **not** a field — the backend reads it from `AppProperties.github().org()`.
```java
public record TagRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$")   String repo,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9._-]{1,128}$")   String tagName,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")  String targetBranch,
    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$")        String ticket
) {}
```

#### `dto/TagResponse.java`
```java
public record TagResponse(boolean success, String tagName, String sha, String releaseUrl, String message) {}
```

#### `dto/PipelineTriggerRequest.java`
```java
public record PipelineTriggerRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$") String jenkinsJob,
    @NotBlank                                               String gitBranch
) {}
```

The Jenkins parameter key is always `git_branch`. No `ParamType` enum — the caller decides what value to pass:
- SDK row: `gitBranch = "origin/" + targetBranch`
- SERVICE row: `gitBranch = tagName` (if `tagName` is empty the step immediately fails with an error toast)

```java
```

#### `dto/PipelineTriggerResponse.java`
```java
public record PipelineTriggerResponse(boolean success, String queueItemUrl, String message) {}
```

#### `dto/PipelineStatusResponse.java`
```java
public record PipelineStatusResponse(
    BuildState state,       // QUEUED | RUNNING | SUCCESS | FAILURE | ABORTED | UNKNOWN
    String buildUrl,
    Integer buildNumber,
    String logFragment,     // Last 4000 chars of build log
    String message
) {
    public enum BuildState { QUEUED, RUNNING, SUCCESS, FAILURE, ABORTED, UNKNOWN }
}
```

#### `dto/JiraCommentRequest.java`
```java
public record JiraCommentRequest(
    @NotBlank @Pattern(regexp = "^[A-Z]{1,20}-[0-9]{1,10}$") String issueKey,
    @NotBlank @Size(max = 10000)                              String text
) {}
```

#### `dto/ServiceRowDto.java`
```java
public record ServiceRowDto(
    @NotBlank                                                       String id,
    @NotBlank @Size(max = 100)                                      String name,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,100}$")         String repo,
    @NotNull                                                        ServiceType type,  // SDK | SERVICE
    @Min(1) @Max(99)                                                int stage,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")        String sourceBranch,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_.-]{1,255}$")        String targetBranch,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9/_-]{1,200}$")         String jenkinsJob,
    @Pattern(regexp = "^([a-zA-Z0-9._-]{1,128})?$")                String tagName,
    @NotNull @Valid                                                  Steps steps
) {
    public enum ServiceType { SDK, SERVICE }

    /** Per-service step configuration. All steps independently togglable. */
    public record Steps(
        boolean mergeBranch,     // verify + merge source → target
        boolean createTag,       // create pre-release tag; also switches pipeline param to TAG
        boolean triggerPipeline, // trigger Jenkins build
        boolean updateJira       // post ADF comments to Jira
    ) {}
}
```

**Pipeline param logic (determined by `type`, not by `steps.createTag`):**
- `type=SERVICE` → `git_branch=<tagName>` (pre-release tag; tagName must be non-blank or pipeline step fails immediately)
- `type=SDK` → `git_branch=origin/<targetBranch>` (branch ref; no tag involved)

#### `dto/DeployAllRequest.java`
```java
public record DeployAllRequest(
    @Pattern(regexp = "^([A-Z]{1,20}-[0-9]{1,10})?$") String ticket,
    @NotEmpty @Valid                                    List<ServiceRowDto> rows
) {}
```

### 6.4 Domain Models

#### `model/ErrorCode.java`
```java
public enum ErrorCode {
    CONFLICT,
    NOT_FOUND,
    AUTH_FAILED,
    NETWORK,
    INVALID_INPUT,
    RATE_LIMITED
}
```

#### `model/DeployException.java`
Unchecked exception carrying `ErrorCode` and optional `repo` name.
```java
public class DeployException extends RuntimeException {
    private final ErrorCode code;
    private final String repo;  // nullable

    // Constructors: (message, code) and (message, code, repo)
    public ErrorCode getCode() { return code; }
    public String getRepo()    { return repo; }
}
```

### 6.5 Services

#### `service/GitHubService.java`

Uses `OkHttpClient` with Bearer token auth (`Authorization: Bearer <GITHUB_TOKEN>`).
Headers on every request: `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`.

All methods throw `DeployException` on error. The **constructor accepts an `OkHttpClient` parameter** for testability (MockWebServer injects a custom client in tests).

| Method | Signature | Returns | Notes |
|--------|-----------|---------|-------|
| `verifyBranch` | `(String repo, String branch) → boolean` | `true` if 200, `false` if 404, throws on other errors | GET `/repos/{org}/{repo}/git/refs/heads/{branch}` |
| `mergeBranch` | `(String repo, String src, String target, String ticket) → MergeResult` | `MergeResult(success, conflict, sha)` | POST `/repos/{org}/{repo}/merges` — 201→success with SHA, 204→already up-to-date, 409→conflict, other→throws |
| `getBranchSha` | `(String repo, String branch) → String` | HEAD commit SHA | GET `/repos/{org}/{repo}/git/refs/heads/{branch}` → `.object.sha` |
| `getLatestTag` | `(String repo) → Optional<String>` | Most recently created git tag name, or empty | GET `/repos/{org}/{repo}/tags?per_page=1` — uses the tags API (not releases) so tags without an associated GitHub release are included; GitHub returns tags sorted by commit date descending |
| `tagExists` | `(String repo, String tagName) → boolean` | `true` if tag ref exists | GET `/repos/{org}/{repo}/git/refs/tags/{tagName}` — 200→true, 404→false |
| `createTagAndPreRelease` | `(String repo, String tagName, String sha, String ticket) → TagResult` | `TagResult(releaseUrl)` | **First calls `tagExists()` — throws INVALID_INPUT if already exists.** Then POST `/git/refs` then POST `/releases` with `prerelease:true` |

Inner records: `MergeResult(boolean success, boolean conflict, String sha)`, `TagResult(String releaseUrl)`

#### `service/JenkinsService.java`

Uses `OkHttpClient` with HTTP Basic auth (`user:token` base64-encoded).
**CSRF crumb is fetched before every POST** — required by Jenkins CSRF protection. Crumb comes from `GET /crumbIssuer/api/json`.

| Method | Signature | Returns | Notes |
|--------|-----------|---------|-------|
| `triggerBuild` | `(String jobPath, String gitBranchValue) → String` | Queue item URL (from `Location` header) | POST `/job/{jobPath}/buildWithParameters?git_branch={urlEncoded}` — expects 201 |
| `pollQueueItem` | `(String queueItemUrl) → String\|null` | Build URL or `null` if still queued | GET `{queueItemUrl}api/json` → `.executable.url` |
| `pollBuildStatus` | `(String buildUrl) → BuildStatus` | `BuildStatus(result, number)` | GET `{buildUrl}api/json`; `result` is `null` while running |
| `getBuildLog` | `(String buildUrl, int start) → LogFragment` | `LogFragment(text, nextStart, hasMore)` | GET `{buildUrl}logText/progressiveText?start={start}` |
| `getLastDeployedBranch` | `(String jobPath) → Optional<String>` | `git_branch` value from last successful build, or empty | GET `{jobPath}/lastSuccessfulBuild/api/json?tree=actions[parameters[name,value]]` — parses `git_branch` param; returns `Optional.empty()` on 404 or missing param; never throws |

Jenkins job path format: nested folders use `/` separator (e.g. `Team/Backend/deploy`). The `triggerBuild` and `getLastDeployedBranch` methods both convert this with `.replace("/", "/job/")`.

Inner records: `Crumb(String field, String value)`, `BuildStatus(String result, int number)`, `LogFragment(String text, int nextStart, boolean hasMore)`

#### `service/JiraService.java`

Uses `OkHttpClient` with HTTP Basic auth (`email:token` base64-encoded).
Comments are sent in **Atlassian Document Format (ADF)** — Jira Cloud REST API v3 requires ADF, not plain text.

```
POST {JIRA_URL}/rest/api/3/issue/{issueKey}/comment
Body: { "body": <ADF node> }
```

Private `toAdf(String text)` method converts plain text to a minimal ADF document node.

#### `service/TagGeneratorService.java`

Computes the next tag name from the last GitHub tag for a repository.

`computeNextTag(String lastTagName) → String` — rules:
1. `lastTagName` is null/blank → `"v1.0.0rc1"`
2. Ends with `rc<N>` (case-insensitive) → `<base>rc<N+1>` (e.g. `v1.0.0rc1` → `v1.0.0rc2`)
3. Matches `<base>staging<N>` → `<base>staging<N+1>` (e.g. `v1.0.0-staging1` → `v1.0.0-staging2`)
4. Contains `"staging"` (no trailing number) → `<lastTag>2`
5. Matches semver `(v?)M.N.P` → `<v?>M.N.<P+1>rc1` (e.g. `v1.0.0` → `v1.0.1rc1`)
6. Fallback → `<lastTag>rc1`

Regex patterns: `RC_PATTERN = ^(.+?)rc(\d+)$`, `STAGING_NUM_PATTERN = ^(.+?staging)(\d+)$`, `SEMVER_PATTERN = ^(v?)(\d+)\.(\d+)\.(\d+)$`

#### `service/JenkinsConfigService.java`

`@Service @Transactional` — persists Jenkins categories and service names to H2.

| Method | Description |
|--------|-------------|
| `getCategories() → List<String>` | All category names, sorted alphabetically |
| `saveCategory(String name) → String` | Idempotent — trims, saves only if not already stored, returns trimmed name |
| `getServiceNames(String categoryName) → List<String>` | All service names for a category, sorted alphabetically |
| `saveServiceName(String categoryName, String serviceName)` | Idempotent — creates category if needed, saves entry only if not already stored |

#### `service/LogService.java`

SLF4J MDC wrapper. Puts `service` and `stage` keys into MDC before logging, removes them after. Used throughout the system for structured log entries.

```java
logSvc.info(serviceName, stageLabel, message);  // → SLF4J INFO
logSvc.warn(serviceName, stageLabel, message);  // → SLF4J WARN
logSvc.error(serviceName, stageLabel, message); // → SLF4J ERROR
```

Log entry format in file:
```
[2026-05-27T14:01:55.012Z] [INFO   ] [auth-service        ] [Service   ] Merging PROJ-123 → env/staging
```

### 6.6 Controllers — REST API

All controllers:
- Use `@Valid` on all `@RequestBody` parameters
- Never call external APIs directly (delegate to services)
- Never log stack traces to clients

#### `POST /api/merge`
**`MergeController`** — merges source branch into target branch.
1. Calls `github.verifyBranch()` — returns 400 if branch not found
2. Calls `github.mergeBranch()` — returns 409 on conflict with `{success:false, conflict:true}`
3. Returns 200 with `{success:true, conflict:false, sha:"abc1234", message:"Merge successful"}`

#### `POST /api/tag`
**`TagController`** — creates git tag and GitHub pre-release for a SERVICE.
1. Calls `github.getBranchSha()` to get HEAD SHA
2. Calls `github.createTagAndPreRelease()` — creates ref then release
3. Returns 200 with `{success:true, tagName, sha, releaseUrl, message}`

#### `POST /api/pipeline/trigger`
**`PipelineController`** — triggers a Jenkins build.
1. Calls `jenkins.triggerBuild(req.jenkinsJob(), req.gitBranch())` — always uses `git_branch` Jenkins parameter
2. Returns 200 with `{success:true, queueItemUrl, message:"Build queued"}`

#### `GET /api/pipeline/status?[queueItemUrl=...][&buildUrl=...]`
**`PipelineController`** — polls build status.
- If `queueItemUrl` is given: calls `pollQueueItem()` to get build URL; if still queued returns `{state:"QUEUED"}`
- If `buildUrl` is given (or resolved): calls `pollBuildStatus()` and `getBuildLog()`
- Returns last 4000 chars of log in `logFragment`
- `state` mapped from Jenkins result string: SUCCESS→SUCCESS, FAILURE→FAILURE, ABORTED→ABORTED, null→RUNNING

#### `GET /api/tag/next?repo=<name>[&jenkinsJob=<path>]`
**`TagController`** — suggests the next tag name and optionally returns the last deployed tag from Jenkins.
1. Calls `github.getLatestTag(repo)` to get the most recent GitHub release tag
2. Calls `tagGen.computeNextTag(latestTag)` to compute the next value
3. If `jenkinsJob` is provided, calls `jenkins.getLastDeployedBranch(jenkinsJob)` to get the `git_branch` value from the last successful build
4. Returns 200 with `{tagName: "v1.0.1rc1", previousTag: "v1.0.0", lastDeployedTag: "v1.0.0rc1"}` (`lastDeployedTag` is `null` if no `jenkinsJob` was given or no successful build exists)

#### `GET /api/jenkins/categories`
**`JenkinsConfigController`** — lists all saved Jenkins category names.
Returns `{categories: [...]}`.

#### `POST /api/jenkins/categories`
**`JenkinsConfigController`** — saves a new category.
Body: `{name: "my-category"}`. Idempotent — no error if already exists.

#### `GET /api/jenkins/service-names?category=<name>`
**`JenkinsConfigController`** — lists all service names for a category.
`@Validated` class + `@NotBlank` param — returns 400 if `category` is missing/blank.

#### `POST /api/jenkins/service-names`
**`JenkinsConfigController`** — saves a service name under a category.
Body: `{category: "team", name: "my-service"}`. Idempotent.

#### `POST /api/jira/comment`
**`JiraController`** — posts a comment to Jira.
1. Calls `jira.addComment(issueKey, text)` — backend converts to ADF
2. Returns 200 with `{success:true, message:"Comment posted"}`

#### `POST /api/deploy/all`
**`DeployController`** — launches a full stage-aware deployment asynchronously.
- Returns **202 Accepted** immediately with `{message:"Deploy All started"}`
- Runs orchestration on a **Java 21 virtual thread** (`Thread.ofVirtual().start(...)`)
- Currently uses `NoOpCallback` (SSE/WebSocket callback for real-time updates is a future iteration)
- State updates are visible through polling individual endpoints

#### `GET /api/log?lines=200`
**`LogController`** — returns last N lines of `logs/deploymate.log` as a JSON string array.
- Returns empty array if log file doesn't exist yet
- Default: 200 lines; configurable via `lines` query param

#### `GET /**` (SPA fallback)
**`SpaController`** — forwards all non-API, non-static-file paths to `index.html`.
```java
@GetMapping(value = {"/{path:[^\\.]*}", "/{path:^(?!api).*$}/**/{subpath:[^\\.]*}"})
public String redirect() { return "forward:/index.html"; }
```

### 6.7 Logging System

**File location:** `logs/deploymate.log` — relative to the working directory (project root or `/app` in Docker). Append-only; never truncated by Spring Boot.

**Log pattern (file):**
```
[2026-05-27T14:01:55.012Z] [INFO   ] [auth-service        ] [Service   ] Merging PROJ-123 → env/staging
```

**MDC keys:** `service` (display name or "SYSTEM"), `stage` ("Stage 1", "Service", "—")

**Session separator** (written by `OrchestratorService.deployAll`):
```
═══ NEW DEPLOY SESSION ═══ Ticket: PROJ-123
```

Log levels:
- `INFO` — normal steps (merging, tagging, pipeline started, pipeline success)
- `WARN` — non-fatal issues
- `ERROR` — conflicts, failures, errors
- `DEBUG` — detailed internal state (com.deploymate package only)

### 6.8 Orchestration Engine

**`service/OrchestratorService.java`** — the core deployment logic.

#### `StepCallback` interface
```java
public interface StepCallback {
    void onMergeState(String id, String state, String sha, String errorMessage);
    void onTagState(String id, String state, String releaseUrl, String errorMessage);
    void onPipelineState(String id, String state, String buildUrl, Integer buildNumber, String errorMessage);
    void onLog(String level, String service, String stage, String message);
}
```
Currently wired to `NoOpCallback` in `DeployController`. Future: SSE.

#### `mergeOnly(row, ticket, stageLabel, cb) → boolean`
1. If `!steps.mergeBranch` → `onMergeState(SKIPPED)` → return `true`
2. `verifyBranch()` → if not found: `onMergeState(FAILED)` → return `false`
3. `onMergeState(RUNNING)` → call `mergeBranch()`
4. If conflict: `onMergeState(CONFLICT)` + Jira comment → return `false`
5. Success: `onMergeState(DONE)` + Jira comment → return `true`

#### `tagOnly(row, ticket, stageLabel, cb) → boolean`
1. If `!steps.createTag` → `onTagState(SKIPPED)` → return `true`
2. `onTagState(RUNNING)` → `getBranchSha()` → `createTagAndPreRelease()`
3. Success: `onTagState(DONE)` + Jira comment → return `true`
4. Exception: `onTagState(FAILED)` → return `false`

#### `pipelineOnly(row, ticket, stageLabel, cb) → boolean`
- If `!steps.triggerPipeline` → `onPipelineState(SKIPPED)` → return `true`
- `gitBranch` is determined by **`row.type()`** (not `steps.createTag`):
  - `SERVICE` → `gitBranch = row.tagName()` (if blank → `onPipelineState(FAILED)` + return `false`)
  - `SDK` → `gitBranch = "origin/" + row.targetBranch()`
1. `onPipelineState(RUNNING)` → `triggerBuild(jenkinsJob, gitBranch)` → get `queueItemUrl`
2. Poll `pollQueueItem()` every 3s until build URL is assigned
3. `onPipelineState(RUNNING, buildUrl, buildNumber)` + Jira comment
4. Poll `pollBuildStatus()` every 5s until result is non-null
5. `SUCCESS` → `onPipelineState(DONE)` + Jira → return `true`
6. Other result → `onPipelineState(FAILED)` + Jira → return `false`

#### `deployAllSteps(row, ticket, stageLabel, currentMerge, currentTag, currentPipeline, cb) → boolean`
Idempotent: skips steps already in `DONE` or `SKIPPED` state.

#### `deployAll(rows, ticket, cb)`
Unified stage-ordered deployment (all row types use stage number):
1. Writes session separator to log
2. Groups **all rows** by stage number, sorts by stage — `type` (SDK/SERVICE) does not affect order
3. For each stage: creates `FixedThreadPool(stageRows.size())`, runs `deployAllSteps` via `CompletableFuture.supplyAsync`, awaits all futures
4. If any stage has failures → logs error → **returns early** (halts)
5. Uses `CompletableFuture.join()` to wait for all

---

## 7. Frontend — React + Vite

### 7.1 Entry Point & Root Component

**`src/main.tsx`** — React StrictMode root, mounts `<App />`.

**`src/App.tsx`** — wraps `<Providers>` around `<DeploymentDashboard />`.

**`src/components/providers.tsx`** — wraps children with `QueryClientProvider` (TanStack Query) and `<Toaster />` (Sonner).

**`src/components/DeploymentDashboard.tsx`** — full page layout:
- Sticky header with `<Rocket />` icon + `<DeployAllButton />`
- `<GlobalConfig />` card
- `<StageVisualizer />` (only when rows.length > 0)
- `<ServiceTable />` (renders all `<ServiceRow />` cards)
- `<ActivityLog />` panel

### 7.2 TypeScript Types

**`src/types/index.ts`** — all types. **Frontend ↔ Backend must stay in sync on all `*Request` and `*Response` types.**

```typescript
type ServiceType = 'SDK' | 'SERVICE';

type StepState = 'IDLE' | 'SKIPPED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CONFLICT';

type OverallState =
  | 'IDLE' | 'MERGING' | 'CONFLICT' | 'MERGED'
  | 'TAGGING' | 'TAGGED'
  | 'PIPELINE_QUEUED' | 'PIPELINE_RUNNING'
  | 'DONE' | 'FAILED';

type JenkinsEnvMode = 'type' | 'env';
// 'type' = SDK→dev, SERVICE→staging (auto); 'env' = explicit env field

interface ServiceRow {
  id: string;              // UUID (client-generated)
  name: string;            // Human-readable display name
  repo: string;            // GitHub repo name (exact)
  type: ServiceType;       // affects default steps and display only
  stage: number;           // 1-based; controls execution order for all row types
  sourceBranch: string;    // Merge FROM
  targetBranch: string;    // Merge INTO
  // Jenkins URL builder fields (compute jenkinsJob = category/env/serviceName)
  jenkinsJob: string;      // computed: e.g. "team/staging/my-service"
  jenkinsCategory: string;     // e.g. "team"
  jenkinsEnvMode: JenkinsEnvMode;
  jenkinsEnv: string;          // explicit env when mode='env'
  jenkinsServiceName: string;  // e.g. "my-service"
  tagName: string;         // Fetched from GitHub (computeNextTag) or manually entered; used when steps.createTag=true
  steps: ServiceRowSteps;  // per-step toggles (replaces skipMerge + updateJira)

  // Per-step states
  mergeState:    StepState;
  tagState:      StepState;
  pipelineState: StepState;

  // Step outputs
  mergeCommitSha: string | null;
  tagReleaseUrl:  string | null;
  buildUrl:       string | null;
  buildNumber:    number | null;
  buildLog:       string | null;
  errorMessage:   string | null;
}

interface GlobalConfig {
  ticket:              string;  // e.g. "PROJ-123"
  defaultSourceBranch: string;
  defaultTargetBranch: string;
}

interface ActivityLogEntry {
  id:        string;  // UUID
  timestamp: string;  // ISO 8601
  level:     'INFO' | 'WARN' | 'ERROR' | 'SUCCESS';
  service:   string;  // display name or "SYSTEM"
  stage:     string;  // "Stage 1", "Service", "SYSTEM"
  message:   string;
}

// API shapes (must match backend DTOs exactly):
// MergeRequest, MergeResponse, TagRequest, TagResponse
// PipelineTriggerRequest: {jenkinsJob, gitBranch}
// PipelineTriggerResponse
// PipelineStatusResponse (BuildState: 'QUEUED'|'RUNNING'|'SUCCESS'|'FAILURE'|'ABORTED'|'UNKNOWN')
// ServiceRowDto, ApiError
```

**`computeOverallState(row: ServiceRow): OverallState`** — derives a single display state from the three independent step states. Priority order:
1. `mergeState === CONFLICT` → `CONFLICT`
2. Any step `FAILED` → `FAILED`
3. `pipelineState === RUNNING` → `PIPELINE_RUNNING`
4. `pipelineState === DONE` → `DONE`
5. SERVICE: `tagState === RUNNING` → `TAGGING`, `tagState === DONE` → `TAGGED`
6. `mergeState === RUNNING` → `MERGING`
7. `mergeState === DONE|SKIPPED` → `MERGED`
8. Default → `IDLE`

### 7.3 Zustand Store

**`src/hooks/useDeployStore.ts`** — Zustand store with Immer middleware.

State shape:
```typescript
interface DeployStore {
  globalConfig: GlobalConfig;
  rows:         ServiceRow[];
  activityLog:  ActivityLogEntry[];

  setGlobalConfig(patch: Partial<GlobalConfig>): void;
  addRow(): void;           // Creates blankRow using current globalConfig defaults
  removeRow(id: string): void;
  updateRow(id: string, patch: Partial<ServiceRow>): void;
    // Side effects in updateRow:
    //   - When repo changes on a SERVICE row → auto-generates tagName
    //   - When type changes to SERVICE and repo is set but tagName empty → auto-generates tagName
  setStepState(id, step: 'merge'|'tag'|'pipeline', state, extra?): void;
  appendLog(entry: Omit<ActivityLogEntry, 'id'|'timestamp'>): void;
  resetRow(id): void;       // Resets all step states to IDLE, clears outputs
  resetAll(): void;         // Resets all rows, clears activityLog
}
```

`blankRow(globalConfig)` defaults: `type:'SDK'`, `stage:1`, `sourceBranch: defaultSourceBranch || ticket`, `targetBranch: defaultTargetBranch || 'env/staging'`, `jenkinsCategory:''`, `jenkinsEnvMode:'type'`, `jenkinsEnv:'dev'`, `jenkinsServiceName:''`, all states `IDLE`, all outputs `null`.

`buildJenkinsJob(category, env, serviceName)` helper returns `"${category}/${env}/${serviceName}"` — `JenkinsService.triggerBuild` converts this to `/job/a/job/b/job/c` internally.

Side effects in `updateRow`:
- `repo` changes on a SERVICE row with `createTag=true` → calls `apiGetNextTag(repo)` asynchronously, then calls `updateRow(id, {tagName})` with the result (Immer draft is sealed before the promise resolves, so the async set is done outside the draft)
- `type` changes to SERVICE + repo set → triggers same async tag fetch
- `jenkinsCategory/jenkinsEnvMode/jenkinsEnv/jenkinsServiceName` changes → recomputes `jenkinsJob`

### 7.4 API Layer

**`src/lib/api.ts`** — typed fetch wrappers. All paths relative to `/api`.

| Function | Method | Path | Request | Response |
|----------|--------|------|---------|----------|
| `apiBranchMerge` | POST | `/merge` | `MergeRequest` | `MergeResponse` |
| `apiCreateTag` | POST | `/tag` | `TagRequest` | `TagResponse` |
| `apiTriggerPipeline` | POST | `/pipeline/trigger` | `PipelineTriggerRequest` | `PipelineTriggerResponse` |
| `apiPipelineStatus` | GET | `/pipeline/status?[queueItemUrl][&buildUrl]` | — | `PipelineStatusResponse` |
| `apiJiraComment` | POST | `/jira/comment` | `{issueKey, text}` | void |
| `apiDeployAll` | POST | `/deploy/all` | `{ticket, rows}` | void |
| `apiGetNextTag` | GET | `/tag/next?repo=...&jenkinsJob=...` | — | `{tagName, previousTag, lastDeployedTag}` (`jenkinsJob` optional; `lastDeployedTag` is `null` when omitted or no successful build) |
| `apiGetCategories` | GET | `/jenkins/categories` | — | `{categories: string[]}` |
| `apiSaveCategory` | POST | `/jenkins/categories` | `{name}` | void |
| `apiGetServiceNames` | GET | `/jenkins/service-names?category=...` | — | `{names: string[]}` |
| `apiSaveServiceName` | POST | `/jenkins/service-names` | `{category, name}` | void |
| `apiGetLog` | GET | `/log?lines=200` | — | `string[]` |

**Special case:** `apiBranchMerge` does NOT use the generic `post()` helper because HTTP 409 (conflict) is a **valid business response** that must not throw — it reads the body and returns it as `MergeResponse`.

### 7.5 Hooks

#### `src/hooks/useDeployActions.ts`

All hooks follow the same contract: `{ run: () => void, running: boolean }`.

**`useMergeAction(row)`** — `org` removed; backend reads org from AppProperties
1. Sets `mergeState = RUNNING`, appends log
2. Calls `apiBranchMerge()` (no `org` field in payload)
3. On conflict: `mergeState = CONFLICT`, toast.warning
4. On success: `mergeState = DONE`, stores `mergeCommitSha`, toast.success
5. On error: `mergeState = FAILED`, toast.error

**`useTagAction(row)`** — `org` removed; backend reads org from AppProperties
1. Sets `tagState = RUNNING`, appends log
2. Calls `apiCreateTag()` (no `org` field in payload)
3. On success: `tagState = DONE`, stores `tagReleaseUrl`, toast.success
4. On error: `tagState = FAILED`, toast.error

**`usePipelineAction(row)`**
- Computes `gitBranch` by **`row.type`**:
  - `SERVICE` → `gitBranch = row.tagName`; if empty, immediately sets `pipelineState = FAILED` with a toast error (no API call made)
  - `SDK` → `gitBranch = "origin/" + row.targetBranch`
1. Sets `pipelineState = RUNNING`, appends log
2. Calls `apiTriggerPipeline({ jenkinsJob, gitBranch })`
3. Polls `apiPipelineStatus()` every **5000ms** until state is not QUEUED/RUNNING
4. When `buildUrl` first appears: stores it + `buildNumber`, appends log
5. `SUCCESS` → `pipelineState = DONE`, stores `buildLog`, toast.success
6. Other → `pipelineState = FAILED`, toast.error

**`useAllStepsAction(row)`** — `org` parameter removed
Composes the three above hooks. Runs steps in sequence, checking fresh store state between each:
1. Skip merge if `steps.mergeBranch=false` OR `mergeState === 'DONE'`
2. Skip tag if `steps.createTag=false` OR `tagState === 'DONE'`; if tag fails, returns early (pipeline does not run)
3. Skip pipeline if `pipelineState === 'DONE'`
4. Reads **fresh** row state from `useDeployStore.getState()` after each step to check if it succeeded

### 7.6 Utility Helpers

**`src/lib/utils.ts`**

```typescript
cn(...inputs: ClassValue[]): string        // clsx + twMerge
shortSha(sha: string | null): string       // returns first 7 chars or 'n/a'
formatTimestamp(iso: string): string       // HH:mm:ss in 24h format
generateTagName(repo, prefix?, sequence?): string
  // Format: {prefix}-{YYYYMMDD}-{repo}-{seq:03d}
  // Default: prefix='env-stag', sequence=1
  // Example: 'env-stag-20260601-payment-service-001'
```

### 7.7 Components

#### `DeploymentDashboard.tsx`
Root layout. `max-w-6xl mx-auto px-6 py-6`. Sticky header with blur backdrop. Shows `<StageVisualizer>` only when `rows.length > 0`.

#### `GlobalConfig.tsx`
Card with 3 inputs in a row:
- **Ticket #** (e.g. `PROJ-123`) — onChange auto-fills `defaultSourceBranch`
- **Default Source Branch** — text input
- **Default Target Branch** — text input (default: `env/staging`)

#### `ServiceTable.tsx`
Renders `<AnimatePresence>` around a list of `<ServiceRow>` components. Shows `<EmptyState>` when rows are empty. Has `[+ Add Service]` button at bottom (calls `addRow()`).

#### `ServiceRow.tsx`
Full expandable card per service row. Key features:
- Header: index pill + Name input + Repo input + type badge + `<StatusBadge>` + expand/collapse chevron + delete button
- Expanded body (grid layout): Type select, Stage input, Source Branch, Target Branch, **Jenkins URL Builder** (category combobox + env mode toggle + service name combobox → computes `jenkinsJob`), Tag Name field with refresh button (SERVICE + createTag), step toggles (Merge/Tag/Pipeline/Jira switches)
- Step badges row: `<StepBadge step="Merge" ...>`, `<StepBadge step="Tag" ...>` (SERVICE only), `<StepBadge step="Pipeline" ...>`, `<BuildLogButton>`
- Action buttons (conditional):
  - `[Merge]` — hidden if `skipMerge=true` OR `mergeState === 'DONE'`
  - `[Tag]` — SERVICE only, `!skipMerge`, `mergeState === 'DONE'`, `tagState !== 'DONE'`
  - `[Pipeline]` — shown when `pipelineState !== 'DONE'`
  - `[Run all]` — shown when row is not `overall === 'DONE'`
- Conflict panel shown when `mergeState === 'CONFLICT'`
- `ORG` is read from `window.__ORG__` (future: move to Zustand `globalConfig`)
- framer-motion: row enters with `opacity:0, y:-8` → `opacity:1, y:0`

#### `StatusBadge.tsx`
Shows overall row status using `computeOverallState()`. Color mapping:

| State | Classes |
|-------|---------|
| IDLE | `bg-slate-800 text-slate-400` |
| MERGING / TAGGING / PIPELINE_RUNNING | `bg-blue-950 text-blue-300 animate-pulse` |
| MERGED / TAGGED / PIPELINE_QUEUED | `bg-sky-950 text-sky-300` |
| CONFLICT | `bg-orange-950 text-orange-300` |
| DONE | `bg-emerald-950 text-emerald-300` |
| FAILED | `bg-rose-950 text-rose-300` |

#### `StepBadge.tsx`
Per-step pill badge with step name label. Props: `step` (string), `state` (StepState), `detail` (optional string — e.g. short SHA or `#42`).

| StepState | Classes | Label |
|-----------|---------|-------|
| IDLE | `bg-slate-800 text-slate-400` | "Idle" |
| SKIPPED | `bg-slate-700 text-slate-400 line-through` | "Skip ↷" |
| RUNNING | `bg-blue-950 text-blue-300 animate-pulse` | "Running..." |
| DONE | `bg-emerald-950 text-emerald-300` | "Done ✓" |
| FAILED | `bg-rose-950 text-rose-300` | "Failed ✗" |
| CONFLICT | `bg-orange-950 text-orange-300` | "Conflict ⚠" |

#### `StageVisualizer.tsx`
Horizontal animated pill flow showing stage groups.
```
[Stage 1: SDK Core ✅] → [Stage 2: Auth SDK ⟳ ‖ Utils SDK ⟳] → [Services: API Gateway ◌]
```
Uses `framer-motion` `AnimatePresence` — pills animate in/out. Pill color derived from worst state in the group (FAILED > CONFLICT > RUNNING > DONE > IDLE).

#### `ConflictPanel.tsx`
Orange warning panel shown when `mergeState === 'CONFLICT'`. Shows repo name, source→target branch. Buttons: `[Retry Merge ↺]` (calls `resetRow()` then re-runs `useMergeAction`) and `[Dismiss]`.

#### `ActivityLog.tsx`
Fixed-height (`h-64`) scrollable panel at page bottom. Auto-scrolls to bottom on new entries. Header: "Activity Log" + `[Clear]` button + `[Open Log File]` button (calls `apiGetLog()`). Uses display **name** (not repo) in log entries. Color codes by level: INFO=`text-slate-300`, WARN=`text-amber-400`, ERROR=`text-rose-400`, SUCCESS=`text-emerald-400`.

#### `BuildLogDrawer.tsx`
`<BuildLogButton>` is shown in the step states row. Opens a `<Dialog>` showing the last `buildLog` fragment from Jenkins. Monospace font.

#### `DeployAllButton.tsx`
Shown in the sticky header. Calls `apiDeployAll()` with all current rows (mapped to `ServiceRowDto`). Shows spinner + disabled state while running. Validates that no required fields are empty before submitting.

#### `EmptyState.tsx`
Shown when `rows.length === 0`. Icon + heading + "Add your first service" CTA button.

### 7.8 Vite Config

**`frontend/vite.config.ts`**:
```typescript
{
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
  },
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  }
}
```

**Path alias:** `@/` → `./src/` (used throughout all imports).

---

## 8. MCP Server — Claude Integration

**`mcp/server.ts`** — Node.js + TypeScript MCP server using stdio transport.

### Configuration

```typescript
const BASE = process.env["DEPLOYMATE_API_URL"] ?? "http://localhost:8080";
const ORG  = process.env["GITHUB_ORG"]         ?? "";
```

The MCP server holds **NO credentials**. It only calls `http://localhost:8080/api/...`. The Spring Boot backend handles all authentication.

### Rate Limiting

Per-tool rolling 60-second window. Default: 10 calls/minute. Exceptions:
- `get_pipeline_status`: 30/min
- `get_log`: 30/min
- `add_jira_comment`: 20/min
- `deploy_all`: 5/min

### Credential Sanitizer

All tool responses pass through `sanitize()` before being returned to Claude:
- Redacts GitHub PATs (`ghp_*`, `ghs_*`)
- Redacts Basic auth headers
- Redacts Bearer tokens
- Redacts URL passwords

### Rate Limiting (updated)

- `get_pipeline_status`, `get_log`: 30/min
- `add_jira_comment`: 20/min
- `deploy_all`: 5/min
- all others: 10/min

### 9 MCP Tools

#### Atomic tools (one backend endpoint each)

**`merge_branch`** — verify branch + merge source → target. Calls `POST /api/merge`.
```
args: repo, sourceBranch, targetBranch (default: "env/staging"), ticket (optional)
note: org is NOT sent — backend reads it from AppProperties
```

**`create_tag`** — create a GitHub pre-release tag. Calls `POST /api/tag`.
```
args: repo, tagName, targetBranch (default: "env/staging"), ticket (optional)
note: org is NOT sent — backend reads it from AppProperties
```

**`trigger_pipeline`** — fire a Jenkins build. Calls `POST /api/pipeline/trigger`.
```
args: jenkinsJob, gitBranch (the exact value for the git_branch Jenkins parameter)
note: caller must pass the correct value:
  SERVICE → gitBranch = tagName (pre-release tag)
  SDK     → gitBranch = "origin/<targetBranch>"
```

**`get_next_tag`** — suggest next tag + optionally query last Jenkins deploy. Calls `GET /api/tag/next`.
```
args: repo, jenkinsJob (optional — when provided, also returns lastDeployedTag from Jenkins)
returns: {tagName, previousTag, lastDeployedTag}
  tagName         — suggested next tag (computed from latest GitHub release)
  previousTag     — most recent GitHub release tag
  lastDeployedTag — git_branch value from last successful Jenkins build (null if no jenkinsJob or no build)
```

**`get_jenkins_categories`** — list saved Jenkins categories. Calls `GET /api/jenkins/categories`.
```
args: none
```

**`get_pipeline_status`** — poll a build. Calls `GET /api/pipeline/status`.
```
args: queueItemUrl (optional URL), buildUrl (optional URL)
```

**`add_jira_comment`** — post a comment. Calls `POST /api/jira/comment`.
```
args: issueKey (^[A-Z]{1,20}-[0-9]{1,10}$), text (max 5000 chars)
```

**`get_log`** — tail the log file. Calls `GET /api/log`.
```
args: lines (1–500, default 50), filter (optional substring)
```

#### Batch tool

**`deploy_all`** — submit a full batch to the backend orchestrator.
```
args: ticket (optional),
      rows: Array<{id, name, repo, type, stage, sourceBranch, targetBranch, jenkinsJob, tagName,
                   steps: {mergeBranch, createTag, triggerPipeline, updateJira}}>
note: type determines git_branch strategy — SERVICE uses tagName, SDK uses origin/targetBranch
      tagName is required (non-blank) for SERVICE rows; pipeline step fails immediately if missing
```
Returns immediately with accepted message. Orchestrator handles stage ordering and parallel execution.

### Register with Claude Code

```json
{
  "mcpServers": {
    "deploymate": {
      "command": "node",
      "args": ["/absolute/path/to/deploymate/mcp/dist/server.js"]
    }
  }
}
```

Or for development (no build step):
```json
{
  "mcpServers": {
    "deploymate": {
      "command": "npx",
      "args": ["tsx", "/absolute/path/to/deploymate/mcp/server.ts"]
    }
  }
}
```

---

## 9. Deployment Flow Logic (Complete)

### Parameter Logic

The Jenkins parameter key is **always** `git_branch`. The value is determined by **`row.type`**, not by `steps.createTag`:

| Row Type | `git_branch` value |
|---|---|
| `SDK` | `origin/<targetBranch>` (always — no tag is ever used for SDK) |
| `SERVICE` | `<tagName>` (the pre-release tag — **must be non-blank**; pipeline step fails immediately if empty) |

`steps.createTag` controls whether the tag *creation step runs* as part of the deployment — it does not affect what value Jenkins receives. A SERVICE row with `createTag=false` must still have a `tagName` set manually before the pipeline step.

### Tag Auto-generation

When a SERVICE row's `repo` field changes in the UI, the store calls `apiGetNextTag(repo)` and sets `tagName` from the result. When the user clicks **Fetch Info**, `apiGetNextTag(repo, jenkinsJob)` is called — passing `jenkinsJob` fetches both the latest GitHub tag and the `git_branch` value from the last successful Jenkins build, displayed as two separate lines below the tag input. The tag name is editable. `TagGeneratorService.computeNextTag(lastTag)` rules:
- `v1.0.0rc1` → `v1.0.0rc2` (increment rc)
- `v1.0.0-staging1` → `v1.0.0-staging2` (increment staging number)
- `v1.0.0` → `v1.0.1rc1` (semver patch bump + rc1)
- No previous tag → `v1.0.0rc1`

Before creating, `GitHubService.createTagAndPreRelease` calls `tagExists()` and throws `INVALID_INPUT` if the tag already exists.

### Complete Step Flow Diagram

```
User clicks [Deploy All]
           │
           ▼
   POST /api/deploy/all → 202 Accepted
           │
           ▼ (virtual thread)
   OrchestratorService.deployAll()
           │
           ├─── For each SDK Stage (sorted by stage number, sequential)
           │    ├─── [Stage 1 SDKs, parallel]
           │    │    ├─── mergeOnly() → skipMerge? SKIPPED : verify+merge
           │    │    │    ├── CONFLICT? → halt Deploy All
           │    │    │    └── DONE → continue
           │    │    └─── pipelineOnly() → trigger BRANCH=targetBranch
           │    │         └── poll every 5s → DONE/FAILED
           │    │
           │    ├─── All Stage 1 done? → continue to Stage 2
           │    │    Any Stage 1 failed? → HALT (no Stage 2)
           │    │
           │    └─── [Stage 2 SDKs, parallel] ...
           │
           └─── [All Services, parallel, after ALL SDK stages]
                ├─── mergeOnly() → skipMerge? SKIPPED : verify+merge
                │    ├── CONFLICT? → row stays broken, other services continue
                │    └── DONE → continue
                ├─── tagOnly() → skipMerge? SKIPPED : getBranchSha + createTagAndPreRelease
                └─── pipelineOnly() → skipMerge? BRANCH : TAG param
```

### Idempotency

`deployAllSteps()` checks current state before running each step:
- `currentMergeState === 'DONE' || 'SKIPPED'` → skip merge
- `currentTagState === 'DONE' || 'SKIPPED'` → skip tag
- `currentPipelineState === 'DONE'` → skip pipeline

This allows re-running Deploy All after partial failures without duplicating completed steps.

### Jenkins CSRF Crumb

Every Jenkins POST requires a CSRF crumb:
1. GET `{JENKINS_URL}/crumbIssuer/api/json` → `{crumbRequestField, crumb}`
2. Add header `{crumbRequestField}: {crumb}` to the POST
3. `getCrumb()` is called inside `triggerBuild()` every time

### GitHub API Calls

All GitHub calls use:
- `Authorization: Bearer {GITHUB_TOKEN}`
- `Accept: application/vnd.github+json`
- `X-GitHub-Api-Version: 2022-11-28`

Merge commit message format:
```
chore: merge {sourceBranch} into {targetBranch} for {ticket}
```

Pre-release creation body:
```json
{
  "tag_name": "{tagName}",
  "name": "{tagName} (staging pre-release)",
  "body": "Pre-release for staging deployment. Ticket: {ticket}",
  "prerelease": true,
  "draft": false
}
```

### Jira ADF Format

Jira Cloud REST API v3 requires **Atlassian Document Format (ADF)** for comment bodies. The backend's `toAdf(text)` method generates:
```json
{
  "type": "doc",
  "version": 1,
  "content": [{
    "type": "paragraph",
    "content": [{ "type": "text", "text": "..." }]
  }]
}
```

Jira comment content by step:
- Merge conflict: `⚠️ Merge conflict in \`{repo}\` (...). Manual resolution required.`
- Merge success: `🔀 \`{repo}\` merged \`{src}\` → \`{target}\` (SHA: \`{sha}\`)`
- Tag created: `🏷️ Pre-release tag \`{tagName}\` created for \`{repo}\`. [View release]({url})`
- Build started: `🚀 Jenkins build [#{n}]({url}) started for \`{repo}\``
- Build success: `✅ Jenkins build [#{n}]({url}) passed for \`{repo}\``
- Build failure: `❌ Jenkins build [#{n}]({url}) **{result}** for \`{repo}\``

---

## 10. UI Design System

### Color Palette — Dark Mode Only

| Element | Tailwind |
|---|---|
| Page background | `bg-slate-950` |
| Card / surface | `bg-slate-900` |
| Hover row | `bg-slate-800/50` |
| Border | `border-slate-800` |
| Primary text | `text-slate-100` |
| Muted text | `text-slate-400` |
| Input | `bg-slate-800 border-slate-700 text-slate-100 focus:ring-indigo-500` |
| Primary button | `bg-indigo-600 hover:bg-indigo-500 text-white` |
| All Steps button | `bg-indigo-700 hover:bg-indigo-600 text-white` |
| Destructive button | hover `text-rose-400` |
| Skip Merge ON | `text-amber-400` label annotation |
| Log panel | `bg-slate-950 font-mono text-sm` |

### Fonts

- Body: Inter (system-ui fallback)
- Code/log: JetBrains Mono (monospace fallback)

### Activity Log Level Colors

| Level | Class |
|---|---|
| INFO | `text-slate-300` |
| WARN | `text-amber-400` |
| ERROR | `text-rose-400` |
| SUCCESS | `text-emerald-400` |

### Responsive Design

- **Desktop (≥1024px):** Full expanded rows with all fields visible
- **Mobile (<768px):** Collapsed header view only, full details in expanded section
- Deploy All button: `sticky top-0` in header

### Accessibility

- Every interactive element has an ARIA label or associated `<label>`
- Status is never conveyed by color alone — every badge includes text
- Icon-only buttons have `aria-label`
- Focus rings: `focus-visible:ring-2 focus-visible:ring-indigo-500`

---

## 11. Testing

### Backend Tests — 109 tests (all passing)

**Run:** `JAVA_HOME=~/.sdkman/candidates/java/21.0.11-amzn ./mvnw test`

> **Java version note:** The project requires Java 21. If your shell default is different, set `JAVA_HOME` to the 21 JDK. A `.sdkmanrc` file is included — run `sdk env` in the repo root to auto-switch.

#### Service Unit Tests (MockWebServer)

Tests use `OkHttpClient` with a URL-rewriting interceptor to redirect real API hostnames to `MockWebServer`'s localhost URL.

**`GitHubServiceTest`** (16 tests):
- `verifyBranch`: 200→true, 404→false, 500→throws NETWORK
- `mergeBranch`: 201→success+sha, 204→success (already up-to-date), 409→conflict, 500→throws, ticket in commit message
- `getBranchSha`: 200→sha, 404→throws NOT_FOUND
- `getLatestTag`: 200 with release→tagName, empty body→empty
- `tagExists`: 200→true, 404→false
- `createTagAndPreRelease`: first enqueues 404 (tagExists=false), then 201+201→url; if tagExists=200→throws INVALID_INPUT; verifies 3 requests total

**`JenkinsServiceTest`** (15 tests): crumb fetch, trigger build with `git_branch` parameter (Location header), pollQueueItem, pollBuildStatus, getBuildLog; `getLastDeployedBranch` — returns tag when param present, empty on 404, empty when git_branch param absent

**`JiraServiceTest`** (tests): addComment success, addComment failure→throws NETWORK

**`OrchestratorServiceTest`** (19 tests): mergeOnly skip, mergeOnly conflict halts, deployAll stage ordering; pipelineOnly — SERVICE uses tagName regardless of createTag flag, SERVICE with empty tagName returns false, SDK uses `origin/branch`

#### Controller Integration Tests (MockMvc + @WebMvcTest)

**`MergeControllerTest`** (7 tests):
- 200 on success (verifyBranch=true, mergeBranch→success)
- 409 on conflict
- 400 when branch not found
- 400 when repo is empty
- 400 when repo has path traversal (`../etc/passwd`)
- 400 when body is missing
- 400 when ticket format is invalid

**`TagControllerTest`** (8 tests): 200 success, 400 validation; `GET /api/tag/next` — 200 with computed tag + null lastDeployedTag when no jenkinsJob, 200 with lastDeployedTag when jenkinsJob provided, null lastDeployedTag when job has no successful build, 400 missing repo

**`PipelineControllerTest`** (10 tests): trigger 200 with `gitBranch`, status QUEUED/RUNNING/SUCCESS/FAILURE, 400 no params

**`JiraControllerTest`** (4 tests): 200 comment posted, 400 invalid issueKey

**`JenkinsConfigControllerTest`** (7 tests): get categories, save category, get service-names, save service-name, 400 missing category param

**`TagGeneratorServiceTest`** (13 parameterized tests): all rc/staging/semver/fallback rules, edge cases (rc9→rc10, no digits, missing prefix)

**`DeployControllerTest`** (5 tests): 202 accepted, 400 empty rows

### Frontend Type Check

```bash
cd frontend && npm run typecheck
# Expected: no output, exit code 0
```

### Smoke Test (live server required)

**`scripts/smoke-test.sh [BASE_URL]`** — 18 checks using `curl` and `jq`:

1. `GET /` returns 200 (SPA root)
2. `GET /some/deep/route` returns 200 (SPA fallback)
3. `GET /api/log?lines=5` returns 200 and JSON array
4. `POST /api/merge` with no body → 400
5. `POST /api/tag` with no body → 400
6. `POST /api/pipeline/trigger` with no body → 400
7. `POST /api/jira/comment` with no body → 400
8. `POST /api/deploy/all` with no body → 400
9. `POST /api/merge` with path-traversal repo → 400
10. `POST /api/merge` with blank repo → 400
11. `POST /api/tag` with invalid tagName chars → 400
12. 400 body contains `error` field
13. 400 body contains `message` field
14. `GET /api/pipeline/status` with no params → 400
15. (Plus 4 more checks on error response shape and status validation)

```bash
./scripts/smoke-test.sh                       # → http://localhost:8080
./scripts/smoke-test.sh http://other:8080     # custom URL
```

---

## 12. Docker & CI

### Dockerfile (3-stage)

**Stage 1 (`frontend-build`):** `node:22-alpine`
- `npm ci --prefer-offline` (cached layer)
- `npm run build` → output lands at `../backend/src/main/resources/static` (per `vite.config.ts` outDir)

**Stage 2 (`backend-build`):** `eclipse-temurin:21-jdk-alpine`
- Maven dependency caching layer
- Copies frontend build from Stage 1 into `./src/main/resources/static`
- `./mvnw package -DskipTests -q` → renames to `target/app.jar`

**Stage 3 (`runtime`):** `eclipse-temurin:21-jre-alpine`
- Non-root user: `deploy:deploy`
- JVM flags: `-XX:+UseZGC -XX:+ZGenerational -Xmx512m`
- `EXPOSE 8080`
- `ENTRYPOINT ["java", "-jar", "app.jar"]`

### docker-compose.yml

```yaml
services:
  deploymate:
    build: .
    image: deploymate:latest
    ports: ["8080:8080"]
    env_file: [.env]
    volumes:
      - deploymate_logs:/app/logs
      - deploymate_data:/app/data
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/ | grep -q 'DeployMate\\|html'"]
      interval: 30s, timeout: 10s, retries: 3, start_period: 45s
    restart: unless-stopped
    deploy:
      resources:
        limits: {memory: 768M, cpus: "1.0"}
        reservations: {memory: 256M}

volumes:
  deploymate_logs:
    driver: local
  deploymate_data:
    driver: local
```

**Note:** The MCP server is NOT containerised — it runs as a local stdio process.

### Build and run commands

```bash
# First time / after code changes:
docker compose up --build

# Subsequent runs (no code changes):
docker compose up

# Stop:
docker compose down

# Stop and remove log volume:
docker compose down -v

# View live logs:
docker compose logs -f deploymate

# Read log file from container:
docker exec deploymate cat /app/logs/deploymate.log
```

---

## 13. Running Locally

### Prerequisites

| Tool | Min Version |
|---|---|
| Java JDK | 21 |
| Maven | 3.9 (or use `./mvnw` wrapper — no install needed) |
| Node.js | 20 LTS |
| npm | 9+ |

### Terminal 1 — Backend

```bash
cd backend
# Export all env vars (or use IDE .env support):
export GITHUB_TOKEN=ghp_...
export GITHUB_ORG=acme-corp
export JENKINS_URL=https://jenkins.acme.com
export JENKINS_USER=user
export JENKINS_TOKEN=token
export JIRA_URL=https://acme.atlassian.net
export JIRA_EMAIL=user@acme.com
export JIRA_TOKEN=token
# Or: export $(cat ../.env | xargs)

JAVA_HOME=~/.sdkman/candidates/java/21.0.11-amzn ./mvnw spring-boot:run
# → Spring Boot on :8080
# Or: run `sdk env` first (reads .sdkmanrc) then ./mvnw spring-boot:run
```

### Terminal 2 — Frontend

```bash
cd frontend
npm install   # first time only
npm run dev
# → Vite on :5173 (proxies /api → :8080)
```

Open `http://localhost:5173` for dev (hot reload).
Open `http://localhost:8080` for the production build.

### Terminal 3 — MCP Server (optional)

```bash
cd mcp
npm install   # first time only
npm run dev   # runs via tsx (no build needed)
# OR: npm run build && npm start
```

### Available npm scripts

#### Frontend (`frontend/`)
```bash
npm run dev        # Start Vite dev server
npm run build      # TypeScript check + Vite build → backend/static
npm run preview    # Preview production build
npm run typecheck  # tsc --noEmit
npm run lint       # ESLint with 0 warnings tolerance
npm run validate   # typecheck + lint
```

#### MCP (`mcp/`)
```bash
npm run dev        # tsx server.ts (no build, hot dev)
npm run build      # tsc → dist/
npm run start      # node dist/server.js
npm run typecheck  # tsc --noEmit
```

#### Backend (`backend/`)
```bash
./mvnw spring-boot:run   # Start with hot reload
./mvnw test              # Run all 109 tests (requires Java 21 — set JAVA_HOME)
./mvnw package           # Build fat jar (runs tests)
./mvnw package -DskipTests  # Build fat jar (skip tests)
```

---

## 14. Security Rules

### Credential isolation
- ALL credentials in `.env` / environment variables only — **never hardcoded**
- `AppProperties` is the **only** class that reads credentials. No `System.getenv()` anywhere else.
- **Never include credentials in API responses.** If external API auth fails → log server-side, return generic message to client.
- `.gitignore` includes: `.env`, `logs/`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `backend/src/main/resources/static/`

### Input validation

Every controller uses `@Valid` on `@RequestBody`. Regex enforcement:
- Repo names: `^[a-zA-Z0-9_.-]{1,100}$` — blocks path traversal
- Branch names: `^[a-zA-Z0-9/_.-]{1,255}$`
- Tag names: `^[a-zA-Z0-9._-]{1,128}$`
- Ticket keys: `^[A-Z]{1,20}-[0-9]{1,10}$`
- Jenkins job paths: `^[a-zA-Z0-9/_-]{1,200}$`

400 is returned for any validation failure with field-level error details.

### MCP security
- All MCP tool responses pass through `sanitize()` — redacts tokens, Basic/Bearer headers, URL passwords
- MCP server exposes NO `read_env`, `read_file`, or `get_config` tool
- Raw exception messages from external APIs are never forwarded to Claude

### Non-secrets on the frontend
- `ORG` is read from `window.__ORG__` — this is the GitHub org name (not a secret), currently defaulting to empty string
- All actual API calls go through Spring Boot — the browser never touches GitHub, Jenkins, or Jira directly

---

## 15. Code Quality Standards

### Backend (Java)

- **Java 21**: use records for DTOs, `var` for local variables where type is obvious
- **`@Valid` on all `@RequestBody`** controller parameters
- **Never `System.out.println`** — use `private static final Logger log = LoggerFactory.getLogger(ClassName.class)`
- **`@ConfigurationProperties`** for all config — no `@Value`
- **Services are stateless `@Service` singletons** — no mutable per-request state
- **`DeployException` (unchecked)** for domain errors — mapped in `GlobalExceptionHandler`
- **Separation:** `controller/` → HTTP parsing; `service/` → business logic; `dto/` → shapes; `model/` → domain errors; `config/` → properties

### Frontend (TypeScript)

- **Strict TypeScript**: `"strict": true` — never use `any`
- **Explicit return types** on all exported functions
- **`const` over `let`** everywhere
- **`interface` for object shapes**, `type` for unions
- **No `console.log` in production code**
- **All API calls via `/api/...`** — never talk to external services from browser
- **ESLint** with `@typescript-eslint/recommended`, `no-explicit-any` at `error` level

---

## 16. Implementation Status

| # | Part | Status | Commit |
|---|------|--------|--------|
| P1 | Monorepo scaffold — git init, .gitignore, README, directory structure | ✅ Done | cb6b72c |
| P2 | Backend skeleton — pom.xml, main app, AppProperties, GlobalExceptionHandler | ✅ Done | cb6b72c |
| P3 | Backend DTOs and models — all records, DeployException, ErrorCode | ✅ Done | cb6b72c |
| P4 | Backend external services — GitHubService, JenkinsService, JiraService, TagGeneratorService | ✅ Done | cb6b72c |
| P5 | Backend orchestration engine — OrchestratorService, LogService | ✅ Done | cb6b72c |
| P6 | Backend REST controllers — all 7 controllers | ✅ Done | cb6b72c |
| P7 | Backend unit tests — service layer | ✅ Done | 73/73 passing |
| P8 | Backend integration tests — controller layer (MockMvc) | ✅ Done | 73/73 passing |
| P9 | Frontend scaffold — Vite + React + Tailwind + shadcn + dependencies | ✅ Done | 69e6c50 |
| P10 | Frontend types, Zustand store, utility helpers | ✅ Done | 69e6c50 |
| P11 | Frontend components — all UI components | ✅ Done | 69e6c50 |
| P12 | Frontend API hooks — TanStack Query, deploy action functions | ✅ Done | useDeployActions.ts |
| P13 | MCP server — mcp/server.ts with all 6 tools | ✅ Done | d09dacd |
| P14 | Dockerfile + docker-compose | ✅ Done | d09dacd |
| P15 | End-to-end smoke test — scripts/smoke-test.sh (18 checks) | ✅ Done | d09dacd |
| P16 | Per-service step config (steps: {mergeBranch, createTag, triggerPipeline, updateJira}) | ✅ Done | — |
| P16 | Unified stage-based orchestration (type no longer controls order) | ✅ Done | — |
| P16 | MCP 9 tools: merge_branch, create_tag, trigger_pipeline, get_next_tag, get_jenkins_categories | ✅ Done | — |
| P17 | Jenkins URL builder — H2 DB, category/service comboboxes, env mode toggle | ✅ Done | — |
| P17 | git_branch Jenkins parameter (replaces BRANCH/TAG enum) | ✅ Done | — |
| P17 | Tag generation from GitHub latest tag (rc/staging/semver rules) | ✅ Done | — |
| P17 | GET /api/tag/next endpoint + frontend auto-fetch + refresh button | ✅ Done | — |
| P17 | JenkinsConfigController + JenkinsConfigService + H2 JPA entities | ✅ Done | — |
| P17 | Postman collection (postman_collection.json) — all 12 endpoints | ✅ Done | — |
| P17 | 103 backend tests passing | ✅ Done | — |

**All parts complete. Project is fully implemented.**

### Future / Not Yet Built
- SSE (Server-Sent Events) for real-time state updates from `OrchestratorService` to frontend — currently uses `NoOpCallback`. When added: replace `NoOpCallback` in `DeployController` and add an SSE endpoint.
- `ORG` in `ServiceRow.tsx` reads from `window.__ORG__` — should be moved to Zustand `globalConfig` as a configurable field in `GlobalConfig.tsx`.
- Smoke test script (`scripts/smoke-test.sh`) should be updated to test the new `steps` JSON shape and `gitBranch` in deploy/all and pipeline/trigger requests.

---

## 17. Troubleshooting

### "Application failed to start" in Docker

```bash
docker compose logs deploymate
```
Look for:
- `Caused by: java.lang.IllegalStateException: Invalid JENKINS_URL` → fix `JENKINS_URL` in `.env`
- `Field error ... on field 'github.token': rejected value []` → `GITHUB_TOKEN` is missing/empty in `.env`

### Merge conflict — what to do

1. UI shows orange conflict panel on the affected row
2. Resolve the conflict on GitHub (create a new merge commit or rebase the source branch)
3. Click `[Retry Merge ↺]` in the UI

### Jenkins 403

1. Check `JENKINS_USER` and `JENKINS_TOKEN` are correct
2. User needs **Build** permission on the job
3. Check HTTP vs HTTPS consistency

### Jenkins job 404

Jenkins job path format — NO leading slash, folders use `/`:
| Jenkins UI | Correct value |
|---|---|
| `my-job` | `my-job` |
| `Folder » my-job` | `Folder/my-job` |
| `Team » Backend » deploy` | `Team/Backend/deploy` |

### Jira comments not appearing

1. `[Update Jira]` toggle must be ON for the row
2. Ticket field at the top must be filled in (e.g. `PROJ-123`)
3. `JIRA_EMAIL` must match the account that owns the API token
4. `JIRA_URL` must have NO trailing slash
5. Jira failures do NOT stop the deployment — check activity log for warning

### Skip Merge toggle — use when

- Re-deploying without new code changes (branch already in desired state)
- Service row: note appears *"Pipeline will use BRANCH=\<targetBranch\>"*
- Source Branch and Tag fields are greyed out when Skip Merge is ON
- `mergeState` and `tagState` are immediately set to `SKIPPED`

### Port 8080 in use

```yaml
# docker-compose.yml:
ports:
  - "9090:8080"   # host:container
```
Then open `http://localhost:9090`.

### How to view logs

```bash
# Docker streaming:
docker compose logs -f deploymate

# Log file from container:
docker exec deploymate cat /app/logs/deploymate.log

# From UI:
# Activity Log panel (in-memory, current session only)
# GET /api/log?lines=200 (server-side persistent log)
```

### Building fails in Docker (frontend build not finding backend static dir)

The Vite `outDir` in `vite.config.ts` is `'../backend/src/main/resources/static'`. In the Docker build context, the frontend output lands at `/app/backend/src/main/resources/static`. Stage 2 then `COPY --from=frontend-build` that exact path. If this fails, verify the Dockerfile `COPY --from=frontend-build` source path matches.

### Tests fail due to missing env vars

Tests use `@ActiveProfiles("test")` and `MockWebServer` — they do NOT need real credentials. If tests fail because of missing env vars, it means `application.yml` is being loaded with real var substitution. Ensure no `application-test.yml` is missing, or that test properties override required values.
