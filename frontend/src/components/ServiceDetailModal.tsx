import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ServiceRow } from './ServiceRow';
import type { ServiceRow as ServiceRowType } from '@/types';

interface ServiceDetailModalProps {
  row:     ServiceRowType;
  index:   number;
  open:    boolean;
  onClose: () => void;
}

export function ServiceDetailModal({ row, index, open, onClose }: ServiceDetailModalProps) {
  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="w-full max-w-3xl max-h-[90vh] overflow-y-auto p-0">
        <DialogHeader className="px-4 pt-4 pb-0">
          <DialogTitle className="text-sm font-semibold text-slate-200">
            {row.name || row.repo || 'Service details'}
            {row.repo && row.name && (
              <span className="ml-2 font-mono text-xs font-normal text-slate-500">{row.repo}</span>
            )}
          </DialogTitle>
        </DialogHeader>
        <ServiceRow row={row} index={index} flat />
      </DialogContent>
    </Dialog>
  );
}
