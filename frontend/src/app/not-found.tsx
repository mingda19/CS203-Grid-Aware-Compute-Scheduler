import Link from "next/link"
import { Button } from "@/components/ui/button"

export default function NotFound() {
  return <div className="mx-auto max-w-lg space-y-4 p-8 text-center">
    <h1 className="text-xl font-semibold">Page not found</h1>
    <p className="text-sm text-muted-foreground">The page may have moved or the address may be incorrect.</p>
    <Button asChild><Link href="/">Go to overview</Link></Button>
  </div>
}
