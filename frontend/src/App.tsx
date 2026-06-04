import { useEffect } from 'react';
import { Providers }            from '@/components/providers';
import { DeploymentDashboard }  from '@/components/DeploymentDashboard';
import { useServiceRegistry }   from '@/hooks/useServiceRegistry';

export default function App() {
  const loadRegistry = useServiceRegistry((s) => s.load);

  useEffect(() => {
    loadRegistry();
  }, [loadRegistry]);

  return (
    <Providers>
      <DeploymentDashboard />
    </Providers>
  );
}
