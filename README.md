# DeployMate

> A self-hosted deployment control panel that automates the full staging-deploy workflow across multiple repositories — merge branches, create release tags, trigger Jenkins pipelines, and post Jira comments, all from one browser tab.

---

## Table of Contents

1. [What it does](#1-what-it-does)
2. [How it works](#2-how-it-works)
3. [Prerequisites](#3-prerequisites)
4. [Quick start with Docker (recommended)](#4-quick-start-with-docker-recommended)
5. [Environment file — all properties explained](#5-environment-file--all-properties-explained)
6. [Service registry — services.json](#6-service-registry--servicesjson)
7. [Local development (without Docker)](#7-local-development-without-docker)
8. [Running the tests](#8-running-the-tests)
9. [MCP server (Claude integration)](#9-mcp-server-claude-integration)
10. [Project structure](#10-project-structure)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. What it does

You open the app, describe every service and SDK that belongs to a deployment, and click **Deploy All**. DeployMate then:

- **Merges** your feature branch into the target branch on GitHub and writes a structured commit message that includes the Jira ticket number.
- **Creates a pre-release tag** on GitHub (for services — not SDKs) and publishes a draft pre-release so there is a permanent record of what was deployed.
- **Triggers a Jenkins pipeline** with a single `git_branch` parameter — set to the pre-release tag name for services, or `origin/<targetBranch>` for SDKs — and polls it every few seconds until the build finishes.
- **Posts Jira comments** at every milestone (merge done, tag created, build started, build passed/failed, conflict detected).

Everything runs in the correct order: SDK Stage 1 finishes before SDK Stage 2 starts, all SDKs must be done before services begin. Within a single stage, all repos run in parallel.

If a merge conflict is detected, the deployment halts and the UI shows exactly which repository conflicted, with a direct link to the diff on GitHub and a one-click retry button.

---

## 2. How it works

```
Browser (React + Vite)
        │
        │  REST  /api/*
        ▼
Spring Boot (port 8080)
   ├── GitHub API  → merge branches, create tags, create pre-releases
   ├── Jenkins API → fetch CSRF crumb, trigger builds, poll queue + build
   └── Jira API    → post ADF-formatted comments
```

- The **frontend** is a compiled React app that Spring Boot serves as static files. In production there is one process, one port, one container.
- The **backend** holds all credentials. The browser never touches GitHub, Jenkins, or Jira directly.
- The **Deploy All** endpoint returns immediately with `202 Accepted` and runs the entire orchestration on a Java 21 virtual thread in the background.

---

## 3. Prerequisites

### For Docker (the easiest path)

| Tool | Minimum version | Check |
|------|----------------|-------|
| Docker Desktop or Docker Engine | 24+ | `docker --version` |
| Docker Compose | v2 (bundled with Docker Desktop) | `docker compose version` |

That is all you need. Java and Node are not required on your machine.

### For local development

| Tool | Minimum version | Check |
|------|----------------|-------|
| Java JDK | 21 | `java --version` |
| Maven | 3.9 (or use the included wrapper) | `./mvnw --version` |
| Node.js | 20 LTS | `node --version` |
| npm | 9+ | `npm --version` |

---

## 4. Quick start with Docker (recommended)

### Step 1 — Clone the repo

```bash
git clone <your-repo-url> deploymate
cd deploymate
```

### Step 2 — Create the database data directory

PostgreSQL stores its data on your local filesystem so it survives container recreations. Create the directory before the first `docker compose up`:

```bash
mkdir -p ~/Documents/data/deploymate/postgres
```

### Step 3 — Create your `.env` file

```bash
cp .env.example .env
```

Open `.env` in any text editor and fill in your credentials. See [Section 5](#5-environment-file--all-properties-explained) for a full explanation of every property.

> The `DATABASE_USER` and `DATABASE_PASSWORD` values in `.env` are already set with safe defaults. Change them if you want a custom database password.

```
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
GITHUB_ORG=your-org-name
JENKINS_URL=https://jenkins.your-company.com
JENKINS_USER=your-username
JENKINS_TOKEN=your-jenkins-api-token
JIRA_URL=https://your-company.atlassian.net
JIRA_EMAIL=you@company.com
JIRA_TOKEN=your-jira-api-token
DEFAULT_TARGET_BRANCH=env/staging
TAG_PREFIX=env-stag
```

> ⚠️ The `.env` file is in `.gitignore`. It will never be committed. Never put credentials anywhere else.

### Step 4 — Create your `services.json`

The repository dropdown in the UI is driven by a local JSON file that you fill in once and never commit. This file is **gitignored** — every developer keeps their own copy.

```bash
cp frontend/public/services.example.json frontend/public/services.json
```

Open `frontend/public/services.json` and add an entry for every repository you deploy:

```json
{
  "services": {
    "payment-service": {
      "displayName":        "Payment Service",
      "type":               "SERVICE",
      "jenkinsCategory":    "cross-products",
      "jenkinsServiceName": "payment-service"
    },
    "client-sdk": {
      "displayName":        "Client SDK",
      "type":               "SDK",
      "jenkinsCategory":    "cross-products",
      "jenkinsServiceName": "client-sdk"
    }
  }
}
```

See [Section 6](#6-service-registry--servicesjson) for the full field reference.

> ⚠️ `frontend/public/services.json` is in `.gitignore`. It will never be committed. Each developer maintains their own copy.

### Step 5 — Build and start

```bash
docker compose up --build
```

The first build takes a few minutes (it downloads Node modules and Maven dependencies). Subsequent starts are fast because Docker caches the dependency layers.

```
[+] Building ...
 ✔ frontend-build   npm ci + vite build
 ✔ backend-build    mvnw package
 ✔ runtime          JRE image, non-root user
[+] Running 1/1
 ✔ Container deploymate  Started
```

### Step 6 — Open the app

```
http://localhost:8080
```

### Stopping

```bash
docker compose down
```

Logs are stored in a named Docker volume (`deploymate_logs`) and survive container restarts. To also remove the volume:

```bash
docker compose down -v
```

### Rebuilding after a code change

```bash
docker compose up --build
```

---

## 5. Environment file — all properties explained

The file must be named **`.env`** and placed in the **project root** (the same directory as `docker-compose.yml` and `Dockerfile`). Docker Compose reads it automatically via `env_file: .env`.

For local development (without Docker), the same file is read by Spring Boot on startup.

---

### GitHub

```dotenv
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```
A GitHub **Personal Access Token** (classic).

Required scopes:
- `repo` — read repos, create merge commits, create tags and releases
- `workflow` — needed if your Jenkins pipelines are triggered via GitHub Actions (optional)

How to create one: GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token.

```dotenv
GITHUB_ORG=your-org-name
```
The GitHub **organisation name** (or your personal username) that owns all the repositories you will deploy. For example, if your repos live at `github.com/acme-corp/my-service`, this value is `acme-corp`.

---

### Jenkins

```dotenv
JENKINS_URL=https://jenkins.your-company.com
```
The **base URL** of your Jenkins server. No trailing slash. Must be `http://` or `https://` — the app refuses to start if this is missing or uses another scheme.

```dotenv
JENKINS_USER=your-username
```
Your Jenkins username (the one you log in with).

```dotenv
JENKINS_TOKEN=your-jenkins-api-token
```
A Jenkins **API token** — *not* your login password. Generate one at:
`https://<your-jenkins>/user/<your-username>/configure` → API Token → Add new Token.

The backend uses HTTP Basic auth (`user:token`) and also fetches a CSRF crumb before every POST, which is required by most Jenkins installations.

---

### Jira

```dotenv
JIRA_URL=https://your-company.atlassian.net
```
Your Jira Cloud base URL. No trailing slash. Must be `http://` or `https://`.

```dotenv
JIRA_EMAIL=you@company.com
```
The email address of the Jira account that will post comments.

```dotenv
JIRA_TOKEN=your-jira-api-token
```
A Jira **API token** — *not* your Atlassian password. Generate one at:
[https://id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens)

Comments are sent using Jira REST API v3 in **Atlassian Document Format (ADF)**, which is the format Jira Cloud requires.

---

### Defaults (optional)

```dotenv
DEFAULT_TARGET_BRANCH=env/staging
```
The branch that all new rows in the UI will merge INTO by default. You can override this per row in the UI. If you leave this out, it defaults to `env/staging`.

```dotenv
TAG_PREFIX=env-stag
```
The prefix used when auto-generating tag names. A tag is generated as:
`<TAG_PREFIX>-<YYYYMMDD>-<repo-name>-001`

For example, with `TAG_PREFIX=env-stag` and repo `payment-service` on 1 June 2024:
`env-stag-20240601-payment-service-001`

You can edit the tag name per row in the UI before deploying. If you leave this out, it defaults to `env-stag`.

---

### Complete `.env` example (copy and fill in)

```dotenv
# ── GitHub ────────────────────────────────────────────────────────────────────
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
GITHUB_ORG=acme-corp

# ── Jenkins ───────────────────────────────────────────────────────────────────
JENKINS_URL=https://jenkins.acme-corp.com
JENKINS_USER=deploy-bot
JENKINS_TOKEN=11abc123def456abc123def456abc123de

# ── Jira ──────────────────────────────────────────────────────────────────────
JIRA_URL=https://acme-corp.atlassian.net
JIRA_EMAIL=deploy-bot@acme-corp.com
JIRA_TOKEN=ATATT3xFfGF0xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# ── Defaults ──────────────────────────────────────────────────────────────────
DEFAULT_TARGET_BRANCH=env/staging
TAG_PREFIX=env-stag
```

---

## 6. Service registry — services.json

The repository dropdown in every service row is powered by a **local JSON file** that lives outside version control. This keeps your team's internal repository names and Jenkins job paths off GitHub while still letting everyone configure their own copy.

### File locations

```
frontend/public/services.example.json   ← committed template, safe to edit
frontend/public/services.json           ← your local copy (gitignored, never committed)
```

### First-time setup

```bash
cp frontend/public/services.example.json frontend/public/services.json
```

Then open `frontend/public/services.json` in any text editor.

### File format

```json
{
  "services": {
    "<github-repo-name>": {
      "displayName":        "Human-readable name shown in the UI",
      "type":               "SERVICE",
      "jenkinsCategory":    "first-segment-of-jenkins-job-path",
      "jenkinsServiceName": "third-segment-of-jenkins-job-path"
    }
  }
}
```

### Field reference

| Field | Required | Description                                                                                                                                      |
|-------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Key (top level) | ✓ | GitHub repository name — must match exactly, case-sensitive                                                                                      |
| `displayName` | ✓ | Label shown in the row header                                                                                                                    |
| `type` | ✓ | `SERVICE` — deploys from a pre-release tag and uses the staging path for jenkins; `SDK` — builds from a branch and uses the dev path for jenkins |
| `jenkinsCategory` | ✓ | First path segment of the Jenkins job (the team/org folder)                                                                                      |
| `jenkinsServiceName` | ✓ | Third path segment (after the environment segment)                                                                                               |

The Jenkins job path DeployMate constructs is:

```
<jenkinsCategory> / <env> / <jenkinsServiceName>
```

where `<env>` is `staging` for SERVICE rows and `dev` for SDK rows by default. You can override this per row using the **"By env"** toggle.

### Example

```json
{
  "services": {
    "payment-service": {
      "displayName":        "Payment Service",
      "type":               "SERVICE",
      "jenkinsCategory":    "cross-products",
      "jenkinsServiceName": "payment-service"
    },
    "auth-service": {
      "displayName":        "Auth Service",
      "type":               "SERVICE",
      "jenkinsCategory":    "platform",
      "jenkinsServiceName": "auth-service"
    },
    "client-sdk": {
      "displayName":        "Client SDK",
      "type":               "SDK",
      "jenkinsCategory":    "cross-products",
      "jenkinsServiceName": "client-sdk"
    }
  }
}
```

This produces the Jenkins paths:
- `cross-products/staging/payment-service`
- `platform/staging/auth-service`
- `cross-products/dev/client-sdk`

### How the UI uses it

When the app starts, it fetches `services.json` once. Selecting a repository from the dropdown auto-fills the display name, type, Jenkins category, and Jenkins service name — those fields become read-only (shown with a lock icon). Stage, source branch, target branch, tag name, and step toggles remain editable per deployment.

If `services.json` is missing, the dropdown shows a message explaining how to create it. The rest of the UI continues to work normally.

Any change to `services.json` takes effect on the next browser refresh — no rebuild needed.

### Docker: mounting the file

When running via `docker compose`, the built image does not contain your `services.json` (it is gitignored and therefore not present at build time). Mount it at runtime instead:

```yaml
# docker-compose.yml — under the deploymate service
volumes:
  - deploymate_logs:/app/logs
  - deploymate_data:/app/data
  - ./frontend/public/services.json:/app/static/services.json:ro
```

Spring Boot serves `/app/static/` as static files, so the frontend fetches it from `/services.json` as usual.

---

## 7. Local development (without Docker)

This mode runs the backend and frontend as two separate processes. The frontend proxies `/api` requests to the backend automatically.

### Terminal 1 — Backend

```bash
cd backend
cp ../.env .env          # Spring Boot picks up .env automatically via IDE
                          # OR export variables manually (see below)
./mvnw spring-boot:run
```

If your IDE or shell does not load `.env` automatically, export the variables first:

```bash
export GITHUB_TOKEN=ghp_...
export GITHUB_ORG=acme-corp
export JENKINS_URL=https://jenkins.acme-corp.com
export JENKINS_USER=deploy-bot
export JENKINS_TOKEN=11abc...
export JIRA_URL=https://acme-corp.atlassian.net
export JIRA_EMAIL=deploy-bot@acme-corp.com
export JIRA_TOKEN=ATATT...
./mvnw spring-boot:run
```

The backend starts on **port 8080**.

### Terminal 2 — Frontend

```bash
cd frontend
npm install       # first time only

# Create your service registry if you haven't already:
cp public/services.example.json public/services.json
# Then edit public/services.json and add your repositories

npm run dev
```

The frontend starts on **port 5173**. Open `http://localhost:5173`.

All `/api/*` requests from the browser are automatically proxied to `http://localhost:8080` by Vite's dev server — you do not need to configure anything.

> **Note:** In local dev mode, hot reload works for the frontend (save a `.tsx` file and the browser updates instantly). For backend changes, stop and restart `./mvnw spring-boot:run`.

---

## 8. Running the tests

Tests run against the **backend only**. The frontend has TypeScript type checking.

### Backend tests (109 tests)

```bash
cd backend
./mvnw test
```

What is tested:
- **Unit tests** — `GitHubService`, `JenkinsService`, `JiraService`, `OrchestratorService`, `TagGeneratorService` — all external HTTP calls are intercepted by MockWebServer and never reach real APIs.
- **Integration tests** — every REST controller (`/api/merge`, `/api/tag`, `/api/pipeline/*`, `/api/jira/comment`, `/api/deploy/all`, `/api/jenkins/*`, `/api/log`) tested with MockMvc, including validation edge cases like missing bodies, blank fields, and path-traversal attempts.

Expected output:

```
[INFO] Tests run: 109, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Frontend type check

```bash
cd frontend
npx tsc --noEmit --project tsconfig.app.json
```

Expected: no output, exit code 0.

### Smoke test (needs a running server)

```bash
# Start the server first (Docker or local)
./scripts/smoke-test.sh                     # tests http://localhost:8080
./scripts/smoke-test.sh http://other:8080   # or point at any URL
```

Runs 18 checks against a live server: SPA routing, log endpoint, input validation on all POST endpoints, error response shape.

---

## 9. MCP server (Claude integration)

The MCP (Model Context Protocol) server exposes every DeployMate capability as a tool that Claude can call directly from a Claude Code terminal session. This means you can describe a deployment in plain English and Claude will execute each step — merging branches, creating tags, triggering Jenkins, polling build status, posting Jira comments — using the same backend that powers the UI.

The MCP server is a **separate Node.js process** that communicates with the running DeployMate backend over HTTP. It holds **no credentials of its own**; all authentication happens in the Spring Boot backend using the values you put in `.env`.

---

### What Claude can do with this integration

With the MCP server connected, Claude can:

- **Inspect before acting** — check what commit is currently on a branch, what the latest GitHub release tag is, and what tag was last deployed to Jenkins, all before touching anything.
- **Execute individual steps** — merge a single branch, create a tag, or fire one pipeline without a full batch run.
- **Run a full orchestrated deployment** — submit a multi-repo, multi-stage batch in one call and receive a structured summary when it finishes.
- **Monitor progress** — tail the deployment log, filter by service name, and report back whether a build passed or failed.
- **Post Jira updates** — write structured comments on a ticket at any point during the process.
- **Manage Jenkins config** — save new Jenkins categories and service names so they appear in the UI dropdowns.

---

### Step 1 — Build the MCP server

```bash
cd mcp
npm install       # first time only
npm run build     # compiles TypeScript → dist/server.js
```

> **Dev mode (skip the build):** If you want to run directly from source without a build step, use the locally installed `tsx` binary instead:
> ```json
> "command": "/absolute/path/to/deploymate/mcp/node_modules/.bin/tsx",
> "args":    ["/absolute/path/to/deploymate/mcp/server.ts"]
> ```

---

### Step 2 — Register with Claude Code

Claude Code reads MCP server configuration from **`~/.claude/settings.json`**. Open that file and add the `mcpServers` block (keep any existing keys such as `theme`):

```json
{
  "theme": "dark-ansi",
  "mcpServers": {
    "deploymate": {
      "command": "node",
      "args": ["/absolute/path/to/deploymate/mcp/dist/server.js"],
      "env": {
        "DEPLOYMATE_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

Replace `/absolute/path/to/deploymate` with the actual path on your machine. `DEPLOYMATE_API_URL` is optional and defaults to `http://localhost:8080` — set it only if you run the backend on a different port.

> ⚠️ Do **not** add GitHub, Jenkins, or Jira credentials to this file. The MCP server never touches those APIs directly; all calls go through the DeployMate backend which reads credentials from `.env`.

After saving, restart Claude Code (quit and reopen) or run `/mcp` in the terminal to reload servers. You should see:

```
deploymate  ● connected  13 tools
```

---

### Available tools

| Tool | What it does |
|------|-------------|
| `get_branch_commit_info` | Fetch the latest commit SHA, author, message, and timestamp for any branch — use this to verify a branch is in the expected state before merging |
| `get_next_tag` | Suggest the next pre-release tag name based on the latest GitHub release, and optionally show the `git_branch` value from the last successful Jenkins build |
| `merge_branch` | Verify a branch exists on GitHub and merge it into the target branch; returns the merge commit SHA or reports a conflict |
| `create_tag` | Create a lightweight git tag and a GitHub pre-release from the HEAD of the target branch |
| `trigger_pipeline` | Trigger a Jenkins build with the `git_branch` parameter — SERVICE rows pass the tag name, SDK rows pass `origin/<targetBranch>` |
| `get_pipeline_status` | Poll a Jenkins queue item or build URL; returns current state (QUEUED / RUNNING / SUCCESS / FAILURE) and the last log fragment |
| `add_jira_comment` | Post a plain-text comment to a Jira issue (the backend converts it to ADF automatically) |
| `get_jenkins_categories` | List all Jenkins job category prefixes saved in DeployMate |
| `save_jenkins_category` | Persist a new Jenkins category so it appears in the UI dropdown |
| `get_jenkins_service_names` | List all service names saved under a given category |
| `save_jenkins_service_name` | Persist a service name under a category so it appears in the UI dropdown |
| `deploy_all` | Submit a full multi-repo deployment batch; rows with the same stage number run in parallel, stages execute sequentially; returns immediately with 202 and runs in the background |
| `get_log` | Fetch recent lines from the server-side deployment log, optionally filtered by service name |

---

### Prompt example — full multi-stage deployment

This example shows a realistic deployment with two SDKs in stage 1 and one service in stage 2. Paste it directly into Claude Code (with the MCP server connected and the DeployMate backend running):

---

> I need to deploy ticket **PROJ-247** to staging. The backend is running at `http://localhost:8080`.
>
> Here are the repositories and their order:
>
> **Stage 1 — run these two in parallel first:**
>
> 1. **`client-sdk`** (SDK)
>    - Merge `feature/PROJ-247-new-auth` → `env/staging`
>    - Trigger Jenkins job `cross-products/dev/client-sdk`
>
> 2. **`models-sdk`** (SDK)
>    - Merge `feature/PROJ-247-new-auth` → `env/staging`
>    - Trigger Jenkins job `cross-products/dev/models-sdk`
>
> **Stage 2 — only after both SDKs are done:**
>
> 3. **`auth-service`** (SERVICE)
>    - Merge `feature/PROJ-247-new-auth` → `env/staging`
>    - Create a pre-release tag (suggest the next one automatically)
>    - Trigger Jenkins job `platform/staging/auth-service` using the new tag
>
> For all three repos, post Jira comments on **PROJ-247** at each step (merge done, tag created, build started, build result).
>
> If any merge conflict is detected, stop and tell me which repo failed so I can resolve it manually before retrying.
>
> When everything is done, post a final summary comment on PROJ-247 listing what was deployed and whether all builds passed.

---

**What Claude will do with that prompt:**

1. Call `get_next_tag` for `auth-service` to determine the tag name before submitting anything.
2. Call `deploy_all` with all three rows configured at the correct stages, steps, and Jenkins jobs.
3. Poll `get_log` periodically and report progress as each stage completes.
4. If a conflict or build failure occurs, surface the error message and wait for your instruction.
5. Call `add_jira_comment` with a final deployment summary once all stages are done.

---

### Prompt example — step-by-step (manual control)

If you prefer to drive each step individually rather than submitting a full batch:

> Check what commit is currently on `feature/PROJ-247-new-auth` in `auth-service`, then merge it into `env/staging`. If the merge succeeds, suggest the next pre-release tag.

> The merge is done. Create a pre-release tag `v1.2.0rc3` for `auth-service` from `env/staging`.

> Trigger the Jenkins job `platform/staging/auth-service` with tag `v1.2.0rc3` and poll it every 30 seconds until it finishes. Post the result to Jira issue PROJ-247.

---

## 10. Project structure

```
deploymate/
│
├── .env.example              ← Template — copy to .env and fill in
├── .env                      ← Your credentials (git-ignored, never committed)
├── .gitignore
│
├── Dockerfile                ← 3-stage build: Node → JDK → JRE
├── docker-compose.yml        ← Single container, reads .env, persists logs
├── README.md
│
├── backend/                  ← Spring Boot 3.3, Java 21, Maven
│   ├── mvnw                  ← Maven wrapper (no Maven install needed)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/deploymate/
│       │   │   ├── config/       AppProperties.java, WebConfig.java, GlobalExceptionHandler.java
│       │   │   ├── controller/   MergeController, TagController, PipelineController,
│       │   │   │                 JiraController, DeployController, LogController, SpaController
│       │   │   ├── dto/          All request/response records
│       │   │   ├── model/        DeployException, ErrorCode
│       │   │   └── service/      GitHubService, JenkinsService, JiraService,
│       │   │                     OrchestratorService, LogService, TagGeneratorService
│       │   └── resources/
│       │       ├── application.yml   ← Reads all env vars; log file config
│       │       └── static/           ← Frontend build output lands here (git-ignored)
│       └── test/                 109 unit + integration tests
│
├── frontend/                 ← React 19, Vite 6, TypeScript 5, Tailwind CSS
│   ├── package.json
│   ├── vite.config.ts        ← /api proxy to :8080; build output → backend/static
│   ├── public/
│   │   ├── services.example.json  ← committed template — copy to services.json
│   │   └── services.json          ← your local registry (git-ignored, never committed)
│   └── src/
│       ├── components/       All UI components
│       ├── hooks/            useDeployStore (Zustand), useDeployActions, useServiceRegistry
│       ├── lib/              api.ts (fetch wrappers), utils.ts
│       └── types/            index.ts (all TypeScript types)
│
├── mcp/                      ← MCP stdio server (Node.js + TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── server.ts             ← 13 tools; rate limiter; credential sanitizer
│
├── scripts/
│   └── smoke-test.sh         ← 18 checks against a live server
│
└── logs/                     ← Runtime log files (git-ignored)
    └── deploymate.log
```

---

## 11. Troubleshooting

### The container starts but I see "Application failed to start"

The most common cause is a missing or wrong environment variable. Check the Docker logs:

```bash
docker compose logs deploymate
```

Look for a line like:
```
Caused by: java.lang.IllegalStateException: Invalid JENKINS_URL: ...
```
or:
```
Field error in object 'deploymate' on field 'github.token': rejected value []
```

Fix the corresponding value in `.env` and run `docker compose up --build` again.

---

### "Merge conflict" — what do I do?

The UI shows a yellow conflict panel on the affected row with:
- The name of the repository that conflicted
- A button **"Open in GitHub"** — takes you directly to the diff between your source and target branch
- A button **"Retry merge"** — retries after you have resolved the conflict

Resolve the conflict in GitHub (create a new merge commit or rebase), then click **Retry merge** in the UI.

---

### Jenkins trigger returns 403

This usually means:
1. The CSRF crumb request failed — check that `JENKINS_USER` and `JENKINS_TOKEN` are correct.
2. The user does not have **"Build"** permission on the job — check the Jenkins job's security settings.
3. The Jenkins URL uses `http://` but your server requires `https://` (or vice versa).

---

### Jenkins job is not found (404)

Check the `Jenkins job` field in the UI. The format must match the job's full path in Jenkins **without** a leading slash. Nested jobs (inside folders) use forward slashes:

| Jenkins UI path | Correct value in DeployMate |
|----------------|----------------------------|
| `my-job` | `my-job` |
| `Folder » my-job` | `Folder/my-job` |
| `Team » Backend » deploy` | `Team/Backend/deploy` |

---

### Jira comments are not appearing

1. Confirm the **"Update Jira"** toggle is **on** for the row.
2. Confirm the **Jira Ticket** field at the top is filled in (e.g. `PROJ-123`).
3. Check that `JIRA_EMAIL` matches the Atlassian account that owns the API token.
4. The Jira URL must not have a trailing slash: `https://acme.atlassian.net` ✓ not `https://acme.atlassian.net/` ✗.
5. Check the activity log in the UI — if the comment failed, a warning is logged there (Jira failures do not stop the deployment).

---

### I want to run only the pipeline — no merge, no tag

Disable the **Merge branch** and **Create tag** step toggles for that row. The pipeline always uses:
- **SERVICE rows**: `git_branch = <tagName>` — the tag name field must be filled in manually if you skip the create-tag step.
- **SDK rows**: `git_branch = origin/<targetBranch>` — no tag is ever needed.

---

### Port 8080 is already in use

Change the host port in `docker-compose.yml`:

```yaml
ports:
  - "9090:8080"   # host port 9090 → container port 8080
```

Then open `http://localhost:9090`.

---

### How do I see the server logs?

**Inside Docker:**
```bash
docker compose logs -f deploymate        # stream live
docker compose logs --tail 100 deploymate
```

**Persistent log file** (survives container restarts):
```bash
docker exec deploymate cat /app/logs/deploymate.log
# or copy it out:
docker cp deploymate:/app/logs/deploymate.log ./deploymate.log
```

**From the UI:** The **Activity Log** panel at the bottom of the page shows a live feed of every step, with timestamps and colour-coded levels. The `GET /api/log?lines=200` endpoint returns the tail of the server log as a JSON array of strings.
