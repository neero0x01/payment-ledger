package com.paymentledger.seed;

import java.util.List;

/**
 * A ledger table the seed bulk-loads with COPY, with the columns it fills, in the order the tables
 * are loaded so every foreign key finds its row.
 */
enum SeedTable {
  CUSTOMER("customer", List.of("id", "display_name", "created_at")),
  ACCOUNT(
      "account",
      List.of(
          "id",
          "kind",
          "customer_id",
          "name",
          "normal_side",
          "overdraft_limit_pence",
          "sort_code",
          "account_number",
          "created_at"));

  private final String table;

  @SuppressWarnings("ImmutableEnumChecker") // List.of makes an unmodifiable list.
  private final List<String> columns;

  SeedTable(String table, List<String> columns) {
    this.table = table;
    this.columns = columns;
  }

  String table() {
    return table;
  }

  List<String> columns() {
    return columns;
  }

  /** The name of the COPY text file that holds the table's rows. */
  String fileName() {
    return table + ".copy";
  }
}
