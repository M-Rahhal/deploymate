import { Rocket } from 'lucide-react';
import { Separator } from '@/components/ui/separator';
import { GlobalConfig }      from './GlobalConfig';
import { StageVisualizer }   from './StageVisualizer';
import { ServiceTable }      from './ServiceTable';
import { ActivityLog }       from './ActivityLog';
import { DeployAllButton }   from './DeployAllButton';
import { useDeployStore }    from '@/hooks/useDeployStore';

export function DeploymentDashboard() {
  const rowCount = useDeployStore((s) => s.rows.length);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      {/* ── Top bar ─────────────────────────────────────────────────────────── */}
      <header className="sticky top-0 z-20 border-b border-slate-800 bg-slate-950/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6">
          <div className="flex items-center gap-2">
            <Rocket className="h-5 w-5 text-indigo-400" />
            <span className="text-base font-bold tracking-tight text-slate-100">
              DeployMate
            </span>
            <span className="hidden text-xs text-slate-500 sm:inline">
              · deployment automation
            </span>
          </div>
          <DeployAllButton />
        </div>
      </header>

      {/* ── Main content ─────────────────────────────────────────────────────── */}
      <main className="mx-auto max-w-7xl space-y-6 px-4 py-6 sm:px-6">
        {/* Global configuration */}
        <GlobalConfig />

        {/* Stage flow visualiser */}
        {rowCount > 0 && (
          <>
            <Separator />
            <StageVisualizer />
          </>
        )}

        <Separator />

        {/* Services — horizontal scroll strip */}
        <ServiceTable />

        {/* Activity log */}
        <ActivityLog />

        <div className="pb-10" />
      </main>
    </div>
  );
}
