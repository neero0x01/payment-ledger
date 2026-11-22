package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.shared.IdempotencyKey;
import com.paymentledger.ledger.shared.OperatorId;
import com.paymentledger.ledger.shared.RequestHash;

/** A Correction request's Idempotency-Key, the Operator who sent it and the hash of the request. */
public record KeyedCorrection(OperatorId operator, IdempotencyKey key, RequestHash requestHash) {}
