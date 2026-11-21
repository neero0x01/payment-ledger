package com.paymentledger.ledger;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Domain types are the plain-Java classes outside each module's {@code internal} package. Spring
 * wiring, JDBC and web adapters live in {@code internal}.
 */
class ArchitectureTest {

  private static final String ROOT = "com.paymentledger.ledger";
  private static final Path POSTING_SOURCES =
      Path.of("src/main/java/com/paymentledger/ledger/posting");
  private static final Pattern SQL_NOW =
      Pattern.compile("\\bnow\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void domain_types_have_no_spring_or_persistence_dependencies() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".(*)..")
        .and()
        .resideOutsideOfPackage("..internal..")
        .should()
        .dependOnClassesThat(
            resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..", "java.sql..", "javax.sql.."))
        .because("domain types stay plain Java (ADR 0001)")
        .check(classes);
  }

  @Test
  void posting_path_never_reads_the_wall_clock() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".posting..")
        .should()
        .callMethodWhere(
            DescribedPredicate.describe(
                "a java.time now() method",
                (JavaMethodCall call) ->
                    call.getName().equals("now")
                        && call.getTargetOwner().getPackageName().equals("java.time")))
        .because("Booking Time is read from clock_timestamp() after the locks (ADR 0004)")
        .check(classes);
  }

  @Test
  void posting_sql_never_uses_now() throws IOException {
    List<Path> offenders;
    try (Stream<Path> files = Files.walk(POSTING_SOURCES)) {
      offenders =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> SQL_NOW.matcher(read(path)).find())
              .toList();
    }
    assertThat(offenders)
        .as("now() is the transaction start time; use clock_timestamp() (ADR 0004)")
        .isEmpty();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
