package com.paymentledger.seed;

import java.util.Locale;

/**
 * A ledger table the seed bulk-loads, with the COPY statement that loads it and how many columns
 * that statement fills, in the order the tables are loaded so every foreign key finds its row.
 * Columns left out keep their defaults: an outbox row is created, and a balance row updated, when
 * the load runs.
 */
enum SeedTable {
  CUSTOMER(
      """
      copy customer (id, display_name, created_at) from stdin
      """,
      3),
  ACCOUNT(
      """
      copy account (
          id, kind, customer_id, name, normal_side, overdraft_limit_pence,
          sort_code, account_number, created_at)
      from stdin
      """,
      9),
  PAYMENT(
      """
      copy payment (
          id, principal_id, idempotency_key, request_hash, type, debtor_account_id,
          payee_account_id, payee_sort_code, payee_account_number, payee_name, amount_pence,
          status, rejection_reason, scheme_reason_code, end_to_end_id,
          submission_attempts, last_submitted_at, created_at, completed_at)
      from stdin
      """,
      19),
  LEDGER_TRANSACTION(
      """
      copy ledger_transaction (
          id, kind, booked_at, payment_id, reverses_transaction_id,
          correction_reason, correction_note, source)
      from stdin
      """,
      8),
  ENTRY(
      """
      copy entry (
          transaction_id, account_id, side, amount_pence, booked_at,
          entry_seq, balance_after_pence)
      from stdin
      """,
      7),
  ACCOUNT_BALANCE(
      """
      copy account_balance (account_id, balance_pence, last_entry_seq) from stdin
      """,
      3),
  OUTBOX(
      """
      copy outbox (event_id, topic, msg_key, payload) from stdin
      """,
      4);

  private final String copySql;
  private final int columns;

  SeedTable(String copySql, int columns) {
    this.copySql = copySql;
    this.columns = columns;
  }

  /** The COPY statement that loads the table from text-format rows. */
  String copySql() {
    return copySql;
  }

  /** How many columns each row has. */
  int columns() {
    return columns;
  }

  /** The name of the COPY text file that holds the table's rows. */
  String fileName() {
    return name().toLowerCase(Locale.ROOT) + ".copy";
  }
}
