import { CheckCircle2, XCircle, AlertTriangle, Loader2, Clock, SkipForward } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { StepState } from '@/types';

interface StepBadgeProps {
  step:    'Merge' | 'Tag' | 'Pipeline';
  state:   StepState;
  /** Extra detail shown in parentheses, e.g. SHA or build number */
  detail?: string | null;
  className?: string;
}

type Config = {
  icon:      React.ReactNode;
  textClass: string;
  bgClass:   string;
  label:     string;
};

function cfg(state: StepState): Config {
  switch (state) {
    case 'IDLE':
      return {
        icon:      <Clock className="h-3 w-3" />,
        textClass: 'text-slate-400',
        bgClass:   'bg-slate-800',
        label:     'Idle',
      };
    case 'RUNNING':
      return {
        icon:      <Loader2 className="h-3 w-3 animate-spin" />,
        textClass: 'text-indigo-300',
        bgClass:   'bg-indigo-950/60',
        label:     'Running',
      };
    case 'DONE':
      return {
        icon:      <CheckCircle2 className="h-3 w-3" />,
        textClass: 'text-emerald-400',
        bgClass:   'bg-emerald-950/60',
        label:     'Done',
      };
    case 'FAILED':
      return {
        icon:      <XCircle className="h-3 w-3" />,
        textClass: 'text-rose-400',
        bgClass:   'bg-rose-950/60',
        label:     'Failed',
      };
    case 'CONFLICT':
      return {
        icon:      <AlertTriangle className="h-3 w-3" />,
        textClass: 'text-amber-400',
        bgClass:   'bg-amber-950/60',
        label:     'Conflict',
      };
    case 'SKIPPED':
      return {
        icon:      <SkipForward className="h-3 w-3" />,
        textClass: 'text-slate-500',
        bgClass:   'bg-slate-800/60',
        label:     'Skipped',
      };
  }
}

export function StepBadge({ step, state, detail, className }: StepBadgeProps) {
  const { icon, textClass, bgClass, label } = cfg(state);

  return (
    <div
      className={cn(
        'inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium',
        bgClass, textClass, className
      )}
    >
      {icon}
      <span>{step}</span>
      <span className="opacity-70">·</span>
      <span>{label}</span>
      {detail && (
        <span className="opacity-60">({detail})</span>
      )}
    </div>
  );
}
