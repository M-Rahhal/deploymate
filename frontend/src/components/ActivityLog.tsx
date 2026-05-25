import { useRef, useEffect } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Trash2, Activity } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatTimestamp } from '@/lib/utils';
import { useDeployStore } from '@/hooks/useDeployStore';
import type { ActivityLogEntry } from '@/types';

function levelClass(level: ActivityLogEntry['level']): string {
  switch (level) {
    case 'INFO':    return 'text-slate-400';
    case 'WARN':    return 'text-amber-400';
    case 'ERROR':   return 'text-rose-400';
    case 'SUCCESS': return 'text-emerald-400';
  }
}

function levelDot(level: ActivityLogEntry['level']): string {
  switch (level) {
    case 'INFO':    return 'bg-slate-500';
    case 'WARN':    return 'bg-amber-500';
    case 'ERROR':   return 'bg-rose-500';
    case 'SUCCESS': return 'bg-emerald-500';
  }
}

interface LogLineProps {
  entry: ActivityLogEntry;
}

function LogLine({ entry }: LogLineProps) {
  return (
    <div className={cn('flex items-start gap-2 py-0.5 font-mono text-[11px]', levelClass(entry.level))}>
      <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: levelDot(entry.level).replace('bg-','') }}>
        <span className={cn('block h-1.5 w-1.5 shrink-0 rounded-full', levelDot(entry.level))} />
      </span>
      <span className="shrink-0 text-slate-600">{formatTimestamp(entry.timestamp)}</span>
      <span className="shrink-0 text-slate-500">[{entry.stage}]</span>
      <span className="shrink-0 font-semibold">{entry.service}</span>
      <span className="break-all text-slate-300">{entry.message}</span>
    </div>
  );
}

export function ActivityLog() {
  const { activityLog, resetAll } = useDeployStore();
  const bottomRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new entries arrive
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activityLog.length]);

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold text-slate-200">
            <Activity className="h-4 w-4 text-indigo-400" />
            Activity Log
            {activityLog.length > 0 && (
              <span className="rounded-full bg-slate-800 px-2 py-0.5 text-xs text-slate-400">
                {activityLog.length}
              </span>
            )}
          </CardTitle>
          {activityLog.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              className="h-7 gap-1.5 text-xs text-slate-500 hover:text-rose-400"
              onClick={resetAll}
            >
              <Trash2 className="h-3 w-3" />
              Clear
            </Button>
          )}
        </div>
      </CardHeader>

      <CardContent className="p-0">
        <ScrollArea className="h-48 rounded-b-lg">
          <div className="space-y-px px-4 pb-3 pt-1">
            {activityLog.length === 0 ? (
              <p className="py-6 text-center text-xs text-slate-600">
                No activity yet — run a deployment step to see logs here.
              </p>
            ) : (
              <>
                {activityLog.map((entry) => (
                  <LogLine key={entry.id} entry={entry} />
                ))}
                <div ref={bottomRef} />
              </>
            )}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
