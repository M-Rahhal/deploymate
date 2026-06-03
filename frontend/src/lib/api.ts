/**
 * Typed fetch wrappers for every backend endpoint.
 * All functions throw an Error with a human-readable message on failure.
 */

import type {
  MergeRequest, MergeResponse,
  TagRequest, TagResponse,
  PipelineTriggerRequest, PipelineTriggerResponse,
  PipelineStatusResponse,
  ServiceRowDto,
} from '@/types';

const BASE = '/api';

async function post<TReq, TRes>(path: string, body: TReq): Promise<TRes> {
  const res = await fetch(`${BASE}${path}`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({ message: res.statusText }));
  if (!res.ok) {
    const msg = (data as { message?: string }).message ?? `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data as TRes;
}

async function get<TRes>(path: string, params?: Record<string, string>): Promise<TRes> {
  const url = new URL(`${BASE}${path}`, window.location.origin);
  if (params) {
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
  }
  const res  = await fetch(url.toString());
  const data = await res.json().catch(() => ({ message: res.statusText }));
  if (!res.ok) {
    const msg = (data as { message?: string }).message ?? `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data as TRes;
}

// ─── Merge ─────────────────────────────────────────────────────────────────

export async function apiBranchMerge(req: MergeRequest): Promise<MergeResponse> {
  // 409 Conflict is a valid business response — don't let post() throw on it
  const res = await fetch(`${BASE}/merge`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(req),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 409) return data as MergeResponse;
  if (!res.ok) throw new Error((data as { message?: string }).message ?? `HTTP ${res.status}`);
  return data as MergeResponse;
}

// ─── Tag ───────────────────────────────────────────────────────────────────

export async function apiCreateTag(req: TagRequest): Promise<TagResponse> {
  return post<TagRequest, TagResponse>('/tag', req);
}

// ─── Pipeline ──────────────────────────────────────────────────────────────

export async function apiTriggerPipeline(req: PipelineTriggerRequest): Promise<PipelineTriggerResponse> {
  return post<PipelineTriggerRequest, PipelineTriggerResponse>('/pipeline/trigger', req);
}

export async function apiPipelineStatus(
  queueItemUrl?: string,
  buildUrl?: string
): Promise<PipelineStatusResponse> {
  const params: Record<string, string> = {};
  if (queueItemUrl) params['queueItemUrl'] = queueItemUrl;
  if (buildUrl)     params['buildUrl']     = buildUrl;
  return get<PipelineStatusResponse>('/pipeline/status', params);
}

// ─── Jira ──────────────────────────────────────────────────────────────────

export async function apiJiraComment(issueKey: string, text: string): Promise<void> {
  await post('/jira/comment', { issueKey, text });
}

// ─── Deploy All ────────────────────────────────────────────────────────────

export async function apiDeployAll(ticket: string, rows: ServiceRowDto[]): Promise<void> {
  await post('/deploy/all', { ticket, rows });
}

// ─── Log ───────────────────────────────────────────────────────────────────

export async function apiGetLog(lines = 200): Promise<string[]> {
  return get<string[]>('/log', { lines: String(lines) });
}

// ─── Tag — next tag suggestion ─────────────────────────────────────────────

export async function apiGetNextTag(
  repo: string,
  jenkinsJob?: string
): Promise<{ tagName: string; previousTag: string; lastDeployedTag: string | null }> {
  const params: Record<string, string> = { repo };
  if (jenkinsJob) params.jenkinsJob = jenkinsJob;
  return get<{ tagName: string; previousTag: string; lastDeployedTag: string | null }>('/tag/next', params);
}

// ─── GitHub — branch commit info ───────────────────────────────────────────

export interface BranchInfo {
  sha:         string;
  shortSha:    string;
  authorLogin: string;
  authorName:  string;
  message:     string;
  committedAt: string;
}

export async function apiGetBranchInfo(repo: string, branch: string): Promise<BranchInfo> {
  return get<BranchInfo>('/github/branch-info', { repo, branch });
}

// ─── Jenkins config (categories + service names) ───────────────────────────

export async function apiGetCategories(): Promise<string[]> {
  return get<string[]>('/jenkins/categories');
}

export async function apiSaveCategory(name: string): Promise<void> {
  await post('/jenkins/categories', { name });
}

export async function apiGetServiceNames(category: string): Promise<string[]> {
  return get<string[]>('/jenkins/service-names', { category });
}

export async function apiSaveServiceName(category: string, name: string): Promise<void> {
  await post('/jenkins/service-names', { category, name });
}
