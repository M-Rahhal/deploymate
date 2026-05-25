import { useState } from 'react';
import { Trash2, GitMerge, Tag, Rocket, Play, ChevronDown, ChevronUp } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

import { Button }  from '@/components/ui/button';
import { Input }   from '@/components/ui/input';
import { Label }   from '@/components/ui/label';
import { Switch }  from '@/components/ui/switch';
import { Badge }   from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

import { useDeployStore }   from '@/hooks/useDeployStore';
import { useMergeAction, useTagAction, usePipelineAction, useAllStepsAction } from '@/hooks/useDeployActions';
import { computeOverallState } from '@/types';
import { shortSha } from '@/lib/utils';

import { StatusBadge }   from './StatusBadge';
import { StepBadge }     from './StepBadge';
import { ConflictPanel } from './ConflictPanel';
import { BuildLogButton } from './BuildLogDrawer';

import type { ServiceRow as ServiceRowType } from '@/types';

// ─── Hard-coded org from env (or use a config store field in future) ──────────
const ORG = ((window as unknown) as Record<string, unknown>)['__ORG__'] as string | undefined ?? '';

interface ServiceRowProps {
  row:   ServiceRowType;
  index: number;
}

export function ServiceRow({ row, index }: ServiceRowProps) {
  const { updateRow, removeRow }          = useDeployStore();
  const { run: runMerge,   running: mR }  = useMergeAction(row, ORG);
  const { run: runTag,     running: tR }  = useTagAction(row, ORG);
  const { run: runPipeline, running: pR } = usePipelineAction(row);
  const { runAll,           running: aR } = useAllStepsAction(row, ORG);

  const [expanded, setExpanded] = useState(true);

  const overall  = computeOverallState(row);
  const anyBusy  = mR || tR || pR || aR;
  const isDone   = overall === 'DONE';
  const isSdk    = row.type === 'SDK';

  function field(key: keyof ServiceRowType, value: string | boolean | number) {
    updateRow(row.id, { [key]: value } as Partial<ServiceRowType>);
  }

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.98 }}
      transition={{ duration: 0.15 }}
      className="rounded-lg border border-slate-800 bg-slate-900 shadow-sm"
    >
      {/* ── Header row ─────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-4 py-3">
        {/* Index pill */}
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-slate-800 text-[10px] font-bold text-slate-400">
          {index + 1}
        </span>

        {/* Name */}
        <Input
          value={row.name}
          onChange={(e) => field('name', e.target.value)}
          placeholder="Display name…"
          className="h-7 w-40 min-w-0 shrink text-sm"
          disabled={anyBusy}
        />

        {/* Repo */}
        <Input
          value={row.repo}
          onChange={(e) => field('repo', e.target.value)}
          placeholder="github-repo-name"
          className="h-7 min-w-0 flex-1 font-mono text-xs"
          disabled={anyBusy}
        />

        {/* Type badge */}
        <Badge variant={isSdk ? 'default' : 'secondary'} className="shrink-0">
          {row.type}
        </Badge>

        {/* Overall status */}
        <StatusBadge state={overall} className="shrink-0" />

        {/* Expand/collapse */}
        <Button
          variant="ghost"
          size="sm"
          className="h-7 w-7 shrink-0 p-0 text-slate-500"
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </Button>

        {/* Delete */}
        <Button
          variant="ghost"
          size="sm"
          className="h-7 w-7 shrink-0 p-0 text-slate-600 hover:text-rose-400"
          onClick={() => removeRow(row.id)}
          disabled={anyBusy}
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>

      {/* ── Expanded detail ─────────────────────────────────────────────── */}
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            key="body"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <Separator />

            <div className="grid grid-cols-2 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-3 lg:grid-cols-4">
              {/* Type select */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Type</Label>
                <Select
                  value={row.type}
                  onValueChange={(v) => field('type', v as 'SDK' | 'SERVICE')}
                  disabled={anyBusy}
                >
                  <SelectTrigger className="h-7 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="SDK">SDK</SelectItem>
                    <SelectItem value="SERVICE">SERVICE</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* Stage (SDK only) */}
              {isSdk && (
                <div className="space-y-1">
                  <Label className="text-[10px] uppercase tracking-wider text-slate-500">Stage</Label>
                  <Input
                    type="number"
                    min={1}
                    max={20}
                    value={row.stage}
                    onChange={(e) => field('stage', Number(e.target.value))}
                    className="h-7 text-xs"
                    disabled={anyBusy}
                  />
                </div>
              )}

              {/* Source branch */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Source branch</Label>
                <Input
                  value={row.sourceBranch}
                  onChange={(e) => field('sourceBranch', e.target.value)}
                  placeholder="feature/my-branch"
                  className="h-7 font-mono text-xs"
                  disabled={anyBusy || row.skipMerge}
                />
              </div>

              {/* Target branch */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Target branch</Label>
                <Input
                  value={row.targetBranch}
                  onChange={(e) => field('targetBranch', e.target.value)}
                  placeholder="env/staging"
                  className="h-7 font-mono text-xs"
                  disabled={anyBusy}
                />
              </div>

              {/* Jenkins job */}
              <div className="space-y-1 sm:col-span-2">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Jenkins job</Label>
                <Input
                  value={row.jenkinsJob}
                  onChange={(e) => field('jenkinsJob', e.target.value)}
                  placeholder="team/my-deploy-job"
                  className="h-7 font-mono text-xs"
                  disabled={anyBusy}
                />
              </div>

              {/* Tag name (SERVICE only) */}
              {!isSdk && (
                <div className="space-y-1 sm:col-span-2">
                  <Label className="text-[10px] uppercase tracking-wider text-slate-500">Tag name</Label>
                  <Input
                    value={row.tagName}
                    onChange={(e) => field('tagName', e.target.value)}
                    placeholder="env-stag-20240101-repo-001"
                    className="h-7 font-mono text-xs"
                    disabled={anyBusy || row.skipMerge}
                  />
                </div>
              )}

              {/* Toggles */}
              <div className="flex flex-col gap-2 pt-1">
                {/* Skip merge */}
                <div className="flex items-center gap-2">
                  <Switch
                    id={`skip-${row.id}`}
                    checked={row.skipMerge}
                    onCheckedChange={(v) => field('skipMerge', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`skip-${row.id}`} className="cursor-pointer text-xs text-slate-400">
                    Skip merge
                    {row.skipMerge && (
                      <span className="ml-1 text-amber-400">(deploy-only)</span>
                    )}
                  </Label>
                </div>

                {/* Update Jira */}
                <div className="flex items-center gap-2">
                  <Switch
                    id={`jira-${row.id}`}
                    checked={row.updateJira}
                    onCheckedChange={(v) => field('updateJira', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`jira-${row.id}`} className="cursor-pointer text-xs text-slate-400">
                    Update Jira
                  </Label>
                </div>
              </div>
            </div>

            {/* ── Step states ───────────────────────────────────────────────── */}
            <div className="flex flex-wrap items-center gap-2 border-t border-slate-800 px-4 py-2.5">
              <StepBadge
                step="Merge"
                state={row.mergeState}
                detail={row.mergeState === 'DONE' ? shortSha(row.mergeCommitSha) : null}
              />
              {!isSdk && (
                <StepBadge
                  step="Tag"
                  state={row.tagState}
                  detail={row.tagState === 'DONE' && row.tagReleaseUrl ? 'released' : null}
                />
              )}
              <StepBadge
                step="Pipeline"
                state={row.pipelineState}
                detail={row.buildNumber ? `#${row.buildNumber}` : null}
              />

              <BuildLogButton row={row} />

              {/* Error message */}
              {row.errorMessage && overall === 'FAILED' && (
                <span className="text-[11px] text-rose-400">{row.errorMessage}</span>
              )}

              {/* ── Action buttons ──────────────────────────────────────── */}
              <div className="ml-auto flex items-center gap-1.5">
                {/* Merge */}
                {!row.skipMerge && row.mergeState !== 'DONE' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1 px-2 text-xs"
                        onClick={runMerge}
                        disabled={anyBusy}
                      >
                        <GitMerge className="h-3 w-3" />
                        Merge
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Merge {row.sourceBranch} → {row.targetBranch}</TooltipContent>
                  </Tooltip>
                )}

                {/* Tag (SERVICE, !skipMerge, merge done) */}
                {!isSdk && !row.skipMerge && row.mergeState === 'DONE' && row.tagState !== 'DONE' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1 px-2 text-xs"
                        onClick={runTag}
                        disabled={anyBusy}
                      >
                        <Tag className="h-3 w-3" />
                        Tag
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Create pre-release tag {row.tagName}</TooltipContent>
                  </Tooltip>
                )}

                {/* Pipeline */}
                {row.pipelineState !== 'DONE' && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1 px-2 text-xs"
                        onClick={runPipeline}
                        disabled={anyBusy}
                      >
                        <Rocket className="h-3 w-3" />
                        Pipeline
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Trigger Jenkins: {row.jenkinsJob}</TooltipContent>
                  </Tooltip>
                )}

                {/* Run all steps */}
                {!isDone && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        size="sm"
                        className="h-7 gap-1 bg-indigo-700 px-2 text-xs hover:bg-indigo-600"
                        onClick={runAll}
                        disabled={anyBusy}
                      >
                        <Play className="h-3 w-3" />
                        {anyBusy ? 'Running…' : 'Run all'}
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Run all pending steps in sequence</TooltipContent>
                  </Tooltip>
                )}
              </div>
            </div>

            {/* ── Conflict panel ─────────────────────────────────────────── */}
            {row.mergeState === 'CONFLICT' && (
              <div className="border-t border-slate-800 px-4 pb-3 pt-2">
                <ConflictPanel row={row} org={ORG} />
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
