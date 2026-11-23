import dayjs, { type Dayjs } from "dayjs";
import type { StatementRange } from "./ledgerApi";

/** The URL state of an Account's detail: a range of Booking dates, as YYYY-MM-DD. */
export type AccountSearch = { from?: string; to?: string };

const DATE = "YYYY-MM-DD";

function date(value: unknown): string | undefined {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return undefined;
  }
  return dayjs(value).format(DATE) === value ? value : undefined;
}

/** Keeps the search parameters that are real dates and drops the rest. */
export function validateAccountSearch(
  search: Record<string, unknown>,
): AccountSearch {
  const from = date(search.from);
  const to = date(search.to);
  return {
    ...(from && { from }),
    ...(to && { to }),
  };
}

export function asDate(value: Dayjs | null): string | undefined {
  return value?.isValid() ? value.format(DATE) : undefined;
}

/**
 * The Booking Time range covering whole days in the browser's time zone: from the start of
 * `from` up to the start of the day after `to`.
 */
export function bookingTimeRange({ from, to }: AccountSearch): StatementRange {
  return {
    ...(from && { from: dayjs(from).startOf("day").toISOString() }),
    ...(to && { to: dayjs(to).add(1, "day").startOf("day").toISOString() }),
  };
}
