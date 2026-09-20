import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export default function Demo() {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Card>
        <CardHeader><CardTitle>Current grid price</CardTitle></CardHeader>
        <CardContent className="font-mono text-2xl">$28.40 / MWh</CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Flexible workloads</CardTitle></CardHeader>
        <CardContent className="font-mono text-2xl">4 registered</CardContent>
      </Card>
      <Card className="sm:col-span-2">
        <CardHeader><CardTitle>ERCOT North overview</CardTitle></CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Static market and workload values now provide the domain model for the scheduling dashboard.
        </CardContent>
      </Card>
    </div>
  );
}
