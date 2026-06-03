import { useMemo } from 'react';
import { ArrowRight, Layers } from 'lucide-react';
import { cn } from '@/lib/utils';
import { computeOverallState } from '@/types';
import { useDeployStore } from '@/hooks/useDeployStore';
import { StatusBadge } from './StatusBadge';
import type { ServiceRow } from '@/types';

interface StageGroupProps {
  label:  string;
  rows:   ServiceRow[];
  isLast: boolean;
}

function StageGroup({ label, rows, isLast }: StageGroupProps) {
  const groupState = (() => {
    const states = rows.map((r) => computeOverallState(r));
    if (states.some((s) => s === 'FAILED'))   return 'FAILED';
    if (states.some((s) => s === 'CONFLICT')) return 'CONFLICT';
    if (states.some((s) =>
      s === 'PIPELINE_RUNNING' || s === 'MERGING' || s === 'TAGGING'
    )) return 'PIPELINE_RUNNING';
    if (states.every((s) => s === 'DONE'))    return 'DONE';
    if (states.every((s) => s === 'IDLE'))    return 'IDLE';
    return 'MERGING';
  })();

  return (
    <div className="flex items-start gap-3">
      <div className="flex flex-col items-center gap-1">
        <div
          className={cn(
            'flex min-w-[120px] flex-col rounded-lg border p-3 text-xs transition-colors',
            groupState === 'DONE'     && 'border-emerald-700/60 bg-emerald-950/40',
            groupState === 'FAILED'   && 'border-rose-700/60    bg-rose-950/40',
            groupState === 'CONFLICT' && 'border-amber-700/60   bg-amber-950/40',
            groupState === 'PIPELINE_RUNNING' && 'border-indigo-700/60 bg-indigo-950/40',
            !['DONE', 'FAILED', 'CONFLICT', 'PIPELINE_RUNNING'].includes(groupState) &&
              'border-slate-700 bg-slate-800/60'
          )}
        >
          <div className="mb-2 flex items-center gap-1.5 font-semibold text-slate-200">
            <Layers className="h-3.5 w-3.5 text-indigo-400" />
            {label}
          </div>

          <div className="flex flex-col gap-1">
            {rows.map((row) => (
              <div key={row.id} className="flex items-center justify-between gap-2">
                <span className="max-w-[90px] truncate text-slate-300">
                  {row.name || row.repo || '—'}
                </span>
                <StatusBadge state={computeOverallState(row)} className="shrink-0 text-[10px]" />
              </div>
            ))}
          </div>
        </div>
      </div>

      {!isLast && (
        <ArrowRight className="mt-5 h-4 w-4 shrink-0 text-slate-600" />
      )}
    </div>
  );
}

export function StageVisualizer() {
  const rows = useDeployStore((s) => s.rows);

  const stageGroups = useMemo(() => {
    const stageMap = new Map<number, ServiceRow[]>();
    for (const row of rows) {
      const existing = stageMap.get(row.stage) ?? [];
      existing.push(row);
      stageMap.set(row.stage, existing);
    }
    return Array.from(stageMap.entries()).sort(([a], [b]) => a - b);
  }, [rows]);

  if (stageGroups.length === 0) return null;

  return (
    <div className="rounded-lg border border-slate-800 bg-slate-900/50 p-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
        Deployment pipeline
      </p>
      <div className="flex flex-wrap items-start gap-2">
        {stageGroups.map(([stage, stageRows], i) => (
          <StageGroup
            key={stage}
            label={`Stage ${stage}`}
            rows={stageRows}
            isLast={i === stageGroups.length - 1}
          />
        ))}
      </div>
    </div>
  );
}
