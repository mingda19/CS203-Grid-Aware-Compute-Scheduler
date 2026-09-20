export default function Home() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 px-6 text-white">
      <section className="max-w-xl space-y-4 text-center">
        <p className="text-sm font-semibold uppercase tracking-[0.2em] text-emerald-400">GACS</p>
        <h1 className="text-4xl font-bold tracking-tight">Grid-Aware Compute Scheduler</h1>
        <p className="text-slate-300">
          A starting point for scheduling flexible compute around changing grid prices.
        </p>
      </section>
    </main>
  );
}
