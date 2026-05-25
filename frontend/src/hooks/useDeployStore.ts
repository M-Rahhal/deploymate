import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { v4 as uuid } from 'uuid';
import type { ServiceRow, GlobalConfig, ActivityLogEntry, StepState } from '@/types';
import { generateTagName } from '@/lib/utils';

interface DeployStore {
  globalConfig: GlobalConfig;
  rows:         ServiceRow[];
  activityLog:  ActivityLogEntry[];

  setGlobalConfig: (patch: Partial<GlobalConfig>) => void;
  addRow:          () => void;
  removeRow:       (id: string) => void;
  updateRow:       (id: string, patch: Partial<ServiceRow>) => void;
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

const blankRow = (globalConfig: GlobalConfig): ServiceRow => ({
  id:             uuid(),
  name:           '',
  repo:           '',
  type:           'SDK',
  stage:          1,
  sourceBranch:   globalConfig.defaultSourceBranch || globalConfig.ticket,
  targetBranch:   globalConfig.defaultTargetBranch || 'env/staging',
  jenkinsJob:     '',
  tagName:        '',
  updateJira:     true,
  skipMerge:      false,
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
        // Auto-generate tag name when repo changes for SERVICE rows
        if ('repo' in patch && row.type === 'SERVICE' && patch.repo) {
          row.tagName = generateTagName(patch.repo as string);
        }
        // Auto-switch tag name when type changes to SERVICE
        if ('type' in patch && patch.type === 'SERVICE' && row.repo && !row.tagName) {
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
        row.mergeState    = 'IDLE';
        row.tagState      = 'IDLE';
        row.pipelineState = 'IDLE';
        row.errorMessage  = null;
        row.buildUrl      = null;
        row.buildNumber   = null;
        row.buildLog      = null;
        row.mergeCommitSha = null;
        row.tagReleaseUrl  = null;
      }),

    resetAll: () =>
      set((s) => {
        s.rows.forEach((r) => {
          r.mergeState    = 'IDLE';
          r.tagState      = 'IDLE';
          r.pipelineState = 'IDLE';
          r.errorMessage  = null;
          r.buildUrl      = null;
          r.buildNumber   = null;
          r.buildLog      = null;
          r.mergeCommitSha = null;
          r.tagReleaseUrl  = null;
        });
        s.activityLog = [];
      }),
  }))
);
