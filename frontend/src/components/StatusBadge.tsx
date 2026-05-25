import {
  CheckCircle2, XCircle, AlertTriangle, Loader2,
  Clock, GitMerge, Tag, Rocket,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { OverallState } from '@/types';

interface StatusBadgeProps {
  state:     OverallState;
  className?: string;
}

type Config = {
  label:     string;
  icon:      React.ReactNode;
  textClass: string;
  bgClass:   string;
};

function cfg(state: OverallState): Config {
  switch (state) {
    case 'IDLE':
      return { label: 'Idle',             icon: <Clock className="h-3.5 w-3.5" />,              textClass: 'text-slate-400',   bgClass: 'bg-slate-800' };
    case 'MERGING':
      return { label: 'Merging…',         icon: <GitMerge className="h-3.5 w-3.5 animate-pulse" />, textClass: 'text-indigo-300', bgClass: 'bg-indigo-950/60' };
    case 'CONFLICT':
      return { label: 'Conflict!',        icon: <AlertTriangle className="h-3.5 w-3.5" />,      textClass: 'text-amber-400',   bgClass: 'bg-amber-950/60' };
    case 'MERGED':
      return { label: 'Merged',           icon: <GitMerge className="h-3.5 w-3.5" />,           textClass: 'text-sky-400',     bgClass: 'bg-sky-950/60' };
    case 'TAGGING':
      return { label: 'Tagging…',         icon: <Tag className="h-3.5 w-3.5 animate-pulse" />,  textClass: 'text-violet-300',  bgClass: 'bg-violet-950/60' };
    case 'TAGGED':
      return { label: 'Tagged',           icon: <Tag className="h-3.5 w-3.5" />,                textClass: 'text-violet-400',  bgClass: 'bg-violet-950/60' };
    case 'PIPELINE_QUEUED':
      return { label: 'Queued',           icon: <Loader2 className="h-3.5 w-3.5 animate-spin" />, textClass: 'text-indigo-300', bgClass: 'bg-indigo-950/60' };
    case 'PIPELINE_RUNNING':
      return { label: 'Pipeline running', icon: <Rocket className="h-3.5 w-3.5 animate-pulse" />, textClass: 'text-indigo-300', bgClass: 'bg-indigo-950/60' };
    case 'DONE':
      return { label: 'Done ✓',           icon: <CheckCircle2 className="h-3.5 w-3.5" />,       textClass: 'text-emerald-400', bgClass: 'bg-emerald-950/60' };
    case 'FAILED':
      return { label: 'Failed',           icon: <XCircle className="h-3.5 w-3.5" />,            textClass: 'text-rose-400',    bgClass: 'bg-rose-950/60' };
  }
}

export function StatusBadge({ state, className }: StatusBadgeProps) {
  const { label, icon, textClass, bgClass } = cfg(state);

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold',
        bgClass, textClass, className
      )}
    >
      {icon}
      {label}
    </span>
  );
}
