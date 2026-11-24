package com.paymentledger.seed;

import java.nio.file.Path;

/** Writes the seed data as one COPY text file per table. */
public final class CopyFiles implements SeedSink {

  public CopyFiles(Path directory) {}
}
