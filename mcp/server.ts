/**
 * DeployMate MCP Server
 *
 * Exposes deployment tools to Claude via the Model Context Protocol (stdio).
 * This server holds NO credentials — it calls http://localhost:8080/api/... only.
 * The Spring Boot backend reads credentials from .env and performs all auth.
 *
 * Tools exposed:
 *   deploy_sdk      — merge branch + trigger Jenkins (SDK, uses BRANCH param)
 *   deploy_service  — merge + tag + trigger Jenkins (SERVICE, uses TAG param)
 *   deploy_all      — submit a full deployment batch to the backend orchestrator
 *   get_pipeline_status — poll a build queue item or build URL
 *   add_jira_comment    — post a comment to a Jira issue
 *   get_log             — tail the server-side deployment log
 */

import { McpServer }           from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z }                   from "zod";
import axios, { AxiosError }   from "axios";

// ─── Configuration ────────────────────────────────────────────────────────────

const BASE = process.env["DEPLOYMATE_API_URL"] ?? "http://localhost:8080";
const ORG  = process.env["GITHUB_ORG"]         ?? "";

// ─── Axios client ─────────────────────────────────────────────────────────────

const http = axios.create({
  baseURL: BASE,
  timeout: 30_000,
  headers: { "Content-Type": "application/json" },
});

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Redacts any credential-like tokens from text before returning to Claude.
 * Covers GitHub PATs, Basic-auth headers, Bearer tokens, and URL passwords.
 */
function sanitize(text: string): string {
  return text
    .replace(/ghp_[A-Za-z0-9]{36,}/g,          "[REDACTED-GH-TOKEN]")
    .replace(/ghs_[A-Za-z0-9]{36,}/g,          "[REDACTED-GH-TOKEN]")
    .replace(/Basic [A-Za-z0-9+/=]{20,}/g,     "[REDACTED-BASIC]")
    .replace(/Bearer [A-Za-z0-9._\-]{20,}/g,   "[REDACTED-BEARER]")
    .replace(/:[A-Za-z0-9._\-]{8,}@[a-z]/g,   "[REDACTED]@");
}

/** Build a text-only MCP content response. */
function text(msg: string) {
  return { content: [{ type: "text" as const, text: sanitize(msg) }] };
}

/** Format an Axios/generic error safely — never leaks auth details. */
function errText(context: string, err: unknown): ReturnType<typeof text> {
  if (err instanceof AxiosError) {
    const status  = err.response?.status ?? "network error";
    const apiMsg  = (err.response?.data as { message?: string } | undefined)?.message;
    const detail  = apiMsg ? `: ${apiMsg}` : "";
    return text(`${context} failed (HTTP ${status}${detail}). Check the DeployMate activity log.`);
  }
  return text(`${context} failed. Check the DeployMate activity log.`);
}

// ─── Per-tool rate limiter (max calls / rolling 60-second window) ─────────────

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
  return text("Rate limit exceeded (10 calls / minute). Wait a moment and retry.");
}

// ─── MCP server ───────────────────────────────────────────────────────────────

const server = new McpServer({
  name:    "deploymate",
  version: "1.0.0",
});

// ══════════════════════════════════════════════════════════════════════════════
// Tool: deploy_sdk
// Merges source → target branch, then triggers Jenkins with BRANCH=targetBranch.
// skipMerge=true skips the merge and goes straight to pipeline.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "deploy_sdk",
  "Merge a branch and trigger Jenkins for an SDK. Uses BRANCH as the pipeline parameter.",
  {
    name:         z.string().min(1).describe("Human-readable display name for the SDK"),
    repo:         z.string().min(1).describe("GitHub repository name (not full URL)"),
    sourceBranch: z.string().min(1).describe("Branch to merge FROM (e.g. feature/my-sdk)"),
    targetBranch: z.string().min(1).optional().default("env/staging")
                   .describe("Branch to merge INTO (default: env/staging)"),
    jenkinsJob:   z.string().min(1).describe("Jenkins job path (e.g. sdk-team/my-sdk-deploy)"),
    ticket:       z.string().optional().default("").describe("Jira ticket key (e.g. PROJ-123), optional"),
    skipMerge:    z.boolean().optional().default(false)
                   .describe("Skip merge+tag and go straight to pipeline with BRANCH parameter"),
    updateJira:   z.boolean().optional().default(false).describe("Post a Jira comment after deploy"),
  },
  async (args) => {
    if (!checkRateLimit("deploy_sdk")) return rateLimitExceeded();

    try {
      // Step 1: merge (unless skipped)
      if (!args.skipMerge) {
        const mergeRes = await http.post("/api/merge", {
          org:          ORG,
          repo:         args.repo,
          sourceBranch: args.sourceBranch,
          targetBranch: args.targetBranch,
          ticket:       args.ticket,
        });

        if ((mergeRes.data as { conflict?: boolean }).conflict) {
          return text(
            `Merge conflict in ${args.name} (${args.repo}). ` +
            `Resolve the conflict between ${args.sourceBranch} and ${args.targetBranch} manually, then retry.`
          );
        }
      }

      // Step 2: trigger pipeline with BRANCH parameter
      const pipeRes = await http.post("/api/pipeline/trigger", {
        jenkinsJob: args.jenkinsJob,
        paramType:  "BRANCH",
        paramValue: args.targetBranch,
      });
      const queueUrl = (pipeRes.data as { queueItemUrl?: string }).queueItemUrl;

      const mergeNote = args.skipMerge ? "(merge skipped, deploy-only)" : "merged successfully";
      return text(
        `SDK ${args.name}: ${mergeNote}. ` +
        `Jenkins job ${args.jenkinsJob} queued with BRANCH=${args.targetBranch}. ` +
        (queueUrl ? `Queue URL: ${queueUrl}` : "")
      );
    } catch (err) {
      return errText(`SDK deploy for ${args.name}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: deploy_service
// Merges source → target, creates a pre-release tag, then triggers Jenkins
// with TAG=tagName.  skipMerge=true bypasses merge+tag and uses BRANCH instead.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "deploy_service",
  "Merge branch, create a pre-release GitHub tag, and trigger Jenkins for a service. Uses TAG as the pipeline parameter (or BRANCH if skipMerge=true).",
  {
    name:         z.string().min(1).describe("Human-readable display name"),
    repo:         z.string().min(1).describe("GitHub repository name"),
    sourceBranch: z.string().min(1).describe("Branch to merge FROM"),
    targetBranch: z.string().min(1).optional().default("env/staging").describe("Branch to merge INTO"),
    jenkinsJob:   z.string().min(1).describe("Jenkins job path"),
    tagName:      z.string().min(1).describe("Explicit pre-release tag name (e.g. env-stag-20240601-my-svc-001)"),
    ticket:       z.string().optional().default("").describe("Jira ticket key, optional"),
    skipMerge:    z.boolean().optional().default(false)
                   .describe("Skip merge+tag; trigger pipeline with BRANCH instead of TAG"),
    updateJira:   z.boolean().optional().default(false),
  },
  async (args) => {
    if (!checkRateLimit("deploy_service")) return rateLimitExceeded();

    try {
      let releaseUrl: string | undefined;

      if (!args.skipMerge) {
        // Step 1: merge
        const mergeRes = await http.post("/api/merge", {
          org:          ORG,
          repo:         args.repo,
          sourceBranch: args.sourceBranch,
          targetBranch: args.targetBranch,
          ticket:       args.ticket,
        });
        if ((mergeRes.data as { conflict?: boolean }).conflict) {
          return text(
            `Merge conflict in ${args.name} (${args.repo}). ` +
            `Resolve ${args.sourceBranch} ↔ ${args.targetBranch} manually, then retry.`
          );
        }

        // Step 2: create pre-release tag
        const tagRes = await http.post("/api/tag", {
          org:          ORG,
          repo:         args.repo,
          tagName:      args.tagName,
          targetBranch: args.targetBranch,
          ticket:       args.ticket,
        });
        releaseUrl = (tagRes.data as { releaseUrl?: string }).releaseUrl;
      }

      // Step 3: trigger pipeline
      const paramType = args.skipMerge ? "BRANCH" : "TAG";
      const paramVal  = args.skipMerge ? args.targetBranch : args.tagName;

      const pipeRes = await http.post("/api/pipeline/trigger", {
        jenkinsJob: args.jenkinsJob,
        paramType,
        paramValue: paramVal,
      });
      const queueUrl = (pipeRes.data as { queueItemUrl?: string }).queueItemUrl;

      const summary = args.skipMerge
        ? `Service ${args.name}: merge skipped (deploy-only).`
        : `Service ${args.name}: merged and tagged as ${args.tagName}.` +
          (releaseUrl ? ` Release: ${releaseUrl}` : "");

      return text(
        `${summary} Jenkins job ${args.jenkinsJob} queued with ${paramType}=${paramVal}. ` +
        (queueUrl ? `Queue URL: ${queueUrl}` : "")
      );
    } catch (err) {
      return errText(`Service deploy for ${args.name}`, err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: deploy_all
// Submits a full deployment batch (multiple SDKs + services) to the backend
// orchestrator, which handles stage ordering and parallel execution.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "deploy_all",
  "Submit a full deployment batch to the DeployMate backend orchestrator. Handles SDK stage ordering and service deployment automatically.",
  {
    ticket: z.string().optional().default("").describe("Jira ticket key applied to all services"),
    rows: z.array(
      z.object({
        id:           z.string().describe("Unique row ID (generate a uuid if unknown)"),
        name:         z.string().describe("Display name"),
        repo:         z.string().describe("GitHub repo name"),
        type:         z.enum(["SDK", "SERVICE"]).describe("SDK or SERVICE"),
        stage:        z.number().int().min(1).describe("SDK deployment stage (1-based); ignored for SERVICE"),
        sourceBranch: z.string().describe("Branch to merge FROM"),
        targetBranch: z.string().describe("Branch to merge INTO"),
        jenkinsJob:   z.string().describe("Jenkins job path"),
        tagName:      z.string().describe("Pre-release tag name (SERVICE only)"),
        updateJira:   z.boolean().describe("Post Jira comment after deploy"),
        skipMerge:    z.boolean().describe("Skip merge+tag, deploy-only"),
      })
    ).min(1).describe("Array of services to deploy"),
  },
  async (args) => {
    if (!checkRateLimit("deploy_all", 5)) return rateLimitExceeded();

    try {
      await http.post("/api/deploy/all", {
        ticket: args.ticket,
        rows:   args.rows,
      });

      const sdkCount = args.rows.filter((r) => r.type === "SDK").length;
      const svcCount = args.rows.filter((r) => r.type === "SERVICE").length;

      return text(
        `Deployment batch accepted by DeployMate. ` +
        `${sdkCount} SDK(s) and ${svcCount} service(s) queued. ` +
        `Check the DeployMate UI or use get_log to monitor progress.`
      );
    } catch (err) {
      return errText("deploy_all", err);
    }
  }
);

// ══════════════════════════════════════════════════════════════════════════════
// Tool: get_pipeline_status
// Polls either a Jenkins queue item URL (after trigger) or a build URL
// (after the build starts) and returns the current state.
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
// Posts a deployment summary comment to a Jira issue.
// ══════════════════════════════════════════════════════════════════════════════
server.tool(
  "add_jira_comment",
  "Post a deployment summary comment to a Jira issue.",
  {
    issueKey: z.string().regex(/^[A-Z]{1,20}-[0-9]{1,10}$/).describe("Jira issue key (e.g. PROJ-123)"),
    text:     z.string().min(1).max(5000).describe("Comment text (plain text; Atlassian Document Format applied by backend)"),
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
// Tool: get_log
// Returns recent lines from the server-side deployment log, optionally
// filtered by a service name substring.
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
  // Log to stderr so it doesn't pollute the stdio MCP channel
  console.error(`[DeployMate MCP] Server started. API base: ${BASE}, org: ${ORG || "(unset)"}`);
}

main().catch((err: unknown) => {
  console.error("[DeployMate MCP] Fatal startup error:", err);
  process.exit(1);
});
