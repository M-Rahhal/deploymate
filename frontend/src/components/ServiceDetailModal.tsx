import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { ServiceRow } from './ServiceRow';
import type { ServiceRow as ServiceRowType } from '@/types';

interface ServiceDetailModalProps {
  row:          ServiceRowType;
  index:        number;
  open:         boolean;
  onClose:      () => void;
  /** Label for the primary close/confirm button. Defaults to "Done". */
  closeLabel?:  string;
}

export function ServiceDetailModal({
  row,
  index,
  open,
  onClose,
  closeLabel = 'Done',
}: ServiceDetailModalProps) {
  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="w-full max-w-3xl max-h-[90vh] overflow-y-auto p-0">
        <DialogHeader className="px-4 pt-4 pb-0">
          <DialogTitle className="text-sm font-semibold text-slate-200">
            {row.name || row.repo || 'Service details'}
            {row.repo && row.name && (
              <span className="ml-2 font-mono text-xs font-normal text-slate-500">
                {row.repo}
              </span>
            )}
          </DialogTitle>
        </DialogHeader>

        <ServiceRow row={row} index={index} flat />

        <DialogFooter className="px-4 py-3 border-t border-slate-800">
          <Button
            size="sm"
            className="bg-indigo-600 hover:bg-indigo-500 text-white"
            onClick={onClose}
          >
            {closeLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
