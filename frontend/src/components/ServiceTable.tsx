import { useState } from 'react';
import { AnimatePresence } from 'framer-motion';
import { Plus, PackageSearch } from 'lucide-react';
import { Button } from '@/components/ui/button';

import { useDeployStore }     from '@/hooks/useDeployStore';
import { ServiceCard }        from './ServiceCard';
import { ServiceDetailModal } from './ServiceDetailModal';

export function ServiceTable() {
  const { rows, addRow, removeRow } = useDeployStore();

  // ID of a row that was just created via "Add service" and whose modal is open
  const [pendingNewRowId, setPendingNewRowId] = useState<string | null>(null);

  const pendingRow      = pendingNewRowId ? rows.find((r) => r.id === pendingNewRowId)      ?? null : null;
  const pendingRowIndex = pendingNewRowId ? rows.findIndex((r) => r.id === pendingNewRowId)        : -1;

  function handleAddService() {
    addRow();
    // .getState() is synchronous — the row is in the array immediately after addRow()
    const newRow = useDeployStore.getState().rows.at(-1)!;
    setPendingNewRowId(newRow.id);
  }

  function handleNewRowClose() {
    // If the user closed without picking a repo, discard the empty row
    if (pendingNewRowId) {
      const row = useDeployStore.getState().rows.find((r) => r.id === pendingNewRowId);
      if (row && !row.repo) removeRow(pendingNewRowId);
    }
    setPendingNewRowId(null);
  }

  return (
    <div className="space-y-3">
      {/* Section header */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-300">
          Services &amp; SDKs
          {rows.length > 0 && (
            <span className="ml-2 text-xs font-normal text-slate-500">({rows.length})</span>
          )}
        </h2>
        <Button variant="outline" size="sm" className="gap-1.5 text-xs" onClick={handleAddService}>
          <Plus className="h-3.5 w-3.5" />
          Add service
        </Button>
      </div>

      {/* Content */}
      {rows.length === 0 ? (
        /* ── Empty state ───────────────────────────────────────────────────── */
        <div className="flex flex-col items-center justify-center gap-4 rounded-xl border border-dashed border-slate-700 bg-slate-900/50 p-12 text-center">
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-slate-800">
            <PackageSearch className="h-7 w-7 text-slate-500" />
          </div>
          <div>
            <p className="text-sm font-medium text-slate-300">No services added yet</p>
            <p className="mt-1 text-xs text-slate-500">
              Add an SDK or service to start building your deployment.
            </p>
          </div>
          <Button variant="outline" size="sm" onClick={handleAddService} className="gap-2">
            <Plus className="h-4 w-4" />
            Add first service
          </Button>
        </div>
      ) : (
        /* ── Horizontal scroll strip ───────────────────────────────────────── */
        <div className="-mx-1 overflow-x-auto pb-3 px-1">
          <div className="flex gap-3">
            <AnimatePresence mode="popLayout">
              {rows.map((row, index) => (
                <ServiceCard key={row.id} row={row} index={index} />
              ))}
            </AnimatePresence>

            {/* Trailing "add" tile always at the end of the strip */}
            <button
              type="button"
              onClick={handleAddService}
              className={
                `flex w-40 shrink-0 flex-col items-center justify-center gap-2 rounded-xl ` +
                `border border-dashed border-slate-700 bg-slate-900/50 text-slate-600 ` +
                `transition-colors hover:border-indigo-500 hover:text-indigo-400 ` +
                `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500`
              }
              aria-label="Add service"
            >
              <Plus className="h-5 w-5" />
              <span className="text-xs font-medium">Add service</span>
            </button>
          </div>
        </div>
      )}

      {/* "Add" modal — same detail modal used for existing cards */}
      {pendingRow && pendingRowIndex >= 0 && (
        <ServiceDetailModal
          row={pendingRow}
          index={pendingRowIndex}
          open
          onClose={handleNewRowClose}
          closeLabel="Add service"
        />
      )}
    </div>
  );
}
