package com.paymentledger.seed;

import com.paymentledger.ledger.shared.AccountId;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/**
 * Bulk-loads seed data into an empty ledger database with COPY, in one database transaction, so a
 * load either commits whole or leaves the ledger as it was. The ledger's deferred trigger checks
 * every seeded Transaction balances when it commits. It connects as the schema owner, which Flyway
 * also migrates as.
 */
final class SeedLoader implements AutoCloseable {

  private final Connection connection;

  private SeedLoader(Connection connection) {
    this.connection = connection;
  }

  /** Opens a database transaction on the ledger database. */
  static SeedLoader connect(String url, String user, String password) throws SQLException {
    Connection connection = DriverManager.getConnection(url, user, password);
    connection.setAutoCommit(false);
    return new SeedLoader(connection);
  }

  /**
   * Locks out other postings until the load ends, then refuses a ledger that already has
   * Transactions: seeded Entries are booked in the past, so their entry_seq would come after newer
   * ones.
   */
  void requireEmptyLedger() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("lock table ledger_transaction in exclusive mode");
      try (ResultSet rs =
          statement.executeQuery("select exists (select 1 from ledger_transaction)")) {
        rs.next();
        if (rs.getBoolean(1)) {
          throw new SeedRefused(
              "The ledger already has Transactions; the seed loads only into an empty ledger");
        }
      }
    }
  }

  /** The ids of the ledger's Internal Accounts, which its migrations created. */
  InternalAccounts internalAccounts() throws SQLException {
    return new InternalAccounts(
        internalAccount("Funding"),
        internalAccount("FPS Clearing"),
        internalAccount("Corrections"));
  }

  /** COPYs every table's file in, in foreign key order, and returns the rows loaded per table. */
  Map<SeedTable, Long> load(CopyFiles files) throws SQLException {
    CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
    Map<SeedTable, Long> rows = new EnumMap<>(SeedTable.class);
    for (SeedTable table : SeedTable.values()) {
      Path file = files.fileOf(table);
      if (!Files.exists(file)) {
        rows.put(table, 0L);
        continue;
      }
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        rows.put(table, copy.copyIn(table.copySql(), reader));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return rows;
  }

  /** Commits the load; the ledger's deferred checks run now. */
  void commit() throws SQLException {
    connection.commit();
  }

  @Override
  public void close() throws SQLException {
    if (!connection.getAutoCommit()) {
      connection.rollback();
    }
    connection.close();
  }

  private AccountId internalAccount(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select id from account where kind = 'INTERNAL' and name = ?")) {
      statement.setString(1, name);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("The ledger has no Internal Account " + name);
        }
        return new AccountId(rs.getObject(1, UUID.class));
      }
    }
  }
}
