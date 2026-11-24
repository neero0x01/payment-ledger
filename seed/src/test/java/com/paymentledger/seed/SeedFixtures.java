package com.paymentledger.seed;

import com.paymentledger.ledger.shared.AccountId;
import java.util.UUID;

/** Fixed Internal Accounts for tests that generate seed data without a ledger database. */
final class SeedFixtures {

  static final InternalAccounts INTERNAL =
      new InternalAccounts(
          new AccountId(UUID.fromString("019a0000-0000-7000-8000-000000000001")),
          new AccountId(UUID.fromString("019a0000-0000-7000-8000-000000000002")),
          new AccountId(UUID.fromString("019a0000-0000-7000-8000-000000000003")));

  private SeedFixtures() {}
}
