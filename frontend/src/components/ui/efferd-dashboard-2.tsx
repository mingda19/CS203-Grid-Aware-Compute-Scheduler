import { AppShell } from "./efferd-dashboard-2-utils/app-shell";
import { Dashboard } from "./efferd-dashboard-2-utils/dashboard";

export function EfferdDashboard2() {
  return (
    <div
      className="bg-background text-foreground"
      data-efferd-dashboard-2="true"
    >
      <AppShell>
        <Dashboard />
      </AppShell>
    </div>
  );
}

export default EfferdDashboard2;
