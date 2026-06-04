import { useState } from 'react';
import { AnimatePresence } from 'framer-motion';
import { Plus, PackageSearch } from 'lucide-react';
import { Button } from '@/components/ui/button';

import { useDeployStore }   from '@/hooks/useDeployStore';
import { ServiceCard }      from './ServiceCard';
import { AddServiceModal }  from './AddServiceModal';

export function ServiceTable() {
  const rows = useDeployStore((s) => s.rows);
  const [addOpen, setAddOpen] = useState(false);

  return (
    <div className="space-y-3">
      {/* Section header */}
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
          onClick={() => setAddOpen(true)}
        >
          <Plus className="h-3.5 w-3.5" />
          Add service
        </Button>
      </div>

      {/* Content */}
      {rows.length === 0 ? (
        /* ── Empty state ───────────────────────────────────────────── */
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
          <Button
            variant="outline"
            size="sm"
            onClick={() => setAddOpen(true)}
            className="gap-2"
          >
            <Plus className="h-4 w-4" />
            Add first service
          </Button>
        </div>
      ) : (
        /* ── Horizontal scroll strip ──────────────────────────────── */
        <div className="relative">
          {/* Scroll container — negative horizontal margin lets it breathe to the page edge on mobile */}
          <div className="-mx-1 overflow-x-auto pb-3 px-1">
            <div className="flex gap-3">
              <AnimatePresence mode="popLayout">
                {rows.map((row, index) => (
                  <ServiceCard key={row.id} row={row} index={index} />
                ))}
              </AnimatePresence>

              {/* Trailing "add" card — always at the end of the strip */}
              <button
                type="button"
                onClick={() => setAddOpen(true)}
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
        </div>
      )}

      <AddServiceModal open={addOpen} onClose={() => setAddOpen(false)} />
    </div>
  );
}
