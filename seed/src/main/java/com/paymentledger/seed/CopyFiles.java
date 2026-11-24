package com.paymentledger.seed;

import com.paymentledger.ledger.payments.Payee;
import com.paymentledger.ledger.payments.Payment;
import com.paymentledger.ledger.payments.PaymentType;
import com.paymentledger.ledger.posting.CorrectionDetails;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.shared.PaymentId;
import com.paymentledger.ledger.shared.TransactionId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Writes the seed data as one file per table in Postgres's COPY text format, ready for {@code COPY
 * table (columns) FROM STDIN}. Close it to flush the files.
 */
public final class CopyFiles implements SeedSink, AutoCloseable {

  private final Path directory;
  private final Map<SeedTable, Writer> writers = new EnumMap<>(SeedTable.class);

  public CopyFiles(Path directory) {
    this.directory = directory;
  }

  /** The file holding the table's rows, which exists once a row has been written to it. */
  Path fileOf(SeedTable table) {
    return directory.resolve(table.fileName());
  }

  @Override
  public void customer(SeededCustomer customer) {
    row(SeedTable.CUSTOMER, customer.id().value(), customer.displayName(), customer.createdAt());
  }

  @Override
  public void account(SeededAccount seeded) {
    var account = seeded.account();
    row(
        SeedTable.ACCOUNT,
        account.id().value(),
        account.kind().name(),
        account.owner() == null ? null : account.owner().value(),
        account.name(),
        account.normalSide().name(),
        account.overdraftLimit().pence(),
        seeded.sortCode(),
        seeded.accountNumber(),
        seeded.createdAt());
  }

  /**
   * A seeded Payment carries an Idempotency-Key no client sends and an empty request hash, so it
   * can never be replayed. A Faster Payment had one Scheme Submission, when it was made.
   */
  @Override
  public void payment(SeededPayment seeded) {
    Payment payment = seeded.payment();
    Payee payee = payment.payee();
    Payee.AtTheFintech atTheFintech = payee instanceof Payee.AtTheFintech p ? p : null;
    Payee.AtAnotherBank atAnotherBank = payee instanceof Payee.AtAnotherBank p ? p : null;
    boolean fasterPayment = payment.type() == PaymentType.FASTER_PAYMENT;
    row(
        SeedTable.PAYMENT,
        payment.id().value(),
        seeded.principal().value(),
        "seed:" + payment.id().value(),
        "",
        payment.type().name(),
        payment.debtorAccount().value(),
        atTheFintech == null ? null : atTheFintech.account().value(),
        atAnotherBank == null ? null : atAnotherBank.sortCode(),
        atAnotherBank == null ? null : atAnotherBank.accountNumber(),
        atAnotherBank == null ? null : atAnotherBank.name(),
        payment.amount().pence(),
        payment.status().name(),
        payment.rejection().map(Enum::name).orElse(null),
        payment.schemeRejection().map(Enum::name).orElse(null),
        seeded.endToEndId() == null ? null : seeded.endToEndId().value(),
        fasterPayment ? 1 : 0,
        fasterPayment ? seeded.createdAt() : null,
        seeded.createdAt(),
        seeded.completedAt());
  }

  @Override
  public void transaction(PostedTransaction transaction, UUID eventId) {
    CorrectionDetails correction = transaction.correction().orElse(null);
    row(
        SeedTable.LEDGER_TRANSACTION,
        transaction.id().value(),
        transaction.kind().name(),
        transaction.bookingTime(),
        transaction.payment().map(PaymentId::value).orElse(null),
        transaction.reverses().map(TransactionId::value).orElse(null),
        correction == null ? null : correction.reason().name(),
        correction == null ? null : correction.note(),
        transaction.source().name());
    for (PostedEntry posted : transaction.postedEntries()) {
      row(
          SeedTable.ENTRY,
          transaction.id().value(),
          posted.entry().account().value(),
          posted.entry().side().name(),
          posted.entry().amount().pence(),
          transaction.bookingTime(),
          posted.entrySeq(),
          posted.balanceAfter().pence());
    }
    row(
        SeedTable.OUTBOX,
        eventId,
        TransactionPostedJson.TOPIC,
        transaction.id().value(),
        TransactionPostedJson.of(transaction, eventId));
  }

  @Override
  public void balance(SeededBalance balance) {
    row(
        SeedTable.ACCOUNT_BALANCE,
        balance.account().value(),
        balance.balance().pence(),
        balance.lastEntrySeq());
  }

  @Override
  public void close() {
    List<IOException> failures = new ArrayList<>();
    for (Writer writer : writers.values()) {
      try {
        writer.close();
      } catch (IOException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      throw new UncheckedIOException(failures.getFirst());
    }
  }

  private void row(SeedTable table, @Nullable Object... values) {
    if (values.length != table.columns()) {
      throw new IllegalArgumentException(
          table + " has " + table.columns() + " columns, got " + values.length);
    }
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        line.append('\t');
      }
      line.append(field(values[i]));
    }
    line.append('\n');
    try {
      writerOf(table).write(line.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Writer writerOf(SeedTable table) throws IOException {
    Writer writer = writers.get(table);
    if (writer == null) {
      Files.createDirectories(directory);
      writer =
          new BufferedWriter(
              Files.newBufferedWriter(fileOf(table), StandardCharsets.UTF_8), 1 << 16);
      writers.put(table, writer);
    }
    return writer;
  }

  /** A value in COPY text format: \N for null, and backslash escapes for the special characters. */
  private static String field(@Nullable Object value) {
    if (value == null) {
      return "\\N";
    }
    String text = value instanceof Instant instant ? instant.toString() : value.toString();
    StringBuilder escaped = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> escaped.append("\\\\");
        case '\t' -> escaped.append("\\t");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }
}
