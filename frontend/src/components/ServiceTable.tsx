import { AnimatePresence } from 'framer-motion';
import { Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useDeployStore } from '@/hooks/useDeployStore';
import { ServiceRow } from './ServiceRow';
import { EmptyState } from './EmptyState';

export function ServiceTable() {
  const { rows, addRow } = useDeployStore();

  return (
    <div className="space-y-3">
      {/* Table header */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-300">
          Services &amp; SDKs
          {rows.length > 0 && (
            <span className="ml-2 text-xs font-normal text-slate-500">
              ({rows.length})
            </span>
          )}
        </h2>
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5 text-xs"
          onClick={addRow}
        >
          <Plus className="h-3.5 w-3.5" />
          Add service
        </Button>
      </div>

      {/* Rows */}
      {rows.length === 0 ? (
        <EmptyState onAdd={addRow} />
      ) : (
        <div className="space-y-2">
          <AnimatePresence mode="popLayout">
            {rows.map((row, index) => (
              <ServiceRow key={row.id} row={row} index={index} />
            ))}
          </AnimatePresence>
        </div>
      )}
    </div>
  );
}
