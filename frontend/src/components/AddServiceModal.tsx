import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Button }    from '@/components/ui/button';
import { Input }     from '@/components/ui/input';
import { Label }     from '@/components/ui/label';
import { Switch }    from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import {
  Select, SelectTrigger, SelectContent, SelectItem, SelectValue,
} from '@/components/ui/select';

import { useDeployStore }    from '@/hooks/useDeployStore';
import { useServiceRegistry } from '@/hooks/useServiceRegistry';
import type { ServiceRowSteps } from '@/types';
import { ReadOnlyField }     from './ReadOnlyField';

interface AddServiceModalProps {
  open:    boolean;
  onClose: () => void;
}

const DEFAULT_STEPS: ServiceRowSteps = {
  mergeBranch:     true,
  createTag:       false,
  triggerPipeline: true,
  updateJira:      true,
};

export function AddServiceModal({ open, onClose }: AddServiceModalProps) {
  const { globalConfig, addRow, updateRow } = useDeployStore();
  const { entries, repoNames, missing: registryMissing } = useServiceRegistry();

  const [selectedRepo,  setSelectedRepo]  = useState('');
  const [stage,         setStage]         = useState(1);
  const [sourceBranch,  setSourceBranch]  = useState('');
  const [targetBranch,  setTargetBranch]  = useState('');
  const [steps,         setSteps]         = useState<ServiceRowSteps>(DEFAULT_STEPS);

  // Re-initialise form every time the modal opens
  useEffect(() => {
    if (!open) return;
    setSelectedRepo('');
    setStage(1);
    setSourceBranch(globalConfig.defaultSourceBranch || globalConfig.ticket);
    setTargetBranch(globalConfig.defaultTargetBranch || 'env/staging');
    setSteps(DEFAULT_STEPS);
  }, [open, globalConfig]);

  const registryEntry = selectedRepo ? entries[selectedRepo] : null;

  function handleRepoSelect(repoName: string) {
    setSelectedRepo(repoName);
    const entry = entries[repoName];
    if (entry) {
      setSteps((prev) => ({ ...prev, createTag: entry.type === 'SERVICE' }));
    }
  }

  function handleSave() {
    if (!selectedRepo) return;

    // Add a blank row, then immediately update it with all form data.
    // updateRow's side-effects handle jenkinsJob recomputation and tag fetching.
    addRow();
    const newRow = useDeployStore.getState().rows.at(-1)!;

    updateRow(newRow.id, {
      repo:               selectedRepo,
      name:               registryEntry?.displayName ?? selectedRepo,
      type:               registryEntry?.type        ?? 'SDK',
      stage,
      sourceBranch,
      targetBranch,
      jenkinsCategory:    registryEntry?.jenkinsCategory    ?? '',
      jenkinsServiceName: registryEntry?.jenkinsServiceName ?? '',
      jenkinsEnvMode:     'type',
      steps,
    });

    onClose();
  }

  const canSave = selectedRepo.length > 0;

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="w-full max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base">
            <Plus className="h-4 w-4 text-indigo-400" />
            Add service
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-1">
          {/* Repository */}
          <div className="space-y-1.5">
            <Label className="text-xs text-slate-300">Repository <span className="text-rose-400">*</span></Label>
            <Select
              value={selectedRepo}
              onValueChange={handleRepoSelect}
            >
              <SelectTrigger className="h-8 font-mono text-xs">
                <SelectValue placeholder={
                  registryMissing         ? 'No services.json found'  :
                  repoNames.length === 0  ? 'Registry is empty'       :
                                            'Select repository…'
                } />
              </SelectTrigger>
              <SelectContent className="max-h-60">
                {registryMissing || repoNames.length === 0 ? (
                  <div className="px-3 py-4 text-center text-xs text-slate-500">
                    {registryMissing
                      ? 'Copy services.example.json → services.json'
                      : 'No entries in services.json yet.'}
                  </div>
                ) : (
                  repoNames.map((name) => (
                    <SelectItem key={name} value={name} className="font-mono text-xs">
                      {name}
                    </SelectItem>
                  ))
                )}
              </SelectContent>
            </Select>
          </div>

          {/* Auto-filled fields (from registry) */}
          {registryEntry && (
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-500">Display name</Label>
                <ReadOnlyField value={registryEntry.displayName} />
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-500">Type</Label>
                <ReadOnlyField value={registryEntry.type} />
              </div>
            </div>
          )}

          <Separator />

          {/* Stage */}
          <div className="space-y-1.5">
            <Label htmlFor="as-stage" className="text-xs text-slate-300">
              Stage
              <span className="ml-1.5 text-slate-500 font-normal">
                (rows with the same stage run in parallel)
              </span>
            </Label>
            <Input
              id="as-stage"
              type="number"
              min={1}
              max={99}
              value={stage}
              onChange={(e) => setStage(Math.max(1, Number(e.target.value)))}
              className="h-8 w-24 text-xs"
            />
          </div>

          {/* Branches */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="as-src" className="text-xs text-slate-300">Source branch</Label>
              <Input
                id="as-src"
                value={sourceBranch}
                onChange={(e) => setSourceBranch(e.target.value)}
                placeholder="feature/my-branch"
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="as-tgt" className="text-xs text-slate-300">Target branch</Label>
              <Input
                id="as-tgt"
                value={targetBranch}
                onChange={(e) => setTargetBranch(e.target.value)}
                placeholder="env/staging"
                className="h-8 font-mono text-xs"
              />
            </div>
          </div>

          <Separator />

          {/* Steps */}
          <div className="space-y-2">
            <Label className="text-xs text-slate-400 uppercase tracking-wider">Steps to execute</Label>
            <div className="grid grid-cols-2 gap-y-2 gap-x-4">
              {(
                [
                  ['mergeBranch',     'Merge branch'],
                  ['createTag',       'Create tag'],
                  ['triggerPipeline', 'Trigger pipeline'],
                  ['updateJira',      'Post Jira comment'],
                ] as [keyof ServiceRowSteps, string][]
              ).map(([key, label]) => (
                <div key={key} className="flex items-center gap-2">
                  <Switch
                    id={`as-step-${key}`}
                    checked={steps[key]}
                    onCheckedChange={(v) => setSteps((prev) => ({ ...prev, [key]: v }))}
                  />
                  <Label htmlFor={`as-step-${key}`} className="cursor-pointer text-xs text-slate-300">
                    {label}
                  </Label>
                </div>
              ))}
            </div>
          </div>
        </div>

        <DialogFooter className="gap-2">
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={!canSave}
            onClick={handleSave}
            className="bg-indigo-600 hover:bg-indigo-500 text-white"
          >
            <Plus className="h-3.5 w-3.5 mr-1" />
            Add service
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
