import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { v4 as uuid } from 'uuid';
import type { ServiceRow, GlobalConfig, ActivityLogEntry, StepState, ServiceRowSteps } from '@/types';
import { generateTagName } from '@/lib/utils';
import { apiGetNextTag } from '@/lib/api';

interface DeployStore {
  globalConfig: GlobalConfig;
  rows:         ServiceRow[];
  activityLog:  ActivityLogEntry[];

  setGlobalConfig: (patch: Partial<GlobalConfig>) => void;
  addRow:          () => void;
  removeRow:       (id: string) => void;
  updateRow:       (id: string, patch: Partial<ServiceRow>) => void;
  updateSteps:     (id: string, patch: Partial<ServiceRowSteps>) => void;
  setStepState:    (
    id:    string,
    step:  'merge' | 'tag' | 'pipeline',
    state: StepState,
    extra?: Partial<ServiceRow>
  ) => void;
  appendLog:  (entry: Omit<ActivityLogEntry, 'id' | 'timestamp'>) => void;
  resetRow:   (id: string) => void;
  resetAll:   () => void;
}

const defaultSteps = (type: 'SDK' | 'SERVICE'): ServiceRowSteps => ({
  mergeBranch:     true,
  createTag:       type === 'SERVICE',
  triggerPipeline: true,
  updateJira:      true,
});

// Returns simple slash-separated path; JenkinsService converts to /job/x/job/y/... internally
function buildJenkinsJob(category: string, env: string, serviceName: string): string {
  if (!category || !env || !serviceName) return '';
  return `${category}/${env}/${serviceName}`;
}

const blankRow = (globalConfig: GlobalConfig): ServiceRow => ({
  id:             uuid(),
  name:           '',
  repo:           '',
  type:           'SDK',
  stage:          1,
  sourceBranch:   globalConfig.defaultSourceBranch || globalConfig.ticket,
  targetBranch:   globalConfig.defaultTargetBranch || 'env/staging',
  jenkinsJob:     '',
  jenkinsCategory:    '',
  jenkinsEnvMode:     'type',
  jenkinsEnv:         'dev',
  jenkinsServiceName: '',
  tagName:        '',
  steps:          defaultSteps('SDK'),
  mergeState:     'IDLE',
  tagState:       'IDLE',
  pipelineState:  'IDLE',
  mergeCommitSha: null,
  tagReleaseUrl:  null,
  buildUrl:       null,
  buildNumber:    null,
  buildLog:       null,
  errorMessage:   null,
});

function resolvedEnv(row: ServiceRow): string {
  if (row.jenkinsEnvMode === 'env') return row.jenkinsEnv || '';
  return row.type === 'SERVICE' ? 'staging' : 'dev';
}

export const useDeployStore = create<DeployStore>()(
  immer((set) => ({
    globalConfig: {
      ticket:              '',
      defaultSourceBranch: '',
      defaultTargetBranch: 'env/staging',
    },
    rows:        [],
    activityLog: [],

    setGlobalConfig: (patch) =>
      set((s) => { Object.assign(s.globalConfig, patch); }),

    addRow: () =>
      set((s) => { s.rows.push(blankRow(s.globalConfig)); }),

    removeRow: (id) =>
      set((s) => { s.rows = s.rows.filter((r) => r.id !== id); }),

    updateRow: (id, patch) =>
      set((s) => {
        const row = s.rows.find((r) => r.id === id);
        if (!row) return;
        Object.assign(row, patch);

        // When repo changes on a SERVICE row with createTag → fetch suggested next tag from GitHub
        if ('repo' in patch && patch.repo && row.steps.createTag) {
          const repo = patch.repo as string;
          // Optimistic placeholder while fetching
          row.tagName = generateTagName(repo);
          // Async fetch (Immer draft is sealed by the time the promise resolves,
          // so we call set() again from outside the draft)
          apiGetNextTag(repo).then(({ tagName }) => {
            if (tagName) {
              useDeployStore.getState().updateRow(id, { tagName });
            }
          }).catch(() => {});
        }

        // Apply default steps when type changes
        if ('type' in patch) {
          const newType = patch.type as 'SDK' | 'SERVICE';
          row.steps.createTag = newType === 'SERVICE';
          if (newType === 'SERVICE' && row.repo) {
            // Fetch suggested tag for current repo
            apiGetNextTag(row.repo).then(({ tagName }) => {
              if (tagName) useDeployStore.getState().updateRow(id, { tagName });
            }).catch(() => {});
          }
          // Recompute jenkinsJob if using type-based env mode
          if (row.jenkinsEnvMode === 'type') {
            const env = newType === 'SERVICE' ? 'staging' : 'dev';
            row.jenkinsJob = buildJenkinsJob(row.jenkinsCategory, env, row.jenkinsServiceName);
          }
        }
        // Recompute jenkinsJob whenever builder parts change
        if ('jenkinsCategory' in patch || 'jenkinsEnvMode' in patch ||
            'jenkinsEnv' in patch || 'jenkinsServiceName' in patch) {
          const env = resolvedEnv(row);
          row.jenkinsJob = buildJenkinsJob(row.jenkinsCategory, env, row.jenkinsServiceName);
        }
      }),

    updateSteps: (id, patch) =>
      set((s) => {
        const row = s.rows.find((r) => r.id === id);
        if (!row) return;
        Object.assign(row.steps, patch);
        // Clear tag name when createTag is disabled
        if ('createTag' in patch && !patch.createTag) {
          row.tagName = '';
        }
        // Auto-generate tag name when createTag is enabled and repo is set
        if ('createTag' in patch && patch.createTag && row.repo && !row.tagName) {
          row.tagName = generateTagName(row.repo);
        }
      }),

    setStepState: (id, step, state, extra) =>
      set((s) => {
        const row = s.rows.find((r) => r.id === id);
        if (!row) return;
        if (step === 'merge')    row.mergeState    = state;
        if (step === 'tag')      row.tagState      = state;
        if (step === 'pipeline') row.pipelineState = state;
        if (extra) Object.assign(row, extra);
      }),

    appendLog: (entry) =>
      set((s) => {
        s.activityLog.push({
          id:        uuid(),
          timestamp: new Date().toISOString(),
          ...entry,
        });
      }),

    resetRow: (id) =>
      set((s) => {
        const row = s.rows.find((r) => r.id === id);
        if (!row) return;
        row.mergeState     = 'IDLE';
        row.tagState       = 'IDLE';
        row.pipelineState  = 'IDLE';
        row.errorMessage   = null;
        row.buildUrl       = null;
        row.buildNumber    = null;
        row.buildLog       = null;
        row.mergeCommitSha = null;
        row.tagReleaseUrl  = null;
      }),

    resetAll: () =>
      set((s) => {
        s.rows.forEach((r) => {
          r.mergeState     = 'IDLE';
          r.tagState       = 'IDLE';
          r.pipelineState  = 'IDLE';
          r.errorMessage   = null;
          r.buildUrl       = null;
          r.buildNumber    = null;
          r.buildLog       = null;
          r.mergeCommitSha = null;
          r.tagReleaseUrl  = null;
        });
        s.activityLog = [];
      }),
  }))
);
