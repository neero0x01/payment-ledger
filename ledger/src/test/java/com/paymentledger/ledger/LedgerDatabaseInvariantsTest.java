package com.paymentledger.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own guard on the ledger's invariants, independent of the domain model. These tests
 * write SQL as the application does, because the guard sits below every code path.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, LedgerFixture.class})
class LedgerDatabaseInvariantsTest {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired LedgerFixture ledger;

  AccountId funding;
  AccountId customer;

  @BeforeEach
  void givenAFundingAndACustomerAccount() {
    funding = ledger.funding();
    customer = ledger.customerAccountWithBalance(Money.ofPence(0));
  }

  @Test
  void a_transaction_that_balances_by_commit_time_is_accepted() {
    assertThatCode(() ->
            transaction.executeWithoutResult(status -> {
              UUID id = insertFundingTransaction();
              insertEntry(id, funding, "DEBIT", 10_00);
              insertEntry(id, customer, "CREDIT", 10_00);
            }))
        .doesNotThrowAnyException();
  }

  @Test
  void commit_is_rejected_when_a_transaction_does_not_balance() {
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              UUID id = insertFundingTransaction();
              insertEntry(id, funding, "DEBIT", 10_00);
              insertEntry(id, customer, "CREDIT", 9_99);
            }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void commit_is_rejected_when_a_transaction_has_fewer_than_two_entries() {
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(
                status -> insertEntry(insertFundingTransaction(), funding, "DEBIT", 10_00)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void commit_is_rejected_when_an_entry_unbalances_an_earlier_transaction() {
    UUID id =
        transaction.execute(status -> {
          UUID posted = insertFundingTransaction();
          insertEntry(posted, funding, "DEBIT", 10_00);
          insertEntry(posted, customer, "CREDIT", 10_00);
          return posted;
        });

    assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> insertEntry(id, funding, "DEBIT", 1)))
        .isInstanceOf(DataAccessException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "update entry set amount_pence = amount_pence + 1",
        "delete from entry",
        "truncate entry",
        "update ledger_transaction set booked_at = clock_timestamp()",
        "delete from ledger_transaction",
        "truncate ledger_transaction cascade"
      })
  void the_application_can_never_change_entries_or_transactions(String statement) {
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> jdbc.sql(statement).update()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void a_table_added_by_a_later_migration_is_append_only_for_the_application_by_default() {
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.sql("reset role").update();
              jdbc.sql("create table rf_later_table (id bigint)").update();
              jdbc.sql("set role ledger_app").update();
              jdbc.sql("update rf_later_table set id = id + 1").update();
            }))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  private UUID insertFundingTransaction() {
    return jdbc.sql(
            """
            insert into ledger_transaction (kind, booked_at)
            values ('FUNDING', clock_timestamp())
            returning id
            """)
        .query(UUID.class)
        .single();
  }

  private void insertEntry(UUID transactionId, AccountId account, String side, long pence) {
    jdbc.sql(
            """
            insert into entry (transaction_id, account_id, side, amount_pence, booked_at)
            values (:transactionId, :accountId, :side, :pence, clock_timestamp())
            """)
        .param("transactionId", transactionId)
        .param("accountId", account.value())
        .param("side", side)
        .param("pence", pence)
        .update();
  }
}
