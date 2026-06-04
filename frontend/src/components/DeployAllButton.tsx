import { useEffect, useRef, useState } from 'react';
import { Rocket, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { useDeployStore } from '@/hooks/useDeployStore';
import { apiDeployAll } from '@/lib/api';
import type {
  ServiceRow,
  ServiceRowDto,
  DeployLogEvent,
  DeployStepUpdateEvent,
  DeployDoneEvent,
} from '@/types';

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
    steps:        row.steps,
  };
}

export function DeployAllButton() {
  const { rows, globalConfig, resetAll, appendLog, setStepState } = useDeployStore();
  const [deploying, setDeploying]  = useState(false);
  const eventSourceRef             = useRef<EventSource | null>(null);

  // Close any open SSE connection on unmount
  useEffect(() => () => { eventSourceRef.current?.close(); }, []);

  const isDisabled = deploying || rows.length === 0 || rows.every((r) => r.pipelineState === 'DONE');
  const anyRunning = rows.some(
    (r) => r.mergeState === 'RUNNING' || r.tagState === 'RUNNING' || r.pipelineState === 'RUNNING'
  );
  const anyDone = rows.some((r) => r.pipelineState === 'DONE');

  function openEventStream(): EventSource {
    const es = new EventSource('/api/deploy/stream');
    eventSourceRef.current = es;

    es.addEventListener('log', (e: MessageEvent) => {
      const event = JSON.parse(e.data) as DeployLogEvent;
      appendLog({
        level:   event.level,
        service: event.serviceName,
        stage:   event.stageLabel,
        message: event.message,
      });
    });

    es.addEventListener('step-update', (e: MessageEvent) => {
      const event = JSON.parse(e.data) as DeployStepUpdateEvent;
      const extra: Partial<ServiceRow> = {};
      if (event.commitSha)          extra.mergeCommitSha = event.commitSha;
      if (event.releaseUrl)         extra.tagReleaseUrl  = event.releaseUrl;
      if (event.buildUrl)           extra.buildUrl       = event.buildUrl;
      if (event.buildNumber != null) extra.buildNumber   = event.buildNumber;
      if (event.errorMessage)       extra.errorMessage   = event.errorMessage;
      setStepState(
        event.rowId,
        event.step,
        event.state,
        Object.keys(extra).length > 0 ? extra : undefined,
      );
    });

    es.addEventListener('done', (e: MessageEvent) => {
      const event = JSON.parse(e.data) as DeployDoneEvent;
      es.close();
      eventSourceRef.current = null;
      setDeploying(false);
      if (event.success) {
        toast.success('All deployment stages complete ✅');
      } else {
        toast.warning('Deployment finished with failures — check the activity log');
      }
    });

    es.onerror = () => {
      es.close();
      eventSourceRef.current = null;
      setDeploying(false);
    };

    return es;
  }

  async function handleDeployAll() {
    if (deploying || anyRunning) return;

    setDeploying(true);
    appendLog({
      level: 'INFO', service: 'SYSTEM', stage: 'SYSTEM',
      message: `Starting full deployment — ${rows.length} service(s)`,
    });

    // Open SSE stream BEFORE submitting so no events are missed
    const es = openEventStream();

    try {
      await apiDeployAll(globalConfig.ticket, rows.map(toDto));
    } catch (err) {
      es.close();
      eventSourceRef.current = null;
      setDeploying(false);
      const msg = err instanceof Error ? err.message : String(err);
      toast.error(`Deploy All failed: ${msg}`);
      appendLog({ level: 'ERROR', service: 'SYSTEM', stage: 'SYSTEM', message: msg });
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
