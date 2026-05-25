import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

export function shortSha(sha: string | null): string {
  if (!sha) return 'n/a';
  return sha.length > 7 ? sha.slice(0, 7) : sha;
}

export function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString('en-US', { hour12: false });
  } catch {
    return iso;
  }
}

/** Auto-generates a tag name: prefix-YYYYMMDD-repo-001 */
export function generateTagName(repo: string, prefix = 'env-stag', sequence = 1): string {
  const date = new Date().toISOString().slice(0, 10).replace(/-/g, '');
  const seq  = String(sequence).padStart(3, '0');
  return `${prefix}-${date}-${repo}-${seq}`;
}
