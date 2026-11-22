package com.paymentledger.ledger;

import com.paymentledger.ledger.accounts.Accounts;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.Posting;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.CustomerId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.Side;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Arranges ledger state for tests through the modules' public APIs. v1 has no endpoint for opening
 * Accounts, so this is the only way in; assertions still go through the HTTP API.
 */
@Component
public class LedgerFixture {

  private final Accounts accounts;
  private final Posting posting;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final Map<AccountId, CustomerId> owners = new ConcurrentHashMap<>();
  private final Map<AccountId, TransactionId> fundings = new ConcurrentHashMap<>();

  LedgerFixture(
      Accounts accounts, Posting posting, JdbcClient jdbc, TransactionTemplate transaction) {
    this.accounts = accounts;
    this.posting = posting;
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  /** The debit-normal Funding Account. */
  public AccountId funding() {
    return accounts.funding();
  }

  /** The Corrections Account, on the other side of every Adjustment. */
  public AccountId corrections() {
    return accounts.corrections();
  }

  /** Opens a Customer Account for a new Customer, funded from Funding by a real Transaction. */
  public AccountId customerAccountWithBalance(Money balance) {
    return customerAccountWithBalance(balance, Money.ofPence(0));
  }

  /**
   * Opens a Customer Account with an Overdraft Limit for a new Customer, funded from Funding by a
   * real Transaction.
   */
  public AccountId customerAccountWithBalance(Money balance, Money overdraftLimit) {
    CustomerId customer = accounts.registerCustomer("Test Customer");
    AccountId account = accounts.openCustomerAccount(customer, "Main", overdraftLimit);
    owners.put(account, customer);
    if (balance.isPositive()) {
      TransactionId funding =
          posting.post(
              NewTransaction.funding(
                  List.of(
                      new Entry(accounts.funding(), Side.DEBIT, balance),
                      new Entry(account, Side.CREDIT, balance))));
      fundings.put(account, funding);
    }
    return account;
  }

  /** The Transaction that funded a Customer Account this fixture opened with a Balance. */
  public TransactionId lastFundingOf(AccountId account) {
    TransactionId funding = fundings.get(account);
    if (funding == null) {
      throw new IllegalArgumentException("This fixture did not fund Account " + account.value());
    }
    return funding;
  }

  /** The Customer who owns a Customer Account this fixture opened. */
  public CustomerId ownerOf(AccountId account) {
    CustomerId owner = owners.get(account);
    if (owner == null) {
      throw new IllegalArgumentException("This fixture did not open Account " + account.value());
    }
    return owner;
  }

  /**
   * Funds a Customer Account from Funding by a Transaction booked at a past moment. Posting always
   * books now, so this writes the rows by hand, as only the seed generator may in the application.
   */
  public TransactionId fundingBookedAt(AccountId account, Money amount, Instant bookingTime) {
    return transaction.execute(status -> {
      OffsetDateTime bookedAt = bookingTime.atOffset(ZoneOffset.UTC);
      UUID id =
          jdbc.sql(
                  """
                  insert into ledger_transaction (kind, booked_at)
                  values ('FUNDING', :bookedAt)
                  returning id
                  """)
              .param("bookedAt", bookedAt)
              .query(UUID.class)
              .single();
      // Funding and Customer Accounts both move up on these sides of their Normal Side.
      insertBackdatedEntry(id, accounts.funding(), Side.DEBIT, amount, amount, bookedAt);
      insertBackdatedEntry(id, account, Side.CREDIT, amount, amount, bookedAt);
      return new TransactionId(id);
    });
  }

  /**
   * Reverses a funding this fixture booked with a Reversing Correction booked at a past moment,
   * written by hand like {@link #fundingBookedAt}.
   */
  public TransactionId reversalOfFundingBookedAt(
      TransactionId funding, AccountId account, Money amount, Instant bookingTime) {
    return transaction.execute(status -> {
      OffsetDateTime bookedAt = bookingTime.atOffset(ZoneOffset.UTC);
      UUID id =
          jdbc.sql(
                  """
                  insert into ledger_transaction (
                      kind, booked_at, reverses_transaction_id, correction_reason, correction_note)
                  values ('CORRECTION', :bookedAt, :reverses, 'DUPLICATE_POSTING', 'Booked twice')
                  returning id
                  """)
              .param("bookedAt", bookedAt)
              .param("reverses", funding.value())
              .query(UUID.class)
              .single();
      // Both Accounts move down on these sides of their Normal Side.
      Money down = amount.negate();
      insertBackdatedEntry(id, accounts.funding(), Side.CREDIT, amount, down, bookedAt);
      insertBackdatedEntry(id, account, Side.DEBIT, amount, down, bookedAt);
      return new TransactionId(id);
    });
  }

  private void insertBackdatedEntry(
      UUID transactionId,
      AccountId account,
      Side side,
      Money amount,
      Money balanceChange,
      OffsetDateTime bookedAt) {
    BalanceRow after =
        jdbc.sql(
                """
                insert into account_balance (account_id, balance_pence, last_entry_seq)
                values (:accountId, :pence, 1)
                on conflict (account_id) do update
                set balance_pence = account_balance.balance_pence + excluded.balance_pence,
                    last_entry_seq = account_balance.last_entry_seq + 1
                returning balance_pence, last_entry_seq
                """)
            .param("accountId", account.value())
            .param("pence", balanceChange.pence())
            .query((rs, rowNum) ->
                new BalanceRow(rs.getLong("balance_pence"), rs.getLong("last_entry_seq")))
            .single();
    jdbc.sql(
            """
            insert into entry (
                transaction_id, account_id, side, amount_pence, booked_at,
                entry_seq, balance_after_pence)
            values (
                :transactionId, :accountId, :side, :amountPence, :bookedAt,
                :entrySeq, :balanceAfterPence)
            """)
        .param("transactionId", transactionId)
        .param("accountId", account.value())
        .param("side", side.name())
        .param("amountPence", amount.pence())
        .param("bookedAt", bookedAt)
        .param("entrySeq", after.lastEntrySeq())
        .param("balanceAfterPence", after.balancePence())
        .update();
  }

  private record BalanceRow(long balancePence, long lastEntrySeq) {}
}
