/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const ledger = process.env.LEDGER_URL ?? "http://localhost:8080";

// In development the ledger API and its docs are proxied, so the page and the API share an origin.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/v1": ledger,
      "/docs": ledger,
      "/swagger-ui": ledger,
      "/v3/api-docs": ledger,
    },
  },
  test: {
    environment: "jsdom",
    // Booking Times show in the browser's time zone; tests pin it.
    env: { TZ: "UTC" },
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    // e2e/ holds the Playwright smoke test, which runs against Compose.
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
