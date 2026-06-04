import { useState, useEffect, useId } from 'react';
import { Trash2, GitMerge, Tag, Rocket, Play, ChevronDown, ChevronUp, RefreshCw, Loader2, GitCommit } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

import { Button }    from '@/components/ui/button';
import { Input }     from '@/components/ui/input';
import { Label }     from '@/components/ui/label';
import { Switch }    from '@/components/ui/switch';
import { Badge }     from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

import { useDeployStore }   from '@/hooks/useDeployStore';
import { useMergeAction, useTagAction, usePipelineAction, useAllStepsAction } from '@/hooks/useDeployActions';
import { useServiceRegistry } from '@/hooks/useServiceRegistry';
import { computeOverallState } from '@/types';
import { shortSha } from '@/lib/utils';
import {
  apiGetServiceNames, apiSaveServiceName,
  apiGetNextTag,
  apiGetBranchInfo,
} from '@/lib/api';
import type { BranchInfo } from '@/lib/api';
import { StatusBadge }   from './StatusBadge';
import { StepBadge }     from './StepBadge';
import { ConflictPanel } from './ConflictPanel';
import { BuildLogButton } from './BuildLogDrawer';
import { ReadOnlyField }  from './ReadOnlyField';

import type { ServiceRow as ServiceRowType, ServiceRowSteps, JenkinsEnvMode } from '@/types';


interface ServiceRowProps {
  row:   ServiceRowType;
  index: number;
  /** When true: removes card border/shadow/rounded — for rendering inside a modal. */
  flat?: boolean;
}

// ─── Jenkins URL builder sub-component ────────────────────────────────────────

interface JenkinsBuilderProps {
  row:          ServiceRowType;
  disabled:     boolean;
  fromRegistry: boolean;
  onField:      (key: keyof ServiceRowType, value: string | boolean | number) => void;
}

function JenkinsBuilder({ row, disabled, fromRegistry, onField }: JenkinsBuilderProps) {
  const [serviceNames, setServiceNames] = useState<string[]>([]);
  const nameListId = useId();

  useEffect(() => {
    if (!row.jenkinsCategory) { setServiceNames([]); return; }
    apiGetServiceNames(row.jenkinsCategory).then(setServiceNames).catch(() => {});
  }, [row.jenkinsCategory]);

  function handleServiceNameBlur() {
    const val = row.jenkinsServiceName.trim();
    const cat = row.jenkinsCategory.trim();
    if (!val || !cat || fromRegistry) return;
    if (!serviceNames.includes(val)) {
      apiSaveServiceName(cat, val).then(() =>
        setServiceNames((prev) => [...prev, val].sort())
      ).catch(() => {});
    }
  }

  const resolvedEnv = row.jenkinsEnvMode === 'env'
    ? (row.jenkinsEnv || '—')
    : (row.type === 'SERVICE' ? 'staging' : 'dev');

  return (
    <div className="space-y-3">
      {/* Category */}
      <div className="space-y-1">
        <Label className="text-[10px] uppercase tracking-wider text-slate-500">Jenkins category</Label>
        {fromRegistry ? (
          <ReadOnlyField value={row.jenkinsCategory} placeholder="cross-products" monospace />
        ) : (
          <Input
            value={row.jenkinsCategory}
            onChange={(e) => onField('jenkinsCategory', e.target.value)}
            placeholder="cross-products"
            disabled={disabled}
            className="h-7 font-mono text-xs"
          />
        )}
      </div>

      {/* Environment / Type toggle — always editable */}
      <div className="space-y-1.5">
        <Label className="text-[10px] uppercase tracking-wider text-slate-500">
          Environment segment
        </Label>
        <div className="flex items-center gap-3">
          <div className="flex rounded-md border border-slate-700 overflow-hidden text-xs">
            <button
              type="button"
              onClick={() => onField('jenkinsEnvMode', 'type' as JenkinsEnvMode)}
              disabled={disabled}
              className={`px-2.5 py-1 transition-colors ${
                row.jenkinsEnvMode === 'type'
                  ? 'bg-indigo-700 text-white'
                  : 'bg-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              By type
            </button>
            <button
              type="button"
              onClick={() => onField('jenkinsEnvMode', 'env' as JenkinsEnvMode)}
              disabled={disabled}
              className={`px-2.5 py-1 transition-colors ${
                row.jenkinsEnvMode === 'env'
                  ? 'bg-indigo-700 text-white'
                  : 'bg-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              By env
            </button>
          </div>

          {row.jenkinsEnvMode === 'type' ? (
            <span className="text-xs text-slate-400">
              {row.type === 'SERVICE'
                ? <><span className="font-mono text-amber-400">staging</span> (SERVICE)</>
                : <><span className="font-mono text-sky-400">dev</span> (SDK)</>
              }
            </span>
          ) : (
            <Select
              value={row.jenkinsEnv || 'dev'}
              onValueChange={(v) => onField('jenkinsEnv', v)}
              disabled={disabled}
            >
              <SelectTrigger className="h-7 w-28 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="dev">dev</SelectItem>
                <SelectItem value="staging">staging</SelectItem>
              </SelectContent>
            </Select>
          )}
        </div>
      </div>

      {/* Service / SDK name */}
      <div className="space-y-1">
        <Label className="text-[10px] uppercase tracking-wider text-slate-500">Service name</Label>
        {fromRegistry ? (
          <ReadOnlyField value={row.jenkinsServiceName} placeholder="payment-eligibility-service" monospace />
        ) : (
          <>
            <input
              list={nameListId}
              value={row.jenkinsServiceName}
              onChange={(e) => onField('jenkinsServiceName', e.target.value)}
              onBlur={handleServiceNameBlur}
              placeholder="payment-eligibility-service"
              disabled={disabled}
              className="flex h-7 w-full rounded-md border border-slate-700 bg-slate-800 px-2 py-1 font-mono text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500 disabled:opacity-50"
            />
            <datalist id={nameListId}>
              {serviceNames.map((n) => <option key={n} value={n} />)}
            </datalist>
          </>
        )}
      </div>

      {/* Computed URL preview */}
      {(row.jenkinsCategory || row.jenkinsServiceName) && (
        <p className="text-[11px] text-slate-500 font-mono break-all">
          <span className="text-slate-600">Jenkins path: </span>
          <span className="text-indigo-400">/job/</span>
          <span className={row.jenkinsCategory ? 'text-slate-300' : 'text-slate-600'}>
            {row.jenkinsCategory || '‹category›'}
          </span>
          <span className="text-indigo-400">/job/</span>
          <span className="text-sky-400">{resolvedEnv}</span>
          <span className="text-indigo-400">/job/</span>
          <span className={row.jenkinsServiceName ? 'text-slate-300' : 'text-slate-600'}>
            {row.jenkinsServiceName || '‹service›'}
          </span>
          <span className="text-indigo-400">/</span>
        </p>
      )}
    </div>
  );
}

// ─── Main ServiceRow component ────────────────────────────────────────────────

export function ServiceRow({ row, index, flat = false }: ServiceRowProps) {
  const { updateRow, updateSteps, removeRow } = useDeployStore();
  const { entries: registryEntries, repoNames, loaded: registryLoaded, missing: registryMissing } = useServiceRegistry();
  const { run: runMerge,    running: mR }  = useMergeAction(row);
  const { run: runTag,      running: tR }  = useTagAction(row);
  const { run: runPipeline, running: pR }  = usePipelineAction(row);
  const { runAll,            running: aR } = useAllStepsAction(row);

  const [expanded, setExpanded] = useState(true);

  const [fetchingInfo,     setFetchingInfo]     = useState(false);
  const [previousTag,      setPreviousTag]      = useState<string | null>(null);
  const [lastDeployedTag,  setLastDeployedTag]  = useState<string | null>(null);
  const [sourceBranchInfo, setSourceBranchInfo] = useState<BranchInfo | null>(null);
  const [targetBranchInfo, setTargetBranchInfo] = useState<BranchInfo | null>(null);

  useEffect(() => { setSourceBranchInfo(null); }, [row.sourceBranch]);
  useEffect(() => { setTargetBranchInfo(null); }, [row.targetBranch]);
  useEffect(() => { setPreviousTag(null); setLastDeployedTag(null); }, [row.repo]);

  // Whether this row's repo is in the loaded registry — locks derived fields
  const isFromRegistry = row.repo !== '' && row.repo in registryEntries;

  function handleRepoSelect(repoName: string) {
    const entry = registryEntries[repoName];
    if (!entry) return;
    updateRow(row.id, {
      repo:               repoName,
      name:               entry.displayName,
      type:               entry.type,
      jenkinsCategory:    entry.jenkinsCategory,
      jenkinsServiceName: entry.jenkinsServiceName,
      jenkinsEnvMode:     'type',
    });
  }

  async function fetchRepoInfo() {
    if (!row.repo || fetchingInfo) return;
    setFetchingInfo(true);
    const [tagRes, srcRes, tgtRes] = await Promise.allSettled([
      apiGetNextTag(row.repo, row.jenkinsJob || undefined),
      row.sourceBranch ? apiGetBranchInfo(row.repo, row.sourceBranch) : Promise.resolve(null),
      row.targetBranch ? apiGetBranchInfo(row.repo, row.targetBranch) : Promise.resolve(null),
    ]);

    if (tagRes.status === 'fulfilled') {
      setPreviousTag(tagRes.value.previousTag || null);
      setLastDeployedTag(tagRes.value.lastDeployedTag || null);
      if (!row.tagName && tagRes.value.tagName) {
        updateRow(row.id, { tagName: tagRes.value.tagName });
      }
    }
    if (srcRes.status === 'fulfilled' && srcRes.value) setSourceBranchInfo(srcRes.value);
    if (tgtRes.status === 'fulfilled' && tgtRes.value) setTargetBranchInfo(tgtRes.value);

    setFetchingInfo(false);
  }

  const overall = computeOverallState(row);
  const anyBusy = mR || tR || pR || aR;
  const isDone  = overall === 'DONE';

  function field(key: keyof ServiceRowType, value: string | boolean | number) {
    updateRow(row.id, { [key]: value } as Partial<ServiceRowType>);
  }

  function step(key: keyof ServiceRowSteps, value: boolean) {
    updateSteps(row.id, { [key]: value });
  }

  return (
    <motion.div
      layout={!flat}
      initial={flat ? false as const : { opacity: 0, y: -8 }}
      animate={flat ? {} : { opacity: 1, y: 0 }}
      exit={flat ? {} : { opacity: 0, scale: 0.98 }}
      transition={flat ? undefined : { duration: 0.15 }}
      className={flat ? '' : 'rounded-lg border border-slate-800 bg-slate-900 shadow-sm'}
    >
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-slate-800 text-[10px] font-bold text-slate-400">
          {index + 1}
        </span>

        {/* Display name — locked from registry */}
        <ReadOnlyField
          value={row.name}
          placeholder="Select a repo…"
          className="w-40 min-w-0 shrink"
        />

        {/* Repo — dropdown driven by services.json registry */}
        <Select
          value={row.repo}
          onValueChange={handleRepoSelect}
          disabled={anyBusy || !registryLoaded}
        >
          <SelectTrigger className="h-7 min-w-0 flex-1 font-mono text-xs">
            <SelectValue placeholder={
              !registryLoaded         ? 'Loading…'         :
              registryMissing         ? 'No services.json' :
              repoNames.length === 0  ? 'Registry is empty' :
                                        'Select repository…'
            } />
          </SelectTrigger>
          <SelectContent className="max-h-72">
            {registryMissing || repoNames.length === 0 ? (
              <div className="px-3 py-4 text-center text-xs text-slate-500 space-y-1">
                {registryMissing ? (
                  <>
                    <p className="font-medium text-amber-400">services.json not found</p>
                    <p>Copy <span className="font-mono">services.example.json</span> to</p>
                    <p className="font-mono text-slate-400">frontend/public/services.json</p>
                    <p>and fill in your repositories.</p>
                  </>
                ) : (
                  <p>No entries in services.json yet.</p>
                )}
              </div>
            ) : (
              repoNames.map((repoName) => (
                <SelectItem key={repoName} value={repoName} className="font-mono text-xs">
                  {repoName}
                </SelectItem>
              ))
            )}
          </SelectContent>
        </Select>

        <Badge variant={row.type === 'SDK' ? 'default' : 'secondary'} className="shrink-0">
          {row.type}
        </Badge>

        <StatusBadge state={overall} className="shrink-0" />

        {!flat && (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 w-7 shrink-0 p-0 text-slate-500"
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </Button>
        )}

        <Button
          variant="ghost"
          size="sm"
          className="h-7 w-7 shrink-0 p-0 text-slate-600 hover:text-rose-400"
          onClick={() => removeRow(row.id)}
          disabled={anyBusy}
          aria-label="Remove row"
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

            {/* ── Fetch Info from GitHub ───────────────────────────────── */}
            <div className="flex items-center gap-3 border-b border-slate-800 px-4 py-2">
              <button
                type="button"
                onClick={fetchRepoInfo}
                disabled={anyBusy || fetchingInfo || !row.repo}
                className="flex h-7 items-center gap-1.5 rounded-md border border-slate-700 bg-slate-800 px-2.5 text-xs text-slate-300 hover:border-indigo-500 hover:text-indigo-300 disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
              >
                {fetchingInfo
                  ? <Loader2 className="h-3 w-3 animate-spin" />
                  : <RefreshCw className="h-3 w-3" />
                }
                {fetchingInfo ? 'Fetching…' : 'Fetch Info'}
              </button>
              <span className="text-[11px] text-slate-600">
                {!row.repo
                  ? 'Select a repository first'
                  : 'Latest GitHub tag · last Jenkins deploy · branch commits'}
              </span>
            </div>

            {/* ── Fields grid ─────────────────────────────────────────── */}
            <div className="grid grid-cols-2 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-3 lg:grid-cols-4">
              {/* Type — locked from registry */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Type</Label>
                <ReadOnlyField value={row.type} />
              </div>

              {/* Stage — always editable */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Stage</Label>
                <Input
                  type="number"
                  min={1}
                  max={99}
                  value={row.stage}
                  onChange={(e) => field('stage', Number(e.target.value))}
                  className="h-7 text-xs"
                  disabled={anyBusy}
                />
              </div>

              {/* Source branch */}
              <div className="space-y-1">
                <Label className="text-[10px] uppercase tracking-wider text-slate-500">Source branch</Label>
                <Input
                  value={row.sourceBranch}
                  onChange={(e) => field('sourceBranch', e.target.value)}
                  placeholder="feature/my-branch"
                  className="h-7 font-mono text-xs"
                  disabled={anyBusy || !row.steps.mergeBranch}
                />
                {sourceBranchInfo && (
                  <p className="flex items-center gap-1 text-[11px] text-slate-500 font-mono truncate">
                    <GitCommit className="h-3 w-3 shrink-0 text-slate-600" />
                    <span className="text-indigo-400">{sourceBranchInfo.shortSha}</span>
                    <span className="text-slate-600">·</span>
                    <span className="truncate">{sourceBranchInfo.authorName}{sourceBranchInfo.authorLogin ? ` (@${sourceBranchInfo.authorLogin})` : ''}</span>
                    <span className="text-slate-600">·</span>
                    <span className="truncate italic text-slate-500">{sourceBranchInfo.message}</span>
                  </p>
                )}
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
                {targetBranchInfo && (
                  <p className="flex items-center gap-1 text-[11px] text-slate-500 font-mono truncate">
                    <GitCommit className="h-3 w-3 shrink-0 text-slate-600" />
                    <span className="text-indigo-400">{targetBranchInfo.shortSha}</span>
                    <span className="text-slate-600">·</span>
                    <span className="truncate">{targetBranchInfo.authorName}{targetBranchInfo.authorLogin ? ` (@${targetBranchInfo.authorLogin})` : ''}</span>
                    <span className="text-slate-600">·</span>
                    <span className="truncate italic text-slate-500">{targetBranchInfo.message}</span>
                  </p>
                )}
              </div>

              {/* Tag name — SERVICE rows only */}
              {row.type === 'SERVICE' && (
                <div className="space-y-1 sm:col-span-2">
                  <Label className="text-[10px] uppercase tracking-wider text-slate-500">
                    Tag name
                    <span className="ml-1 text-indigo-400">(git_branch for Jenkins)</span>
                  </Label>
                  <Input
                    value={row.tagName}
                    onChange={(e) => field('tagName', e.target.value)}
                    placeholder="v1.0.0rc1"
                    className="h-7 font-mono text-xs"
                    disabled={anyBusy}
                  />
                  {(previousTag || lastDeployedTag) ? (
                    <div className="space-y-0.5">
                      {previousTag && (
                        <p className="text-[11px] text-slate-500">
                          Latest GitHub tag:
                          <span className="ml-1 font-mono text-amber-400">{previousTag}</span>
                        </p>
                      )}
                      {lastDeployedTag && (
                        <p className="text-[11px] text-slate-500">
                          Last Jenkins deploy:
                          <span className="ml-1 font-mono text-sky-400">{lastDeployedTag}</span>
                        </p>
                      )}
                    </div>
                  ) : (
                    <p className="text-[11px] text-slate-600">
                      Click <span className="text-slate-400">Fetch Info</span> to see latest tag and last deploy
                    </p>
                  )}
                </div>
              )}

              {/* Pipeline param hint — SDK only */}
              {row.type !== 'SERVICE' && row.steps.triggerPipeline && (
                <div className="space-y-1 sm:col-span-2 flex items-end">
                  <p className="text-[11px] text-slate-500">
                    Pipeline param:
                    <span className="ml-1 font-mono text-amber-400">
                      git_branch=origin/{row.targetBranch || 'env/staging'}
                    </span>
                  </p>
                </div>
              )}
            </div>

            {/* ── Jenkins URL builder ──────────────────────────────────── */}
            <div className="border-t border-slate-800 px-4 py-3">
              <p className="mb-3 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Jenkins job
                {isFromRegistry && (
                  <span className="ml-2 normal-case tracking-normal font-normal text-slate-600">
                    — category and service name set from registry
                  </span>
                )}
              </p>
              <JenkinsBuilder
                row={row}
                disabled={anyBusy}
                fromRegistry={isFromRegistry}
                onField={field}
              />
            </div>

            {/* ── Steps configuration ──────────────────────────────────── */}
            <div className="border-t border-slate-800 px-4 py-3">
              <p className="mb-2.5 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Steps to execute
              </p>
              <div className="flex flex-wrap gap-x-5 gap-y-2">
                <div className="flex items-center gap-2">
                  <Switch
                    id={`merge-${row.id}`}
                    checked={row.steps.mergeBranch}
                    onCheckedChange={(v) => step('mergeBranch', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`merge-${row.id}`} className="cursor-pointer text-xs text-slate-300">
                    Merge branch
                  </Label>
                </div>

                <div className="flex items-center gap-2">
                  <Switch
                    id={`tag-${row.id}`}
                    checked={row.steps.createTag}
                    onCheckedChange={(v) => step('createTag', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`tag-${row.id}`} className="cursor-pointer text-xs text-slate-300">
                    Create tag
                  </Label>
                </div>

                <div className="flex items-center gap-2">
                  <Switch
                    id={`pipeline-${row.id}`}
                    checked={row.steps.triggerPipeline}
                    onCheckedChange={(v) => step('triggerPipeline', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`pipeline-${row.id}`} className="cursor-pointer text-xs text-slate-300">
                    Trigger pipeline
                  </Label>
                </div>

                <div className="flex items-center gap-2">
                  <Switch
                    id={`jira-${row.id}`}
                    checked={row.steps.updateJira}
                    onCheckedChange={(v) => step('updateJira', v)}
                    disabled={anyBusy}
                  />
                  <Label htmlFor={`jira-${row.id}`} className="cursor-pointer text-xs text-slate-300">
                    Post Jira comment
                  </Label>
                </div>
              </div>
            </div>

            {/* ── Step states + action buttons ─────────────────────────── */}
            <div className="flex flex-wrap items-center gap-2 border-t border-slate-800 px-4 py-2.5">
              {row.steps.mergeBranch && (
                <StepBadge
                  step="Merge"
                  state={row.mergeState}
                  detail={row.mergeState === 'DONE' ? shortSha(row.mergeCommitSha) : null}
                />
              )}
              {row.type === 'SERVICE' && (
                <StepBadge
                  step="Tag"
                  state={row.tagState}
                  detail={row.tagState === 'DONE' && row.tagReleaseUrl ? 'released' : null}
                />
              )}
              {row.steps.triggerPipeline && (
                <StepBadge
                  step="Pipeline"
                  state={row.pipelineState}
                  detail={row.buildNumber ? `#${row.buildNumber}` : null}
                />
              )}

              <BuildLogButton row={row} />

              {row.errorMessage && overall === 'FAILED' && (
                <span className="text-[11px] text-rose-400">{row.errorMessage}</span>
              )}

              {/* ── Action buttons ──────────────────────────────────────── */}
              <div className="ml-auto flex items-center gap-1.5">
                {row.steps.mergeBranch && row.mergeState !== 'DONE' && (
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

                {row.steps.createTag && row.mergeState === 'DONE' && row.tagState !== 'DONE' && (
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

                {row.steps.triggerPipeline && row.pipelineState !== 'DONE' && (
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
                    <TooltipContent>
                      Trigger {row.jenkinsJob}
                      {row.type === 'SERVICE'
                        ? ` (git_branch=${row.tagName || '?'})`
                        : ` (git_branch=origin/${row.targetBranch})`}
                    </TooltipContent>
                  </Tooltip>
                )}

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
                    <TooltipContent>Run all configured steps in sequence</TooltipContent>
                  </Tooltip>
                )}
              </div>
            </div>

            {/* ── Conflict panel ─────────────────────────────────────────── */}
            {row.mergeState === 'CONFLICT' && (
              <div className="border-t border-slate-800 px-4 pb-3 pt-2">
                <ConflictPanel row={row} />
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
