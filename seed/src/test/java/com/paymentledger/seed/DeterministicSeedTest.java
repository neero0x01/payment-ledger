package com.paymentledger.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The seed generator has a fixed random seed, so every run writes byte-for-byte the same data. */
class DeterministicSeedTest {

  @Test
  void every_run_generates_identical_data(@TempDir Path first, @TempDir Path second)
      throws IOException {
    generateInto(first);
    generateInto(second);

    List<Path> tables = filesIn(first);
    assertThat(tables).isNotEmpty();
    assertThat(filesIn(second)).isEqualTo(tables);
    for (Path table : tables) {
      byte[] content = Files.readAllBytes(first.resolve(table));
      assertThat(content).as("%s is empty", table).isNotEmpty();
      assertThat(Files.readAllBytes(second.resolve(table))).as("%s", table).isEqualTo(content);
    }
  }

  private static void generateInto(Path directory) {
    try (CopyFiles files = new CopyFiles(directory)) {
      new SeedGenerator(40, SeedFixtures.INTERNAL).generate(files);
    }
  }

  private static List<Path> filesIn(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(Path::getFileName).sorted().toList();
    }
  }
}
