import { Input }  from '@/components/ui/input';
import { Label }  from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Settings2, Info } from 'lucide-react';
import { useDeployStore } from '@/hooks/useDeployStore';

export function GlobalConfig() {
  const { globalConfig, setGlobalConfig } = useDeployStore();

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm font-semibold text-slate-200">
          <Settings2 className="h-4 w-4 text-indigo-400" />
          Global Configuration
        </CardTitle>
      </CardHeader>

      <CardContent>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          {/* Jira Ticket */}
          <div className="space-y-1.5">
            <div className="flex items-center gap-1.5">
              <Label htmlFor="gc-ticket" className="text-xs text-slate-300">
                Jira Ticket
              </Label>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Info className="h-3 w-3 cursor-help text-slate-500" />
                </TooltipTrigger>
                <TooltipContent>
                  Used in merge commit messages and Jira comments (e.g. PROJ-123)
                </TooltipContent>
              </Tooltip>
            </div>
            <Input
              id="gc-ticket"
              value={globalConfig.ticket}
              onChange={(e) => setGlobalConfig({ ticket: e.target.value.toUpperCase() })}
              placeholder="PROJ-123"
              className="h-8 font-mono text-xs"
            />
          </div>

          {/* Default Source Branch */}
          <div className="space-y-1.5">
            <div className="flex items-center gap-1.5">
              <Label htmlFor="gc-source" className="text-xs text-slate-300">
                Default Source Branch
              </Label>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Info className="h-3 w-3 cursor-help text-slate-500" />
                </TooltipTrigger>
                <TooltipContent>
                  Branch to merge FROM. Applied to new rows automatically.
                </TooltipContent>
              </Tooltip>
            </div>
            <Input
              id="gc-source"
              value={globalConfig.defaultSourceBranch}
              onChange={(e) => setGlobalConfig({ defaultSourceBranch: e.target.value })}
              placeholder="feature/my-branch"
              className="h-8 font-mono text-xs"
            />
          </div>

          {/* Default Target Branch */}
          <div className="space-y-1.5">
            <div className="flex items-center gap-1.5">
              <Label htmlFor="gc-target" className="text-xs text-slate-300">
                Default Target Branch
              </Label>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Info className="h-3 w-3 cursor-help text-slate-500" />
                </TooltipTrigger>
                <TooltipContent>
                  Branch to merge INTO. Applied to new rows automatically.
                </TooltipContent>
              </Tooltip>
            </div>
            <Input
              id="gc-target"
              value={globalConfig.defaultTargetBranch}
              onChange={(e) => setGlobalConfig({ defaultTargetBranch: e.target.value })}
              placeholder="env/staging"
              className="h-8 font-mono text-xs"
            />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
