import { useRef } from "react";

/**
 * One Idempotency-Key per user submission (CODING_STANDARDS rule 16). Every send of the form as it
 * stands, whether a retry or a double-click, gets the same key; editing the form, or finishing the
 * submission, starts a new one with a new key.
 */
export function useSubmissionKey() {
  const key = useRef<string | null>(null);
  return {
    /** The current submission's key, made the first time it is asked for. */
    current: () => (key.current ??= crypto.randomUUID()),
    /** Starts a new submission: the next send gets a new key. */
    next: () => {
      key.current = null;
    },
  };
}
