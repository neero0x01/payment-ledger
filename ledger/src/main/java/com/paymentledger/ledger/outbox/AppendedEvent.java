package com.paymentledger.ledger.outbox;

import java.time.Duration;
import java.util.UUID;

/** An event appended to the outbox: its event id, and how long ago it was appended. */
public record AppendedEvent(UUID eventId, Duration age) {}
