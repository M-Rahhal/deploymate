/**
 * Deploy action hooks — each hook wraps an API call and updates Zustand state.
 * Used by individual step buttons and the All Steps button.
 */

import { useCallback, useState } from 'react';
import { toast } from 'sonner';
import { useDeployStore } from './useDeployStore';
import {
  apiBranchMerge,
  apiCreateTag,
  apiTriggerPipeline,
  apiPipelineStatus,
} from '@/lib/api';
import type { ServiceRow } from '@/types';

function useRunning() {
  const [running, setRunning] = useState(false);
  return { running, setRunning };
}

/** Runs the merge step for one row */
export function useMergeAction(row: ServiceRow, org: string) {
  const { setStepState, appendLog, globalConfig } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);
    setStepState(row.id, 'merge', 'RUNNING');
    appendLog({ level: 'INFO', service: row.name || row.repo, stage: `Stage ${row.stage}`,
      message: `Merging ${row.sourceBranch} → ${row.targetBranch}` });

    try {
      const res = await apiBranchMerge({
        org, repo: row.repo,
        sourceBranch: row.sourceBranch,
        targetBranch: row.targetBranch,
        ticket: globalConfig.ticket,
      });

      if (res.conflict) {
        setStepState(row.id, 'merge', 'CONFLICT', {
          errorMessage: 'Merge conflict. Resolve manually and retry.',
        });
        appendLog({ level: 'ERROR', service: row.name || row.repo, stage: `Stage ${row.stage}`,
          message: 'Merge conflict detected' });
        toast.warning(`Merge conflict in ${row.name || row.repo} — action required`);
      } else {
        setStepState(row.id, 'merge', 'DONE', { mergeCommitSha: res.sha ?? null });
        appendLog({ level: 'SUCCESS', service: row.name || row.repo, stage: `Stage ${row.stage}`,
          message: `Merged successfully (SHA: ${res.sha?.slice(0, 7) ?? 'n/a'})` });
        toast.success(`${row.name || row.repo} merged successfully`);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setStepState(row.id, 'merge', 'FAILED', { errorMessage: msg });
      appendLog({ level: 'ERROR', service: row.name || row.repo, stage: `Stage ${row.stage}`,
        message: `Merge failed: ${msg}` });
      toast.error(`Merge failed for ${row.name || row.repo}`);
    } finally {
      setRunning(false);
    }
  }, [row, org, running, setRunning, setStepState, appendLog, globalConfig.ticket]);

  return { run, running };
}

/** Runs the tag step for one SERVICE row */
export function useTagAction(row: ServiceRow, org: string) {
  const { setStepState, appendLog, globalConfig } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);
    setStepState(row.id, 'tag', 'RUNNING');
    appendLog({ level: 'INFO', service: row.name || row.repo, stage: 'Service',
      message: `Creating pre-release tag: ${row.tagName}` });

    try {
      const res = await apiCreateTag({
        org, repo: row.repo,
        tagName: row.tagName,
        targetBranch: row.targetBranch,
        ticket: globalConfig.ticket,
      });
      setStepState(row.id, 'tag', 'DONE', { tagReleaseUrl: res.releaseUrl ?? null });
      appendLog({ level: 'SUCCESS', service: row.name || row.repo, stage: 'Service',
        message: `Tag ${res.tagName} created` });
      toast.success(`Tag ${res.tagName} created for ${row.name || row.repo}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setStepState(row.id, 'tag', 'FAILED', { errorMessage: msg });
      appendLog({ level: 'ERROR', service: row.name || row.repo, stage: 'Service',
        message: `Tag failed: ${msg}` });
      toast.error(`Tag creation failed for ${row.name || row.repo}`);
    } finally {
      setRunning(false);
    }
  }, [row, org, running, setRunning, setStepState, appendLog, globalConfig.ticket]);

  return { run, running };
}

/** Triggers Jenkins and polls until the build finishes */
export function usePipelineAction(row: ServiceRow) {
  const { setStepState, appendLog } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);

    const useTag    = row.type === 'SERVICE' && !row.skipMerge;
    const paramType = useTag ? 'TAG' : 'BRANCH';
    const paramVal  = useTag ? row.tagName : row.targetBranch;
    const label     = row.type === 'SERVICE' ? 'Service' : `Stage ${row.stage}`;

    setStepState(row.id, 'pipeline', 'RUNNING');
    appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
      message: `Triggering ${row.jenkinsJob} (${paramType}=${paramVal})` });

    try {
      const trig = await apiTriggerPipeline({
        jenkinsJob: row.jenkinsJob,
        paramType,
        paramValue: paramVal,
      });

      appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
        message: 'Build queued — polling...' });

      // Poll until DONE/FAILURE/ABORTED
      let queueItemUrl: string | null = trig.queueItemUrl;
      let buildUrl:     string | null = null;
      let buildNumber:  number | null = null;

      const poll = async (): Promise<boolean> => {
        const status = await apiPipelineStatus(
          queueItemUrl ?? undefined,
          buildUrl ?? undefined
        );

        if (status.buildUrl && !buildUrl) {
          buildUrl    = status.buildUrl;
          buildNumber = status.buildNumber;
          queueItemUrl = null;   // switch to polling by buildUrl
          setStepState(row.id, 'pipeline', 'RUNNING', { buildUrl, buildNumber });
          appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
            message: `Build #${buildNumber} started — ${buildUrl}` });
        }

        if (status.state === 'QUEUED' || status.state === 'RUNNING') {
          // keep polling
          await new Promise((r) => setTimeout(r, 5000));
          return poll();
        }

        if (status.state === 'SUCCESS') {
          setStepState(row.id, 'pipeline', 'DONE', {
            buildLog: status.logFragment ?? null,
          });
          appendLog({ level: 'SUCCESS', service: row.name || row.repo, stage: label,
            message: `Build #${buildNumber} SUCCESS ✅` });
          toast.success(`Build #${buildNumber} passed for ${row.name || row.repo}`);
          return true;
        } else {
          setStepState(row.id, 'pipeline', 'FAILED', {
            errorMessage: `Build result: ${status.state}`,
            buildLog: status.logFragment ?? null,
          });
          appendLog({ level: 'ERROR', service: row.name || row.repo, stage: label,
            message: `Build #${buildNumber} ${status.state} ❌` });
          toast.error(`Build #${buildNumber} failed for ${row.name || row.repo}`);
          return false;
        }
      };

      await poll();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setStepState(row.id, 'pipeline', 'FAILED', { errorMessage: msg });
      appendLog({ level: 'ERROR', service: row.name || row.repo, stage: label,
        message: `Pipeline failed: ${msg}` });
      toast.error(`Pipeline failed for ${row.name || row.repo}`);
    } finally {
      setRunning(false);
    }
  }, [row, running, setRunning, setStepState, appendLog]);

  return { run, running };
}

/** Runs all applicable steps in sequence for one row */
export function useAllStepsAction(row: ServiceRow, org: string) {
  const { running: mRunning, run: runMerge }    = useMergeAction(row, org);
  const { running: tRunning, run: runTag }      = useTagAction(row, org);
  const { running: pRunning, run: runPipeline } = usePipelineAction(row);

  const running = mRunning || tRunning || pRunning;

  const runAll = useCallback(async () => {
    if (running) return;

    // Merge (skipped automatically if skipMerge=true or already DONE)
    if (!row.skipMerge && row.mergeState !== 'DONE') {
      await runMerge();
      // Read latest state from store after the call completes
      const freshRow = useDeployStore.getState().rows.find((r) => r.id === row.id);
      if (!freshRow || (freshRow.mergeState !== 'DONE')) return;
    }

    // Tag (SERVICE only, skipMerge=false, not already DONE)
    if (row.type === 'SERVICE' && !row.skipMerge && row.tagState !== 'DONE') {
      await runTag();
      const freshRow = useDeployStore.getState().rows.find((r) => r.id === row.id);
      if (!freshRow || freshRow.tagState !== 'DONE') return;
    }

    // Pipeline
    if (row.pipelineState !== 'DONE') {
      await runPipeline();
    }
  }, [row, running, runMerge, runTag, runPipeline]);

  return { runAll, running };
}
