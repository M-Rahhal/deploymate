# DeployMate

A locally-run web application that automates staging deployments across multiple microservice repositories tied to a single Jira ticket.

## What it does

- Merges feature branches into staging, creates pre-release tags, triggers Jenkins pipelines — all from one UI
- Stage-based parallel execution: SDK Stage 1 → Stage 2 → ... → Services (all parallel)
- Per-service **Skip Merge** option to go straight to pipeline re-runs
- Structured Jira comments at every step
- MCP server so Claude can drive deployments from the terminal

## Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.3, Maven |
| Frontend | React 19, Vite, TypeScript, Tailwind CSS, shadcn/ui |
| MCP server | Node.js, TypeScript |
| Container | Single multi-stage Dockerfile |

## Quick start (local dev)

```bash
cp .env.example .env
# Fill in .env with your GitHub / Jenkins / Jira credentials

# Terminal 1 — Spring Boot API
cd backend
./mvnw spring-boot:run

# Terminal 2 — React dev server (proxies /api to :8080)
cd frontend
npm install && npm run dev

# Open http://localhost:5173
```

## Docker (production)

```bash
docker compose up --build
# Open http://localhost:8080
```

## MCP server (optional)

```bash
cd mcp
npm install
npx ts-node server.ts
```

Register in Claude's MCP config — see `docs/` for details.

## Environment variables

Copy `.env.example` to `.env` and fill in:

| Variable | Description |
|----------|-------------|
| `GITHUB_TOKEN` | Personal access token with `repo` + `workflow` scopes |
| `GITHUB_ORG` | GitHub organisation or username that owns the repos |
| `JENKINS_URL` | Base URL of your Jenkins server (no trailing slash) |
| `JENKINS_USER` | Jenkins username |
| `JENKINS_TOKEN` | Jenkins API token |
| `JIRA_URL` | Jira Cloud base URL |
| `JIRA_EMAIL` | Email address for Jira API auth |
| `JIRA_TOKEN` | Jira API token |
| `DEFAULT_TARGET_BRANCH` | Default branch to merge into (default: `env/staging`) |
| `TAG_PREFIX` | Prefix for auto-generated tag names (default: `env-stag`) |

## Running tests

```bash
cd backend
./mvnw test
```
