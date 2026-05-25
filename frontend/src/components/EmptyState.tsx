import { PackageSearch, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface EmptyStateProps {
  onAdd: () => void;
}

export function EmptyState({ onAdd }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 rounded-lg border border-dashed border-slate-700 bg-slate-900/50 p-12 text-center">
      <div className="flex h-14 w-14 items-center justify-center rounded-full bg-slate-800">
        <PackageSearch className="h-7 w-7 text-slate-500" />
      </div>
      <div>
        <p className="text-sm font-medium text-slate-300">No services added yet</p>
        <p className="mt-1 text-xs text-slate-500">
          Add an SDK or service to get started with your deployment.
        </p>
      </div>
      <Button variant="outline" size="sm" onClick={onAdd} className="gap-2">
        <Plus className="h-4 w-4" />
        Add first service
      </Button>
    </div>
  );
}
