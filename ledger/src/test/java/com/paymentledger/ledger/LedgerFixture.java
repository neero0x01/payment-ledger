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
import java.util.UUID;
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

  /** Opens a Customer Account for a new Customer, funded from Funding by a real Transaction. */
  public AccountId customerAccountWithBalance(Money balance) {
    CustomerId customer = accounts.registerCustomer("Test Customer");
    AccountId account = accounts.openCustomerAccount(customer, "Main");
    if (balance.isPositive()) {
      posting.post(
          NewTransaction.funding(
              List.of(
                  new Entry(accounts.funding(), Side.DEBIT, balance),
                  new Entry(account, Side.CREDIT, balance))));
    }
    return account;
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
      insertBackdatedEntry(id, accounts.funding(), Side.DEBIT, amount, bookedAt);
      insertBackdatedEntry(id, account, Side.CREDIT, amount, bookedAt);
      return new TransactionId(id);
    });
  }

  private void insertBackdatedEntry(
      UUID transactionId, AccountId account, Side side, Money amount, OffsetDateTime bookedAt) {
    jdbc.sql(
            """
            insert into entry (transaction_id, account_id, side, amount_pence, booked_at)
            values (:transactionId, :accountId, :side, :amountPence, :bookedAt)
            """)
        .param("transactionId", transactionId)
        .param("accountId", account.value())
        .param("side", side.name())
        .param("amountPence", amount.pence())
        .param("bookedAt", bookedAt)
        .update();
    // Funding and Customer Accounts both move up on these sides of their Normal Side.
    jdbc.sql(
            """
            insert into account_balance (account_id, balance_pence)
            values (:accountId, :pence)
            on conflict (account_id) do update
            set balance_pence = account_balance.balance_pence + excluded.balance_pence
            """)
        .param("accountId", account.value())
        .param("pence", amount.pence())
        .update();
  }
}
