import { Lock } from 'lucide-react';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

interface ReadOnlyFieldProps {
  value:       string;
  placeholder?: string;
  monospace?:  boolean;
  className?:  string;
}

export function ReadOnlyField({
  value,
  placeholder = '—',
  monospace   = false,
  className   = '',
}: ReadOnlyFieldProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div
          className={
            `flex h-7 cursor-default select-none items-center gap-1.5 rounded-md ` +
            `border border-slate-700/50 bg-slate-800/40 px-2 text-xs ` +
            `${monospace ? 'font-mono' : ''} ${className}`
          }
        >
          <span className={`truncate ${value ? 'text-slate-300' : 'text-slate-600'}`}>
            {value || placeholder}
          </span>
          <Lock className="ml-auto h-3 w-3 shrink-0 text-slate-700" />
        </div>
      </TooltipTrigger>
      <TooltipContent side="top" className="text-xs">
        Set from service registry
      </TooltipContent>
    </Tooltip>
  );
}
