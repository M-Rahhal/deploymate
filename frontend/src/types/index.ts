// ─── Domain types ──────────────────────────────────────────────────────────

export type ServiceType = 'SDK' | 'SERVICE';

export type StepState =
  | 'IDLE'
  | 'SKIPPED'
  | 'RUNNING'
  | 'DONE'
  | 'FAILED'
  | 'CONFLICT';

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

/**
 * Per-service step configuration.
 * Each step is independently toggled.
 * Pipeline param: createTag=true → TAG=tagName, false → BRANCH=targetBranch.
 */
export interface ServiceRowSteps {
  mergeBranch:     boolean;
  createTag:       boolean;
  triggerPipeline: boolean;
  updateJira:      boolean;
}

/** Controls whether the env segment of the Jenkins URL is derived from type or entered directly. */
export type JenkinsEnvMode = 'type' | 'env';

export interface ServiceRow {
  id: string;
  name: string;
  repo: string;
  type: ServiceType;
  stage: number;
  sourceBranch: string;
  targetBranch: string;
  jenkinsJob: string;  // computed: /job/{category}/job/{env}/job/{serviceName}/

  // Jenkins URL builder parts
  jenkinsCategory:    string;  // first part, e.g. "cross-products"
  jenkinsEnvMode:     JenkinsEnvMode;  // 'type' → SDK=dev, SERVICE=staging | 'env' → explicit
  jenkinsEnv:         string;  // only used when jenkinsEnvMode === 'env' (dev | staging)
  jenkinsServiceName: string;  // third part, e.g. "payment-eligibility-service"

  tagName: string;
  steps: ServiceRowSteps;

  // Per-step runtime states
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
  timestamp: string;
  level:     'INFO' | 'WARN' | 'ERROR' | 'SUCCESS';
  service:   string;
  stage:     string;
  message:   string;
}

// ─── Computed overall state ────────────────────────────────────────────────

export function computeOverallState(row: ServiceRow): OverallState {
  const { mergeState, tagState, pipelineState, steps } = row;

  if (mergeState === 'CONFLICT') return 'CONFLICT';

  // Any configured step failed
  if (
    (steps.mergeBranch     && mergeState    === 'FAILED') ||
    (steps.createTag       && tagState      === 'FAILED') ||
    (steps.triggerPipeline && pipelineState === 'FAILED')
  ) return 'FAILED';

  // Pipeline in-progress
  if (steps.triggerPipeline && pipelineState === 'RUNNING') return 'PIPELINE_RUNNING';

  // All configured steps done?
  const mergeDone    = !steps.mergeBranch     || mergeState    === 'DONE' || mergeState    === 'SKIPPED';
  const tagDone      = !steps.createTag       || tagState      === 'DONE' || tagState      === 'SKIPPED';
  const pipelineDone = !steps.triggerPipeline || pipelineState === 'DONE' || pipelineState === 'SKIPPED';
  if (mergeDone && tagDone && pipelineDone) return 'DONE';

  // Tag in-progress / done
  if (steps.createTag && tagState === 'RUNNING') return 'TAGGING';
  if (steps.createTag && (tagState === 'DONE' || tagState === 'SKIPPED')) return 'TAGGED';

  // Merge in-progress / done
  if (steps.mergeBranch && mergeState === 'RUNNING') return 'MERGING';
  if (steps.mergeBranch && (mergeState === 'DONE' || mergeState === 'SKIPPED')) return 'MERGED';

  return 'IDLE';
}

// ─── API shapes (must match backend DTOs exactly) ──────────────────────────

export interface MergeRequest {
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
  gitBranch:  string;  // value for Jenkins git_branch param
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
  steps:        ServiceRowSteps;
}

export interface ApiError {
  error:   string;
  message: string;
  details?: string[];
}

// ─── SSE event payloads from GET /api/deploy/stream ───────────────────────

export interface DeployLogEvent {
  level:       ActivityLogEntry['level'];
  serviceName: string;
  stageLabel:  string;
  message:     string;
}

export interface DeployStepUpdateEvent {
  rowId:        string;
  step:         'merge' | 'tag' | 'pipeline';
  state:        StepState;
  commitSha?:   string;
  releaseUrl?:  string;
  buildUrl?:    string;
  buildNumber?: number;
  errorMessage?: string;
}

export interface DeployDoneEvent {
  success: boolean;
}
