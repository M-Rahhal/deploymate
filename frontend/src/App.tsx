import { Providers } from '@/components/providers';
import { DeploymentDashboard } from '@/components/DeploymentDashboard';

export default function App() {
  return (
    <Providers>
      <DeploymentDashboard />
    </Providers>
  );
}
