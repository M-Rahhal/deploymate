/**
 * DeployMate MCP Server
 *
 * Exposes deployment tools to Claude via the Model Context Protocol (stdio).
 * This server holds NO credentials — it calls http://localhost:8080/api/... only.
 * The Spring Boot backend reads credentials from .env and performs all auth.
 *
 * Atomic tools (one endpoint each):
 *   merge_branch               — verify + merge source → target
 *   create_tag                 — create a GitHub pre-release tag
 *   trigger_pipeline           — trigger a Jenkins pipeline build
 *   get_pipeline_status        — poll a build queue item or build URL
 *   get_next_tag               — suggest next tag + last deployed tag from Jenkins
 *   get_jenkins_categories     — list saved Jenkins category prefixes
 *   save_jenkins_category      — persist a new Jenkins category
 *   get_jenkins_service_names  — list service names under a category
 *   save_jenkins_service_name  — persist a service name under a category
 *   get_branch_commit_info     — fetch latest commit details for a branch
 *   add_jira_comment           — post a comment to a Jira issue
 *   get_log                    — tail the server-side deployment log
 *
 * Composite/batch tool:
 *   deploy_all — submit a full deployment batch to the orchestrator
 *
 * git_branch parameter strategy (enforced by the backend):
 *   SERVICE rows → git_branch = tagName  (pre-release tag; tagName must be set)
 *   SDK rows     → git_branch = "origin/" + targetBranch  (branch ref)
 */

import { McpServer }           from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z }                   from "zod";
import axios, { AxiosError }   from "axios";

// ─── Configuration ────────────────────────────────────────────────────────────

const BASE = process.env["DEPLOYMATE_API_URL"] ?? "http://localhost:8080";

// ─── Axios client ─────────────────────────────────────────────────────────────

const http = axios.create({
  baseURL: BASE,
  timeout: 30_000,
  headers: { "Content-Type": "application/json" },
});

// ─── Helpers ──────────────────────────────────────────────────────────────────

function sanitize(text: string): string {
  return text
    .replace(/ghp_[A-Za-z0-9]{36,}/g,         "[REDACTED-GH-TOKEN]")
    .replace(/ghs_[A-Za-z0-9]{36,}/g,         "[REDACTED-GH-TOKEN]")
    .replace(/Basic [A-Za-z0-9+/=]{20,}/g,    "[REDACTED-BASIC]")
    .replace(/Bearer [A-Za-z0-9._\-]{20,}/g,  "[REDACTED-BEARER]")
    .replace(/:[A-Za-z0-9._\-]{8,}@[a-z]/g,  "[REDACTED]@");
}

function text(msg: string) {
  return { content: [{ type: "text" as const, text: sanitize(msg) }] };
}

function errText(context: string, err: unknown): ReturnType<typeof text> {
  if (err instanceof AxiosError) {
    const status = err.response?.status ?? "network error";
    const apiMsg = (err.response?.data as { message?: string } | undefined)?.message;
    const detail = apiMsg ? `: ${apiMsg}` : "";
    return text(`${context} failed (HTTP ${status}${detail}). Check the DeployMate activity log.`);
  }
  return text(`${context} failed. Check the DeployMate activity log.`);
}

// ─── Per-tool rate limiter ────────────────────────────────────────────────────

const callLog = new Map<string, number[]>();

function checkRateLimit(toolName: string, max = 10): boolean {
  const now    = Date.now();
  const window = 60_000;
  const calls  = (callLog.get(toolName) ?? []).filter((t) => now - t < window);
  if (calls.length >= max) return false;
  calls.push(now);
  callLog.set(toolName, calls);
  return true;
}

function rateLimitExceeded(): ReturnType<typeof text> {
  return text("Rate limit exceeded. Wait a moment and retry.");
}

// ─── Steps schema (reused across tools) ──────────────────────────────────────

const stepsSchema = z.object({
  mergeBranch:     z.boolean().describe("Merge source branch into target branch"),
  createTag:       z.boolean().describe("Create a GitHub pre-release tag before triggering the pipeline"),
  triggerPipeline: z.boolean().describe("Trigger the Jenkins pipeline"),
  updateJira:      z.boolean().describe("Post Jira comments at each step"),
});

// ─── MCP server ───────────────────────────────────────────────────────────────

const server = new McpServer({
  name:    "deploymate",
  version: "2.0.0",
});

// ══════════════════════════════════════════════════════════════════════════════
// Atomic tool: merge_branch
// Calls POST /api/merge only. No pipeline, no tag.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "merge_branch",
  "Verify that a branch exists on GitHub and merge it into the target branch. Returns the merge commit SHA or reports a conflict.",
  {
    repo:         z.string().min(1).describe("GitHub repository name"),
    sourceBranch: z.string().min(1).describe("Branch to merge FROM"),
    targetBranch: z.string().min(1).optional().default("env/staging").describe("Branch to merge INTO"),
    ticket:       z.string().optional().default("").describe("Jira ticket key (e.g. PROJ-123), optional"),
  },
  async (args) => {
    if (!checkRateLimit("merge_branch")) return rateLimitExceeded();

    try {
      const res = await http.post("/api/merge", {
        repo:         args.repo,
        sourceBranch: args.sourceBranch,
        targetBranch: args.targetBranch,
        ticket:       args.ticket,
      });

      const data = res.data as { conflict?: boolean; sha?: string; message?: string };
      if (data.conflict) {
        return text(
          `Merge conflict in ${args.repo} (${args.sourceBranch} → ${args.targetBranch}). ` +
          `Resolve the conflict manually, then retry.`
        );
      }

      return text(
        `Merged ${args.repo}: ${args.sourceBranch} → ${args.targetBranch}. ` +
        `SHA: ${data.sha ?? "n/a"}`
      );
    } catch (err) {
      return errText(`merge_branch for ${args.repo}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Atomic tool: create_tag
// Calls POST /api/tag only. Gets HEAD SHA of target branch, creates pre-release.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "create_tag",
  "Create a GitHub pre-release tag from the HEAD of the target branch. Returns the tag name and release URL.",
  {
    repo:         z.string().min(1).describe("GitHub repository name"),
    tagName:      z.string().min(1).describe("Tag name (e.g. env-stag-20240601-my-svc-001)"),
    targetBranch: z.string().min(1).optional().default("env/staging").describe("Branch to tag from"),
    ticket:       z.string().optional().default("").describe("Jira ticket key, optional"),
  },
  async (args) => {
    if (!checkRateLimit("create_tag")) return rateLimitExceeded();

    try {
      const res = await http.post("/api/tag", {
        repo:         args.repo,
        tagName:      args.tagName,
        targetBranch: args.targetBranch,
        ticket:       args.ticket,
      });

      const data = res.data as { tagName?: string; releaseUrl?: string };
      return text(
        `Tag ${data.tagName ?? args.tagName} created for ${args.repo}. ` +
        (data.releaseUrl ? `Release: ${data.releaseUrl}` : "")
      );
    } catch (err) {
      return errText(`create_tag for ${args.repo}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Atomic tool: trigger_pipeline
// Calls POST /api/pipeline/trigger only. Returns the Jenkins queue item URL.
//
// Jenkins always receives the parameter "git_branch".
//   SDK:     gitBranch = "origin/{targetBranch}"  (branch to build)
//   SERVICE: gitBranch = tagName                  (pre-release tag to deploy; tag must exist on GitHub)
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "trigger_pipeline",
  "Trigger a Jenkins pipeline build. Jenkins receives a single 'git_branch' parameter. For SDKs pass 'origin/{branchName}'; for SERVICEs pass the pre-release tag name (e.g. v1.0.0rc2). Returns the queue item URL to poll with get_pipeline_status.",
  {
    jenkinsJob: z.string().min(1).describe("Jenkins job path (e.g. cross-products/staging/payment-service)"),
    gitBranch:  z.string().min(1).describe("Value for the git_branch Jenkins parameter. SDK: 'origin/env/staging'. SERVICE: tag name e.g. 'v1.0.0rc2'"),
  },
  async (args) => {
    if (!checkRateLimit("trigger_pipeline")) return rateLimitExceeded();

    try {
      const res = await http.post("/api/pipeline/trigger", {
        jenkinsJob: args.jenkinsJob,
        gitBranch:  args.gitBranch,
      });

      const data = res.data as { queueItemUrl?: string };
      return text(
        `Pipeline ${args.jenkinsJob} queued with git_branch=${args.gitBranch}. ` +
        (data.queueItemUrl ? `Poll status at: ${data.queueItemUrl}` : "")
      );
    } catch (err) {
      return errText(`trigger_pipeline for ${args.jenkinsJob}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_pipeline_status
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_pipeline_status",
  "Poll the status of a Jenkins build. Pass either queueItemUrl (right after trigger) or buildUrl (once the build has started).",
  {
    queueItemUrl: z.string().url().optional().describe("Jenkins queue item URL from a trigger response"),
    buildUrl:     z.string().url().optional().describe("Jenkins build URL (e.g. http://jenkins/job/x/42/)"),
  },
  async (args) => {
    if (!checkRateLimit("get_pipeline_status", 30)) return rateLimitExceeded();
    if (!args.queueItemUrl && !args.buildUrl) {
      return text("Provide either queueItemUrl or buildUrl.");
    }

    try {
      const params: Record<string, string> = {};
      if (args.queueItemUrl) params["queueItemUrl"] = args.queueItemUrl;
      if (args.buildUrl)     params["buildUrl"]     = args.buildUrl;

      const res  = await http.get("/api/pipeline/status", { params });
      const data = res.data as {
        state:       string;
        buildUrl:    string | null;
        buildNumber: number | null;
        message:     string;
      };

      return text(
        `Pipeline state: ${data.state}. ` +
        (data.buildNumber ? `Build #${data.buildNumber}. ` : "") +
        (data.buildUrl    ? `URL: ${data.buildUrl}. `      : "") +
        data.message
      );
    } catch (err) {
      return errText("get_pipeline_status", err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: add_jira_comment
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "add_jira_comment",
  "Post a deployment summary comment to a Jira issue.",
  {
    issueKey: z.string().regex(/^[A-Z]{1,20}-[0-9]{1,10}$/).describe("Jira issue key (e.g. PROJ-123)"),
    text:     z.string().min(1).max(5000).describe("Comment text (plain text; ADF applied by backend)"),
  },
  async (args) => {
    if (!checkRateLimit("add_jira_comment", 20)) return rateLimitExceeded();

    try {
      await http.post("/api/jira/comment", {
        issueKey: args.issueKey,
        text:     args.text,
      });
      return text(`Comment posted to ${args.issueKey} successfully.`);
    } catch (err) {
      return errText(`add_jira_comment for ${args.issueKey}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: deploy_all
// Submits a full batch to the backend orchestrator. Rows are executed in
// stage order; same-stage rows run in parallel.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "deploy_all",
  "Submit a full deployment batch to the DeployMate backend orchestrator. Rows with the same stage number run in parallel; stages execute sequentially. Each row has its own steps configuration.",
  {
    ticket: z.string().optional().default("").describe("Jira ticket key applied to all rows"),
    rows: z.array(
      z.object({
        id:           z.string().describe("Unique row ID (generate a uuid if unknown)"),
        name:         z.string().describe("Display name"),
        repo:         z.string().describe("GitHub repo name"),
        type:         z.enum(["SDK", "SERVICE"]).describe("SDK or SERVICE — determines git_branch strategy: SERVICE uses tagName, SDK uses origin/targetBranch"),
        stage:        z.number().int().min(1).describe("Execution stage (1-based); same stage = parallel"),
        sourceBranch: z.string().describe("Branch to merge FROM"),
        targetBranch: z.string().describe("Branch to merge INTO"),
        jenkinsJob:   z.string().describe("Jenkins job path"),
        tagName:      z.string().describe("Pre-release tag name — required for SERVICE rows (used as git_branch value for Jenkins)"),
        steps:        stepsSchema.describe("Which steps to execute for this row"),
      })
    ).min(1).describe("Array of rows to deploy in stage order"),
  },
  async (args) => {
    if (!checkRateLimit("deploy_all", 5)) return rateLimitExceeded();

    try {
      await http.post("/api/deploy/all", {
        ticket: args.ticket,
        rows:   args.rows,
      });

      const byStage = new Map<number, number>();
      for (const r of args.rows) {
        byStage.set(r.stage, (byStage.get(r.stage) ?? 0) + 1);
      }
      const stagesSummary = Array.from(byStage.entries())
        .sort(([a], [b]) => a - b)
        .map(([s, n]) => `Stage ${s}: ${n} row(s)`)
        .join(", ");

      return text(
        `Deployment batch accepted. ${args.rows.length} row(s) queued. ` +
        `${stagesSummary}. ` +
        `Check the DeployMate UI or use get_log to monitor progress.`
      );
    } catch (err) {
      return errText("deploy_all", err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_next_tag
// Returns the suggested next pre-release tag for a repository.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_next_tag",
  "Get the suggested next pre-release tag for a GitHub repository and, optionally, the git_branch value from its last successful Jenkins build. Provide jenkinsJob to also see the last deployed tag.",
  {
    repo:       z.string().min(1).describe("GitHub repository name"),
    jenkinsJob: z.string().optional().describe("Jenkins job path (e.g. team/staging/my-service) — when provided, also returns the last deployed git_branch value"),
  },
  async (args) => {
    if (!checkRateLimit("get_next_tag")) return rateLimitExceeded();

    try {
      const params: Record<string, string> = { repo: args.repo };
      if (args.jenkinsJob) params.jenkinsJob = args.jenkinsJob;

      const res  = await http.get("/api/tag/next", { params });
      const data = res.data as { tagName?: string; previousTag?: string; lastDeployedTag?: string | null };

      const lines: string[] = [];
      lines.push(`Suggested next tag for ${args.repo}: ${data.tagName ?? "v1.0.0rc1"}`);
      if (data.previousTag) lines.push(`Latest GitHub tag: ${data.previousTag}`);
      if (data.lastDeployedTag) lines.push(`Last Jenkins deploy (git_branch): ${data.lastDeployedTag}`);
      else if (args.jenkinsJob)  lines.push(`Last Jenkins deploy: no successful build found for ${args.jenkinsJob}`);

      return text(lines.join("\n"));
    } catch (err) {
      return errText(`get_next_tag for ${args.repo}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_jenkins_categories
// Returns all saved Jenkins category names. Used to discover available Jenkins
// job folder prefixes before building a jenkinsJob path for deploy_all / trigger_pipeline.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_jenkins_categories",
  "List all Jenkins job categories (folder prefixes) that have been saved in DeployMate. Use this to discover the valid first segment of a jenkinsJob path (e.g. 'cross-products', 'platform') before calling trigger_pipeline or deploy_all.",
  {},
  async () => {
    if (!checkRateLimit("get_jenkins_categories")) return rateLimitExceeded();

    try {
      const res  = await http.get("/api/jenkins/categories");
      const cats = res.data as string[];
      if (!cats.length) {
        return text("No Jenkins categories saved yet. Add a service row in the UI to populate them.");
      }
      return text(`Saved Jenkins categories (${cats.length}): ${cats.join(", ")}`);
    } catch (err) {
      return errText("get_jenkins_categories", err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: save_jenkins_category
// Calls POST /api/jenkins/categories. Idempotent — no error if already exists.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "save_jenkins_category",
  "Save a Jenkins job category (folder prefix) to DeployMate's configuration. Idempotent — safe to call even if the category already exists.",
  {
    name: z.string().min(1).max(100).describe("Category name to save (e.g. 'cross-products', 'platform')"),
  },
  async (args) => {
    if (!checkRateLimit("save_jenkins_category")) return rateLimitExceeded();

    try {
      const res  = await http.post("/api/jenkins/categories", { name: args.name });
      const data = res.data as { name?: string };
      return text(`Jenkins category "${data.name ?? args.name}" saved.`);
    } catch (err) {
      return errText(`save_jenkins_category "${args.name}"`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_jenkins_service_names
// Calls GET /api/jenkins/service-names?category=...
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_jenkins_service_names",
  "List all Jenkins service names saved under a given category. Use together with get_jenkins_categories to construct a valid jenkinsJob path (format: category/env/serviceName).",
  {
    category: z.string().min(1).describe("Jenkins category name (e.g. 'cross-products')"),
  },
  async (args) => {
    if (!checkRateLimit("get_jenkins_service_names", 20)) return rateLimitExceeded();

    try {
      const res   = await http.get("/api/jenkins/service-names", { params: { category: args.category } });
      const names = res.data as string[];
      if (!names.length) {
        return text(`No service names saved under category "${args.category}" yet.`);
      }
      return text(`Service names under "${args.category}" (${names.length}): ${names.join(", ")}`);
    } catch (err) {
      return errText(`get_jenkins_service_names for category "${args.category}"`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: save_jenkins_service_name
// Calls POST /api/jenkins/service-names. Idempotent — no error if already exists.
// Creates the category automatically if it does not exist yet.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "save_jenkins_service_name",
  "Save a Jenkins service name under a category in DeployMate's configuration. Idempotent and creates the category if it does not already exist.",
  {
    category: z.string().min(1).max(100).describe("Jenkins category name (e.g. 'cross-products')"),
    name:     z.string().min(1).max(200).describe("Service name to save (e.g. 'payment-service')"),
  },
  async (args) => {
    if (!checkRateLimit("save_jenkins_service_name")) return rateLimitExceeded();

    try {
      await http.post("/api/jenkins/service-names", { category: args.category, name: args.name });
      return text(`Jenkins service name "${args.name}" saved under category "${args.category}".`);
    } catch (err) {
      return errText(`save_jenkins_service_name "${args.category}/${args.name}"`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_branch_commit_info
// Calls GET /api/github/branch-info?repo=...&branch=...
// Returns the latest commit SHA, author, message, and timestamp for a branch.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_branch_commit_info",
  "Fetch the latest commit details for a branch in a GitHub repository — SHA, author login, author name, commit message, and committed timestamp. Useful for verifying what is currently on a branch before merging or tagging.",
  {
    repo:   z.string().min(1).describe("GitHub repository name"),
    branch: z.string().min(1).describe("Branch name (e.g. 'PROJ-123' or 'env/staging')"),
  },
  async (args) => {
    if (!checkRateLimit("get_branch_commit_info", 20)) return rateLimitExceeded();

    try {
      const res  = await http.get("/api/github/branch-info", {
        params: { repo: args.repo, branch: args.branch },
      });
      const data = res.data as {
        sha:         string;
        shortSha:    string;
        authorLogin: string;
        authorName:  string;
        message:     string;
        committedAt: string;
      };
      const lines = [
        `Branch "${args.branch}" in ${args.repo}:`,
        `  SHA: ${data.shortSha} (${data.sha})`,
        `  Author: ${data.authorName}${data.authorLogin ? ` (@${data.authorLogin})` : ""}`,
        `  Message: ${data.message}`,
        `  Committed: ${data.committedAt}`,
      ];
      return text(lines.join("\n"));
    } catch (err) {
      return errText(`get_branch_commit_info for ${args.repo}/${args.branch}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_log
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "get_log",
  "Fetch recent deployment log lines from the DeployMate server. Optionally filter by service name.",
  {
    lines:  z.number().int().min(1).max(500).optional().default(50)
              .describe("Number of recent log lines to retrieve (max 500)"),
    filter: z.string().optional().describe("Case-insensitive substring filter on service name"),
  },
  async (args) => {
    if (!checkRateLimit("get_log", 30)) return rateLimitExceeded();

    try {
      const res  = await http.get("/api/log", { params: { lines: String(args.lines ?? 50) } });
      let lines  = res.data as string[];

      if (args.filter) {
        const needle = args.filter.toLowerCase();
        lines = lines.filter((l) => l.toLowerCase().includes(needle));
      }

      if (lines.length === 0) {
        return text(args.filter
          ? `No log entries matching "${args.filter}".`
          : "Log is empty."
        );
      }

      return text(lines.join("\n"));
    } catch (err) {
      return errText("get_log", err);
    }
  }
);

// ─── Start ────────────────────────────────────────────────────────────────────

async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(`[DeployMate MCP] Server started. API base: ${BASE}`);
}

main().catch((err: unknown) => {
  console.error("[DeployMate MCP] Fatal startup error:", err);
  process.exit(1);
});
