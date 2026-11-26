package com.paymentledger.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The requests the simulations make, each as a load-test Customer whose session the feeder hands
 * the virtual user. The request names are what the Gatling report and its assertions group by.
 */
final class LedgerRequests {

  /** The fintech's own sort code (00-01-00): a Faster Payment never pays it. */
  private static final String FINTECH_SORT_CODE = "000100";

  /** The simulator always rejects a Payee account number ending in this with AC04. */
  private static final String ALWAYS_REJECTED_SUFFIX = "0401";

  private LedgerRequests() {}

  /**
   * One Payment's worth of data per virtual user: who pays, from which session, to which load-test
   * Account or external Payee, how much, and its Idempotency-Key.
   */
  static Iterator<Map<String, Object>> feeder(List<LoadTestCustomers.LoggedIn> customers) {
    return Stream.generate(() -> nextPayment(customers)).iterator();
  }

  /** Swaps in, as the debtor, an Account held below the maximum Payment. */
  private static Iterator<Map<String, Object>> belowMaximumDebtors(
      List<LoadTestCustomers.LoggedIn> belowMaximum) {
    return Stream.generate(
            () -> {
              LoadTestCustomers.LoggedIn customer =
                  belowMaximum.get(ThreadLocalRandom.current().nextInt(belowMaximum.size()));
              Map<String, Object> debtor = new HashMap<>();
              debtor.put("debtorAccountId", customer.accountId());
              debtor.put("sessionCookie", customer.sessionCookie());
              debtor.put("xsrfToken", customer.xsrfToken());
              return debtor;
            })
        .iterator();
  }

  private static Map<String, Object> nextPayment(List<LoadTestCustomers.LoggedIn> customers) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    int debtor = random.nextInt(customers.size());
    // Any other load-test Account: an Internal Transfer to the debtor's own Account is not one
    // the load test makes.
    int payee = (debtor + 1 + random.nextInt(customers.size() - 1)) % customers.size();
    LoadTestCustomers.LoggedIn customer = customers.get(debtor);
    Map<String, Object> payment = new HashMap<>();
    payment.put("debtorAccountId", customer.accountId());
    payment.put("sessionCookie", customer.sessionCookie());
    payment.put("xsrfToken", customer.xsrfToken());
    payment.put("payeeAccountId", customers.get(payee).accountId());
    payment.put("amountPence", random.nextLong(1, 10_000));
    payment.put("sortCode", fictionalSortCode(random));
    payment.put("accountNumber", acceptedAccountNumber(random));
    payment.put("idempotencyKey", UUID.randomUUID().toString());
    return payment;
  }

  /** A sort code in 00-00-00 to 00-99-99, the range this project treats as fictional. */
  private static String fictionalSortCode(ThreadLocalRandom random) {
    String sortCode;
    do {
      sortCode = "00%04d".formatted(random.nextInt(10_000));
    } while (sortCode.equals(FINTECH_SORT_CODE));
    return sortCode;
  }

  private static String acceptedAccountNumber(ThreadLocalRandom random) {
    String accountNumber;
    do {
      accountNumber = "%08d".formatted(random.nextInt(100_000_000));
    } while (accountNumber.endsWith(ALWAYS_REJECTED_SUFFIX));
    return accountNumber;
  }

  /** Completes at once: both Accounts are at the fintech. */
  static final ChainBuilder INTERNAL_TRANSFER =
      exec(
          postPayment("Internal Transfer", internalTransferBody("#{amountPence}"))
              .check(status().is(201))
              .check(jsonPath("$.status").is("COMPLETED")));

  /** Pending until the simulator's Scheme Outcome arrives, after the response. */
  static final ChainBuilder FASTER_PAYMENT =
      exec(
          postPayment("Faster Payment", fasterPaymentBody())
              .check(status().is(201))
              .check(jsonPath("$.status").is("PENDING")));

  /** The same Payment sent twice with one Idempotency-Key: the second is a replay of the first. */
  static final ChainBuilder RETRY =
      exec(postPayment("Retry: first send", internalTransferBody("#{amountPence}"))
              .check(status().is(201))
              .check(jsonPath("$.id").saveAs("paymentId")))
          .exec(
              postPayment("Retry: replay", internalTransferBody("#{amountPence}"))
                  .check(status().is(200))
                  .check(header("Idempotent-Replayed").is("true"))
                  .check(jsonPath("$.id").isEL("#{paymentId}")));

  /**
   * A Rejected Payment, stored with its Rejection Reason: a business outcome, not an error. It pays
   * the most one Payment may be from an Account held below that (LoadTestCustomers).
   */
  static ChainBuilder insufficientFunds(List<LoadTestCustomers.LoggedIn> belowMaximum) {
    return feed(belowMaximumDebtors(belowMaximum))
        .exec(
            postPayment(
                    "Insufficient Funds",
                    internalTransferBody(String.valueOf(LoadTestCustomers.MAXIMUM_PAYMENT_PENCE)))
                .check(status().is(201))
                .check(jsonPath("$.status").is("REJECTED"))
                .check(jsonPath("$.rejectionReason").is("INSUFFICIENT_FUNDS")));
  }

  /** The authoritative Balance from the write side, then the Statement from the Read Model. */
  static final ChainBuilder READS =
      exec(withSession(http("Balance").get("/v1/accounts/#{debtorAccountId}/balance"))
              .check(status().is(200)))
          .exec(
              withSession(http("Statement").get("/v1/accounts/#{debtorAccountId}/statement"))
                  .check(status().is(200)));

  private static HttpRequestActionBuilder postPayment(String name, String body) {
    return withSession(http(name).post("/v1/payments"))
        .header("Idempotency-Key", "#{idempotencyKey}")
        .header("Content-Type", "application/json")
        .body(StringBody(body));
  }

  private static HttpRequestActionBuilder withSession(HttpRequestActionBuilder request) {
    return request
        .header("Cookie", "JSESSIONID=#{sessionCookie}; XSRF-TOKEN=#{xsrfToken}")
        .header("X-XSRF-TOKEN", "#{xsrfToken}");
  }

  private static String internalTransferBody(String amountPence) {
    return """
    {"debtorAccountId": "#{debtorAccountId}",
     "payee": {"accountId": "#{payeeAccountId}"},
     "amountPence": %s}\
    """
        .formatted(amountPence);
  }

  private static String fasterPaymentBody() {
    return """
    {"debtorAccountId": "#{debtorAccountId}",
     "payee": {"sortCode": "#{sortCode}", "accountNumber": "#{accountNumber}",
               "name": "Load Test Payee"},
     "amountPence": #{amountPence}}\
    """;
  }
}
