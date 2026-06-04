import { useState } from 'react';
import { Settings2, Trash2, Play, GitMerge, Tag, Rocket } from 'lucide-react';
import { motion } from 'framer-motion';

import { Button }    from '@/components/ui/button';
import { Badge }     from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

import { useDeployStore } from '@/hooks/useDeployStore';
import { useMergeAction, useTagAction, usePipelineAction, useAllStepsAction } from '@/hooks/useDeployActions';
import { computeOverallState } from '@/types';
import { shortSha } from '@/lib/utils';
import { StatusBadge } from './StatusBadge';
import { StepBadge }   from './StepBadge';
import { ServiceDetailModal } from './ServiceDetailModal';
import type { ServiceRow } from '@/types';

interface ServiceCardProps {
  row:   ServiceRow;
  index: number;
}

export function ServiceCard({ row, index }: ServiceCardProps) {
  const { removeRow } = useDeployStore();
  const { run: runMerge,    running: mR } = useMergeAction(row);
  const { run: runTag,      running: tR } = useTagAction(row);
  const { run: runPipeline, running: pR } = usePipelineAction(row);
  const { runAll,            running: aR } = useAllStepsAction(row);

  const [detailOpen, setDetailOpen] = useState(false);

  const overall  = computeOverallState(row);
  const anyBusy  = mR || tR || pR || aR;
  const isDone   = overall === 'DONE';
  const isFailed = overall === 'FAILED';

  return (
    <>
      <motion.div
        layout
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.96 }}
        transition={{ duration: 0.15 }}
        className="w-72 shrink-0 flex flex-col rounded-xl border border-slate-800 bg-slate-900 shadow-sm"
      >
        {/* ── Card header ────────────────────────────────────────────── */}
        <div className="flex items-start gap-2 p-3 pb-2">
          <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-slate-800 text-[10px] font-bold text-slate-400">
            {index + 1}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-slate-100 leading-tight">
              {row.name || <span className="text-slate-500 italic">unnamed</span>}
            </p>
            <p className="truncate font-mono text-[11px] text-slate-500 mt-0.5">
              {row.repo || '—'}
            </p>
          </div>
          <StatusBadge state={overall} className="shrink-0 text-[10px] mt-0.5" />
        </div>

        {/* ── Type + Stage + Target ──────────────────────────────────── */}
        <div className="flex items-center gap-1.5 px-3 pb-2 flex-wrap">
          <Badge
            variant={row.type === 'SDK' ? 'default' : 'secondary'}
            className="text-[10px] h-4 px-1.5"
          >
            {row.type}
          </Badge>
          <span className="text-[11px] text-slate-600">Stage {row.stage}</span>
          <span className="text-slate-700">·</span>
          <span className="font-mono text-[11px] text-slate-500 truncate max-w-[120px]">
            {row.targetBranch || '—'}
          </span>
        </div>

        {/* ── Source branch ─────────────────────────────────────────── */}
        {row.sourceBranch && (
          <div className="px-3 pb-2">
            <span className="font-mono text-[11px] text-slate-600 truncate block">
              {row.sourceBranch}
              <span className="text-indigo-600 mx-1">→</span>
              {row.targetBranch}
            </span>
          </div>
        )}

        <Separator />

        {/* ── Step badges ───────────────────────────────────────────── */}
        <div className="flex flex-wrap gap-1 px-3 py-2">
          {row.steps.mergeBranch && (
            <StepBadge
              step="Merge"
              state={row.mergeState}
              detail={row.mergeState === 'DONE' ? shortSha(row.mergeCommitSha) : undefined}
            />
          )}
          {row.type === 'SERVICE' && (
            <StepBadge
              step="Tag"
              state={row.tagState}
              detail={row.tagState === 'DONE' && row.tagReleaseUrl ? 'released' : undefined}
            />
          )}
          {row.steps.triggerPipeline && (
            <StepBadge
              step="Pipeline"
              state={row.pipelineState}
              detail={row.buildNumber ? `#${row.buildNumber}` : undefined}
            />
          )}
        </div>

        {/* ── Conflict / error message ──────────────────────────────── */}
        {row.mergeState === 'CONFLICT' && (
          <p className="px-3 pb-1 text-[11px] text-amber-400">
            ⚠ Merge conflict — open Details to retry
          </p>
        )}
        {row.errorMessage && isFailed && (
          <p className="px-3 pb-1 text-[11px] text-rose-400 truncate">
            {row.errorMessage}
          </p>
        )}

        <Separator />

        {/* ── Action row ────────────────────────────────────────────── */}
        <div className="flex items-center gap-1 p-2">
          {/* Primary step actions — shown when the step is pending */}
          {!isDone && row.steps.mergeBranch && row.mergeState !== 'DONE' && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-6 gap-1 px-2 text-[10px]"
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
          {!isDone && row.steps.createTag && row.mergeState === 'DONE' && row.tagState !== 'DONE' && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-6 gap-1 px-2 text-[10px]"
                  onClick={runTag}
                  disabled={anyBusy}
                >
                  <Tag className="h-3 w-3" />
                  Tag
                </Button>
              </TooltipTrigger>
              <TooltipContent>Create tag {row.tagName}</TooltipContent>
            </Tooltip>
          )}
          {!isDone && row.steps.triggerPipeline && row.pipelineState !== 'DONE' && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-6 gap-1 px-2 text-[10px]"
                  onClick={runPipeline}
                  disabled={anyBusy}
                >
                  <Rocket className="h-3 w-3" />
                  Pipeline
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                {row.type === 'SERVICE'
                  ? `git_branch=${row.tagName || '?'}`
                  : `git_branch=origin/${row.targetBranch}`}
              </TooltipContent>
            </Tooltip>
          )}

          {/* Run all — shown whenever not fully done */}
          {!isDone && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="sm"
                  className="h-6 gap-1 bg-indigo-700 px-2 text-[10px] hover:bg-indigo-600 text-white"
                  onClick={runAll}
                  disabled={anyBusy}
                >
                  <Play className="h-3 w-3" />
                  {anyBusy ? '…' : 'Run all'}
                </Button>
              </TooltipTrigger>
              <TooltipContent>Run all configured steps</TooltipContent>
            </Tooltip>
          )}

          {/* Spacer */}
          <div className="ml-auto flex items-center gap-0.5">
            {/* Details */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0 text-slate-500 hover:text-slate-200"
                  onClick={() => setDetailOpen(true)}
                  aria-label="Open service details"
                >
                  <Settings2 className="h-3.5 w-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Details &amp; configuration</TooltipContent>
            </Tooltip>

            {/* Delete */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0 text-slate-600 hover:text-rose-400"
                  onClick={() => removeRow(row.id)}
                  disabled={anyBusy}
                  aria-label="Remove service"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Remove</TooltipContent>
            </Tooltip>
          </div>
        </div>
      </motion.div>

      <ServiceDetailModal
        row={row}
        index={index}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      />
    </>
  );
}
