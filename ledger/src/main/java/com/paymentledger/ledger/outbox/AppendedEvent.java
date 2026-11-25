package com.paymentledger.ledger.outbox;

import java.time.Duration;

/** An event appended to the outbox: its event id, and how long ago it was appended. */
public record AppendedEvent(EventId eventId, Duration age) {}
