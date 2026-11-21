const gbp = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
});

/** Shows an amount of pence as GBP. Pence stay integers; only the display divides. */
export function formatPence(pence: number): string {
  return gbp.format(pence / 100);
}
