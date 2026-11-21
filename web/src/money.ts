const gbp = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
});

/**
 * Reads an amount typed in pounds, such as "30" or "30.05", as whole pence without going through
 * floating point. Returns undefined for anything else.
 */
export function parsePounds(text: string): number | undefined {
  const match = text.trim().match(/^(\d+)(?:\.(\d{1,2}))?$/);
  if (!match) return undefined;
  const [, pounds = "0", pence = ""] = match;
  return Number(pounds) * 100 + Number(pence.padEnd(2, "0"));
}

/** Shows an amount of pence as GBP. Pence stay integers; only the display divides. */
export function formatPence(pence: number): string {
  return gbp.format(pence / 100);
}
