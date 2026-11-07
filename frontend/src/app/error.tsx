"use client"

import Link from "next/link"
import { Button } from "@/components/ui/button"

export default function ErrorPage({ reset }: { reset: () => void }) {
  return <div role="alert" className="mx-auto max-w-lg space-y-4 p-8 text-center">
    <h1 className="text-xl font-semibold">This page could not be loaded</h1>
    <p className="text-sm text-muted-foreground">Try again. If the problem continues, contact your administrator.</p>
    <div className="flex justify-center gap-3"><Button onClick={reset}>Try again</Button><Button variant="outline" asChild><Link href="/">Go to Home</Link></Button></div>
  </div>
}
