package com.paymentledger.seed;

/** The seed refuses to load into this ledger database, and has changed nothing. */
public final class SeedRefused extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SeedRefused(String message) {
    super(message);
  }
}
