"use client"

import * as React from "react"
import { useRouter } from "next/navigation"

export default function EnergyPage() {
  const router = useRouter()

  React.useEffect(() => {
    router.replace("/overview")
  }, [router])

  return null
}
