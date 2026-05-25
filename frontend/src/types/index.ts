// ─── Domain types ──────────────────────────────────────────────────────────

export type ServiceType = 'SDK' | 'SERVICE';

export type StepState =
  | 'IDLE'
  | 'SKIPPED'    // Step was intentionally skipped (e.g. skipMerge=true)
  | 'RUNNING'
  | 'DONE'
  | 'FAILED'
  | 'CONFLICT';  // Merge-specific

export type OverallState =
  | 'IDLE'
  | 'MERGING'
  | 'CONFLICT'
  | 'MERGED'
  | 'TAGGING'
  | 'TAGGED'
  | 'PIPELINE_QUEUED'
  | 'PIPELINE_RUNNING'
  | 'DONE'
  | 'FAILED';

export interface ServiceRow {
  id: string;
  name: string;           // Human-readable display name
  repo: string;           // GitHub repo name (exact)
  type: ServiceType;
  stage: number;          // 1-based; ignored for SERVICE type
  sourceBranch: string;   // Merge FROM
  targetBranch: string;   // Merge INTO
  jenkinsJob: string;     // e.g. "backend/my-sdk-deploy"
  tagName: string;        // Auto-generated, editable; used for SERVICE only
  updateJira: boolean;
  skipMerge: boolean;     // When true: skip merge+tag, go straight to pipeline with BRANCH param

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

export interface GlobalConfig {
  ticket:              string;
  defaultSourceBranch: string;
  defaultTargetBranch: string;
}

export interface ActivityLogEntry {
  id:        string;
  timestamp: string;  // ISO 8601
  level:     'INFO' | 'WARN' | 'ERROR' | 'SUCCESS';
  service:   string;  // display name or "SYSTEM"
  stage:     string;  // "Stage 1", "Service", "SYSTEM"
  message:   string;
}

// ─── Computed overall state ────────────────────────────────────────────────

export function computeOverallState(row: ServiceRow): OverallState {
  const { mergeState, tagState, pipelineState, type } = row;

  if (mergeState === 'CONFLICT') return 'CONFLICT';
  if (mergeState === 'FAILED' || tagState === 'FAILED' || pipelineState === 'FAILED') return 'FAILED';

  if (pipelineState === 'RUNNING') return 'PIPELINE_RUNNING';
  if (pipelineState === 'DONE')    return 'DONE';

  if (type === 'SERVICE') {
    if (tagState === 'RUNNING') return 'TAGGING';
    if (tagState === 'DONE')    return 'TAGGED';
  }

  if (mergeState === 'RUNNING')                             return 'MERGING';
  if (mergeState === 'DONE' || mergeState === 'SKIPPED')    return 'MERGED';

  return 'IDLE';
}

// ─── API shapes (must match backend DTOs exactly) ──────────────────────────

export interface MergeRequest {
  org:          string;
  repo:         string;
  sourceBranch: string;
  targetBranch: string;
  ticket:       string;
}

export interface MergeResponse {
  success:  boolean;
  conflict: boolean;
  sha:      string | null;
  message:  string;
}

export interface TagRequest {
  org:          string;
  repo:         string;
  tagName:      string;
  targetBranch: string;
  ticket:       string;
}

export interface TagResponse {
  success:    boolean;
  tagName:    string;
  sha:        string | null;
  releaseUrl: string | null;
  message:    string;
}

export interface PipelineTriggerRequest {
  jenkinsJob: string;
  paramType:  'BRANCH' | 'TAG';
  paramValue: string;
}

export interface PipelineTriggerResponse {
  success:      boolean;
  queueItemUrl: string | null;
  message:      string;
}

export type BuildState = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILURE' | 'ABORTED' | 'UNKNOWN';

export interface PipelineStatusResponse {
  state:       BuildState;
  buildUrl:    string | null;
  buildNumber: number | null;
  logFragment: string | null;
  message:     string;
}

export interface ServiceRowDto {
  id:           string;
  name:         string;
  repo:         string;
  type:         ServiceType;
  stage:        number;
  sourceBranch: string;
  targetBranch: string;
  jenkinsJob:   string;
  tagName:      string;
  updateJira:   boolean;
  skipMerge:    boolean;
}

export interface ApiError {
  error:   string;
  message: string;
  details?: string[];
}
