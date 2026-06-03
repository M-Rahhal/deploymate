import { AlertTriangle, ExternalLink, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useDeployStore } from '@/hooks/useDeployStore';
import { useMergeAction } from '@/hooks/useDeployActions';
import type { ServiceRow } from '@/types';

interface ConflictPanelProps {
  row: ServiceRow;
}

export function ConflictPanel({ row }: ConflictPanelProps) {
  const resetRow      = useDeployStore((s) => s.resetRow);
  const { run, running } = useMergeAction(row);

  if (row.mergeState !== 'CONFLICT') return null;

  const org = ((window as unknown) as Record<string, unknown>)['__ORG__'] as string | undefined ?? '';
  const compareUrl = `https://github.com/${org}/${row.repo}/compare/${row.targetBranch}...${row.sourceBranch}`;

  return (
    <div className="flex items-start gap-3 rounded-md border border-amber-700/50 bg-amber-950/30 px-4 py-3 text-sm">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
      <div className="flex-1 space-y-1">
        <p className="font-medium text-amber-300">Merge conflict detected</p>
        <p className="text-xs text-amber-400/80">
          Resolve the conflict in{' '}
          <code className="rounded bg-amber-950/60 px-1 font-mono">
            {row.repo}
          </code>{' '}
          manually, then retry.
        </p>
        {row.errorMessage && (
          <p className="text-xs text-amber-500/70">{row.errorMessage}</p>
        )}
      </div>

      <div className="flex shrink-0 gap-2">
        <Button
          variant="outline"
          size="sm"
          className="h-7 gap-1.5 border-amber-700/50 text-xs text-amber-300 hover:bg-amber-950/50"
          onClick={() => window.open(compareUrl, '_blank', 'noopener')}
        >
          <ExternalLink className="h-3 w-3" />
          Open in GitHub
        </Button>

        <Button
          variant="outline"
          size="sm"
          className="h-7 gap-1.5 border-slate-700 text-xs hover:bg-slate-800"
          disabled={running}
          onClick={async () => {
            resetRow(row.id);
            await run();
          }}
        >
          <RefreshCw className={`h-3 w-3 ${running ? 'animate-spin' : ''}`} />
          Retry merge
        </Button>
      </div>
    </div>
  );
}
