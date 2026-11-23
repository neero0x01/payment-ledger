package com.paymentledger.ledger.views.internal;

import com.paymentledger.ledger.shared.Problem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Where the next page of a Statement starts: just after the Statement Line with this Booking Time
 * and entry_seq, going back in time. Clients pass it back as an opaque string.
 */
record StatementCursor(Instant bookingTime, long entrySeq) {

  static StatementCursor after(StatementLine line) {
    return new StatementCursor(line.bookingTime(), line.entrySeq());
  }

  String encode() {
    String plain = bookingTime + "|" + entrySeq;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }

  /** The cursor a page's nextCursor gave; anything else is a validation-failed problem. */
  static StatementCursor decode(String cursor) {
    try {
      String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int bar = plain.indexOf('|');
      if (bar < 0) {
        throw Problem.validationFailed("Not a Statement cursor: " + cursor);
      }
      return new StatementCursor(
          Instant.parse(plain.substring(0, bar)), Long.parseLong(plain.substring(bar + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw Problem.validationFailed("Not a Statement cursor: " + cursor);
    }
  }
}
