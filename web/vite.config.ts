/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In development the ledger API is proxied, so the page and the API share an origin.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/v1": process.env.LEDGER_URL ?? "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    // Booking Times show in the browser's time zone; tests pin it.
    env: { TZ: "UTC" },
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});
