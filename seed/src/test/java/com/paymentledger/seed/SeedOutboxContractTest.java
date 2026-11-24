package com.paymentledger.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seed writes a TransactionPosted event to the outbox for every seeded Transaction, in the
 * shared envelope its JSON Schema describes, so the ledger's publisher sends it on like any other.
 * The producer side of the contract test, for seeded events.
 */
class SeedOutboxContractTest {

  private static final Path SCHEMA = Path.of("../docs/schemas/transaction-posted.v1.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void every_seeded_transaction_has_a_transaction_posted_event_in_the_outbox(@TempDir Path dir)
      throws IOException {
    try (CopyFiles files = new CopyFiles(dir)) {
      new SeedGenerator(10, SeedFixtures.INTERNAL).generate(files);
    }
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(SCHEMA));

    List<String> outbox = Files.readAllLines(dir.resolve("outbox.copy"));
    List<String> transactions = Files.readAllLines(dir.resolve("ledger_transaction.copy"));

    assertThat(outbox).hasSameSizeAs(transactions).isNotEmpty();
    for (int i = 0; i < outbox.size(); i++) {
      String[] row = outbox.get(i).split("\t", -1);
      String transactionId = transactions.get(i).split("\t", -1)[0];
      JsonNode event = JSON.readTree(unescape(row[3]));
      assertThat(schema.validate(event)).as("schema errors in %s", event).isEmpty();
      assertThat(row[0]).isEqualTo(event.get("eventId").asString());
      assertThat(row[1]).isEqualTo("ledger.transaction-posted.v1");
      assertThat(row[2]).isEqualTo(transactionId);
      assertThat(event.get("payload").get("transactionId").asString()).isEqualTo(transactionId);
      assertThat(event.get("occurredAt")).isEqualTo(event.get("payload").get("bookingTime"));
    }
  }

  /** A field in COPY text format, back to its value. */
  private static String unescape(String field) {
    StringBuilder value = new StringBuilder(field.length());
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (c == '\\' && i + 1 < field.length()) {
        char next = field.charAt(++i);
        value.append(
            switch (next) {
              case 't' -> '\t';
              case 'n' -> '\n';
              case 'r' -> '\r';
              default -> next;
            });
      } else {
        value.append(c);
      }
    }
    return value.toString();
  }
}
