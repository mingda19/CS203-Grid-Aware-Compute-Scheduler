import { fileURLToPath } from "node:url"
import { defineConfig } from "vitest/config"

export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  // Component tests (*.test.tsx) use the automatic JSX runtime, like the app.
  oxc: { jsx: { runtime: "automatic" } },
})
