import { AppShell } from "@/components/ui/efferd-dashboard-2-utils/app-shell";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export default function Home() {
  return (
    <AppShell>
      <Card>
        <CardHeader>
          <CardTitle>Operations workspace</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Navigation, responsive layout, and the mobile drawer are ready for dashboard data.
        </CardContent>
      </Card>
    </AppShell>
  );
}
