import { create } from 'zustand';

export interface ServiceRegistryEntry {
  displayName:        string;
  type:               'SDK' | 'SERVICE';
  jenkinsCategory:    string;
  jenkinsServiceName: string;
}

interface ServiceRegistryStore {
  entries:   Record<string, ServiceRegistryEntry>;
  repoNames: string[];
  loaded:    boolean;
  missing:   boolean;
  load:      () => Promise<void>;
}

export const useServiceRegistry = create<ServiceRegistryStore>((set, get) => ({
  entries:   {},
  repoNames: [],
  loaded:    false,
  missing:   false,

  load: async () => {
    if (get().loaded) return;
    try {
      const response = await fetch('/services.json');
      if (response.status === 404) {
        set({ loaded: true, missing: true });
        return;
      }
      if (!response.ok) {
        set({ loaded: true, missing: true });
        return;
      }
      const data    = await response.json() as { services?: Record<string, ServiceRegistryEntry> };
      const entries = data.services ?? {};
      set({
        entries,
        repoNames: Object.keys(entries).sort(),
        loaded:    true,
        missing:   false,
      });
    } catch {
      set({ loaded: true, missing: true });
    }
  },
}));
