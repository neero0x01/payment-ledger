package com.paymentledger.ledger.shared;

import java.net.URI;

/** The shared catalogue of RFC 9457 problem types. Clients branch on these stable type URIs. */
public enum ProblemType {
  VALIDATION_FAILED("validation-failed", "Validation failed", 400),
  NOT_FOUND("not-found", "Not found", 404),
  ALREADY_REVERSED("already-reversed", "Already reversed", 409);

  private final URI uri;
  private final String title;
  private final int status;

  ProblemType(String slug, String title, int status) {
    this.uri = URI.create("/problems/" + slug);
    this.title = title;
    this.status = status;
  }

  public URI uri() {
    return uri;
  }

  public String title() {
    return title;
  }

  /** The HTTP status a problem of this type is answered with. */
  public int status() {
    return status;
  }
}
