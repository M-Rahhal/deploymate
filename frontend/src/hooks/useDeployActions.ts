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

/** Merges source → target for one row */
export function useMergeAction(row: ServiceRow) {
  const { setStepState, appendLog, globalConfig } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);

    try {
      // Same branch → no merge needed, treat as already done
      if (row.sourceBranch && row.sourceBranch === row.targetBranch) {
        appendLog({ level: 'INFO', service: row.name || row.repo, stage: `Stage ${row.stage}`,
          message: `Source = target branch (${row.sourceBranch}) — assumed already merged` });
        setStepState(row.id, 'merge', 'DONE', { mergeCommitSha: null });
        toast.info(`${row.name || row.repo}: same branch, assumed already merged`);
        return;
      }

      setStepState(row.id, 'merge', 'RUNNING');
      appendLog({ level: 'INFO', service: row.name || row.repo, stage: `Stage ${row.stage}`,
        message: `Merging ${row.sourceBranch} → ${row.targetBranch}` });

      const res = await apiBranchMerge({
        repo: row.repo,
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
  }, [row, running, setRunning, setStepState, appendLog, globalConfig.ticket]);

  return { run, running };
}

/** Creates a pre-release tag for one row */
export function useTagAction(row: ServiceRow) {
  const { setStepState, appendLog, globalConfig } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);
    setStepState(row.id, 'tag', 'RUNNING');
    appendLog({ level: 'INFO', service: row.name || row.repo, stage: `Stage ${row.stage}`,
      message: `Creating pre-release tag: ${row.tagName}` });

    try {
      const res = await apiCreateTag({
        repo: row.repo,
        tagName: row.tagName,
        targetBranch: row.targetBranch,
        ticket: globalConfig.ticket,
      });
      setStepState(row.id, 'tag', 'DONE', { tagReleaseUrl: res.releaseUrl ?? null });
      appendLog({ level: 'SUCCESS', service: row.name || row.repo, stage: `Stage ${row.stage}`,
        message: `Tag ${res.tagName} created` });
      toast.success(`Tag ${res.tagName} created for ${row.name || row.repo}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setStepState(row.id, 'tag', 'FAILED', { errorMessage: msg });
      appendLog({ level: 'ERROR', service: row.name || row.repo, stage: `Stage ${row.stage}`,
        message: `Tag failed: ${msg}` });
      toast.error(`Tag creation failed for ${row.name || row.repo}`);
    } finally {
      setRunning(false);
    }
  }, [row, running, setRunning, setStepState, appendLog, globalConfig.ticket]);

  return { run, running };
}

/** Triggers Jenkins and polls until the build finishes */
export function usePipelineAction(row: ServiceRow) {
  const { setStepState, appendLog } = useDeployStore();
  const { running, setRunning } = useRunning();

  const run = useCallback(async () => {
    if (running) return;
    setRunning(true);

    // SERVICE → git_branch=tagName (deploy from pre-release tag; tag must be set)
    // SDK     → git_branch=origin/targetBranch (build from branch)
    const label = `Stage ${row.stage}`;
    let gitBranch: string;
    if (row.type === 'SERVICE') {
      if (!row.tagName) {
        setStepState(row.id, 'pipeline', 'FAILED', {
          errorMessage: 'A tag name is required for SERVICE rows. Fetch or enter a tag first.',
        });
        appendLog({ level: 'ERROR', service: row.name || row.repo, stage: label,
          message: 'Pipeline failed: tagName is required for SERVICE rows' });
        toast.error(`${row.name || row.repo}: tag name is required before triggering the pipeline`);
        setRunning(false);
        return;
      }
      gitBranch = row.tagName;
    } else {
      gitBranch = `origin/${row.targetBranch}`;
    }

    setStepState(row.id, 'pipeline', 'RUNNING');
    appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
      message: `Triggering ${row.jenkinsJob} (git_branch=${gitBranch})` });

    try {
      const trig = await apiTriggerPipeline({
        jenkinsJob: row.jenkinsJob,
        gitBranch,
      });

      appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
        message: 'Build queued — polling...' });

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
          queueItemUrl = null;
          setStepState(row.id, 'pipeline', 'RUNNING', { buildUrl, buildNumber });
          appendLog({ level: 'INFO', service: row.name || row.repo, stage: label,
            message: `Build #${buildNumber} started — ${buildUrl}` });
        }

        if (status.state === 'QUEUED' || status.state === 'RUNNING') {
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

/** Runs all configured steps in sequence for one row */
export function useAllStepsAction(row: ServiceRow) {
  const { running: mRunning, run: runMerge }    = useMergeAction(row);
  const { running: tRunning, run: runTag }      = useTagAction(row);
  const { running: pRunning, run: runPipeline } = usePipelineAction(row);

  const running = mRunning || tRunning || pRunning;

  const runAll = useCallback(async () => {
    if (running) return;

    if (row.steps.mergeBranch && row.mergeState !== 'DONE') {
      await runMerge();
      const freshRow = useDeployStore.getState().rows.find((r) => r.id === row.id);
      if (!freshRow || freshRow.mergeState !== 'DONE') return;
    }

    if (row.steps.createTag && row.tagState !== 'DONE') {
      await runTag();
      const freshRow = useDeployStore.getState().rows.find((r) => r.id === row.id);
      if (!freshRow || freshRow.tagState !== 'DONE') return;
    }

    if (row.steps.triggerPipeline && row.pipelineState !== 'DONE') {
      await runPipeline();
    }
  }, [row, running, runMerge, runTag, runPipeline]);

  return { runAll, running };
}
