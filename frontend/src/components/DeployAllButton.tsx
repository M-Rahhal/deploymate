import { useState } from 'react';
import { Rocket, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { useDeployStore } from '@/hooks/useDeployStore';
import { apiDeployAll } from '@/lib/api';
import type { ServiceRow, ServiceRowDto } from '@/types';

/** Converts a store ServiceRow into a backend DTO */
function toDto(row: ServiceRow): ServiceRowDto {
  return {
    id:           row.id,
    name:         row.name,
    repo:         row.repo,
    type:         row.type,
    stage:        row.stage,
    sourceBranch: row.sourceBranch,
    targetBranch: row.targetBranch,
    jenkinsJob:   row.jenkinsJob,
    tagName:      row.tagName,
    updateJira:   row.updateJira,
    skipMerge:    row.skipMerge,
  };
}

export function DeployAllButton() {
  const { rows, globalConfig, resetAll, appendLog } = useDeployStore();
  const [deploying, setDeploying] = useState(false);

  const isDisabled =
    deploying ||
    rows.length === 0 ||
    rows.every((r) => r.pipelineState === 'DONE');

  const anyRunning = rows.some(
    (r) => r.mergeState === 'RUNNING' || r.tagState === 'RUNNING' || r.pipelineState === 'RUNNING'
  );

  const anyDone = rows.some((r) => r.pipelineState === 'DONE');

  async function handleDeployAll() {
    if (deploying || anyRunning) return;

    setDeploying(true);
    appendLog({ level: 'INFO', service: 'SYSTEM', stage: 'SYSTEM',
      message: `Starting full deployment — ${rows.length} service(s)` });

    try {
      await apiDeployAll(
        globalConfig.ticket,
        rows.map(toDto)
      );
      toast.success('Deployment submitted — steps running in background');
      appendLog({ level: 'SUCCESS', service: 'SYSTEM', stage: 'SYSTEM',
        message: 'Deployment accepted by backend — watch step statuses above' });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      toast.error(`Deploy all failed: ${msg}`);
      appendLog({ level: 'ERROR', service: 'SYSTEM', stage: 'SYSTEM', message: msg });
    } finally {
      setDeploying(false);
    }
  }

  return (
    <div className="flex items-center gap-3">
      {anyDone && (
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5 text-slate-400 hover:text-slate-200"
          onClick={resetAll}
          disabled={deploying || anyRunning}
        >
          <RotateCcw className="h-3.5 w-3.5" />
          Reset all
        </Button>
      )}

      <Button
        size="default"
        disabled={isDisabled || anyRunning}
        onClick={handleDeployAll}
        className="gap-2 bg-indigo-600 text-white hover:bg-indigo-500 disabled:opacity-50"
      >
        <Rocket className={`h-4 w-4 ${deploying ? 'animate-bounce' : ''}`} />
        {deploying ? 'Deploying…' : 'Deploy All'}
      </Button>
    </div>
  );
}
