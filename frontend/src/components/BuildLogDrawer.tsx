import { useState } from 'react';
import { Terminal, ExternalLink } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Dialog, DialogContent, DialogHeader,
  DialogTitle, DialogDescription,
} from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import type { ServiceRow } from '@/types';

interface BuildLogDrawerProps {
  row:  ServiceRow;
  open: boolean;
  onClose: () => void;
}

export function BuildLogDrawer({ row, open, onClose }: BuildLogDrawerProps) {
  const log = row.buildLog ?? '(no log available)';

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) onClose(); }}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-slate-100">
            <Terminal className="h-4 w-4 text-indigo-400" />
            Build log — {row.name || row.repo}
            {row.buildNumber && (
              <span className="text-sm font-normal text-slate-400">
                #{row.buildNumber}
              </span>
            )}
          </DialogTitle>
          <DialogDescription className="text-slate-500">
            {row.buildUrl ? (
              <a
                href={row.buildUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 hover:text-indigo-400"
              >
                View full build in Jenkins <ExternalLink className="h-3 w-3" />
              </a>
            ) : (
              'Last captured log fragment'
            )}
          </DialogDescription>
        </DialogHeader>

        <ScrollArea className="h-96 rounded-md border border-slate-800 bg-black/60">
          <pre className="whitespace-pre-wrap break-all p-4 font-mono text-[11px] leading-relaxed text-slate-300">
            {log}
          </pre>
        </ScrollArea>

        <div className="flex justify-end gap-2">
          {row.buildUrl && (
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              onClick={() => window.open(row.buildUrl!, '_blank', 'noopener')}
            >
              <ExternalLink className="h-3.5 w-3.5" />
              Open in Jenkins
            </Button>
          )}
          <Button variant="secondary" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Trigger button that opens the drawer */
interface BuildLogButtonProps {
  row: ServiceRow;
}

export function BuildLogButton({ row }: BuildLogButtonProps) {
  const [open, setOpen] = useState(false);

  if (!row.buildLog && !row.buildUrl) return null;

  return (
    <>
      <Button
        variant="ghost"
        size="sm"
        className="h-6 gap-1 px-2 text-[11px] text-slate-400 hover:text-slate-200"
        onClick={() => setOpen(true)}
      >
        <Terminal className="h-3 w-3" />
        Log
      </Button>
      <BuildLogDrawer row={row} open={open} onClose={() => setOpen(false)} />
    </>
  );
}
