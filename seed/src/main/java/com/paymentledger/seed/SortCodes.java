package com.paymentledger.seed;

/**
 * The sort codes in seed data. Every one is in the range 00-00-00 to 00-99-99, which this project
 * documents as fictional: it stands in for real UK sort codes and must never be used to make a real
 * payment. The fintech's own Customer Accounts share one sort code in it; the Payees of seeded
 * Faster Payments are at the rest.
 */
final class SortCodes {

  /** The lowest sort code in the fictional range. */
  static final String FICTIONAL_FROM = "000000";

  /** The highest sort code in the fictional range. */
  static final String FICTIONAL_TO = "009999";

  /** The sort code of every seeded Customer Account at the fintech. */
  static final String FINTECH = "000100";

  private SortCodes() {}
}
