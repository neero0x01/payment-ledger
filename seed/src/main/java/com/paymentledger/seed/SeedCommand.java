package com.paymentledger.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;

/**
 * The seed CLI (spec §10). It migrates the ledger database, refuses one that already has
 * Transactions, generates the seed data from its fixed random seed and bulk-loads it with COPY in
 * one database transaction. The running ledger's outbox publisher then drains the seeded events
 * into Kafka, and the projector builds the Read Model from them.
 *
 * <pre>
 * java -jar seed.jar [--url=jdbc:postgresql://localhost:5432/ledger] [--user=ledger]
 *                    [--password=ledger] [--customers=5000] [--load-test-password=…]
 * </pre>
 *
 * The connection defaults come from LEDGER_DB_URL, LEDGER_DB_USER and LEDGER_DB_PASSWORD, as the
 * ledger's do, and the user must own the schema. Fewer Customers make a smaller seed in the same
 * proportions.
 *
 * <p>With a load-test password, from {@code --load-test-password} or SEED_LOAD_TEST_PASSWORD, each
 * of the 200 load-test Customers gets a login, {@code load-test-001} to {@code load-test-200}, with
 * that password. Without one, the seed adds no logins: there is no default password.
 */
public final class SeedCommand {

  private static final Set<String> OPTIONS =
      Set.of("url", "user", "password", "customers", "load-test-password");

  private SeedCommand() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (SeedRefused | IllegalArgumentException e) {
      System.err.println("Seed refused: " + e.getMessage());
      System.exit(1);
    }
  }

  /** Seeds the ledger database the arguments name; refuses with {@link SeedRefused}. */
  public static void run(String... args) {
    Map<String, String> options = parse(args);
    String url =
        options.getOrDefault(
            "url", env("LEDGER_DB_URL", "jdbc:postgresql://localhost:5432/ledger"));
    String user = options.getOrDefault("user", env("LEDGER_DB_USER", "ledger"));
    String password = options.getOrDefault("password", env("LEDGER_DB_PASSWORD", "ledger"));
    int customers = Integer.parseInt(options.getOrDefault("customers", "5000"));
    String loadTestPassword =
        options.getOrDefault("load-test-password", env("SEED_LOAD_TEST_PASSWORD", ""));

    Flyway.configure().dataSource(url, user, password).load().migrate();
    Path directory = temporaryDirectory();
    try (SeedLoader loader = SeedLoader.connect(url, user, password)) {
      loader.requireEmptyLedger();
      long started = System.nanoTime();
      CopyFiles files = new CopyFiles(directory);
      try (files) {
        new SeedGenerator(customers, loader.internalAccounts()).generate(files);
      }
      long generated = System.nanoTime();
      Map<SeedTable, Long> rows = loader.load(files);
      if (loadTestPassword.isEmpty()) {
        System.out.println("No load-test password given, so no load-test logins were added");
      } else {
        int logins = loader.addLoadTestLogins(loadTestPassword);
        System.out.printf("Added %d load-test logins, load-test-NNN%n", logins);
      }
      long copied = System.nanoTime();
      loader.commit();
      long committed = System.nanoTime();
      System.out.printf(
          "Seeded %s in %s (generate %s, COPY %s, commit %s)%n",
          rows,
          Duration.ofNanos(committed - started),
          Duration.ofNanos(generated - started),
          Duration.ofNanos(copied - generated),
          Duration.ofNanos(committed - copied));
    } catch (SQLException e) {
      throw new IllegalStateException("The seed could not load the ledger database", e);
    } finally {
      deleteRecursively(directory);
    }
  }

  private static Map<String, String> parse(String... args) {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int equals = arg.indexOf('=');
      String name = arg.startsWith("--") && equals > 2 ? arg.substring(2, equals) : "";
      if (!OPTIONS.contains(name)) {
        throw new IllegalArgumentException(
            "Unknown argument "
                + arg
                + "; expected --url, --user, --password, --customers or --load-test-password");
      }
      options.put(name, arg.substring(equals + 1));
    }
    return options;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("seed");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
