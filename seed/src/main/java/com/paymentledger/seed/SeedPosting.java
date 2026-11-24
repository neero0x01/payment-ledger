package com.paymentledger.seed;

import com.paymentledger.ledger.accounts.Account;
import com.paymentledger.ledger.posting.Entry;
import com.paymentledger.ledger.posting.NewTransaction;
import com.paymentledger.ledger.posting.PostedEntry;
import com.paymentledger.ledger.posting.PostedTransaction;
import com.paymentledger.ledger.posting.TransactionSource;
import com.paymentledger.ledger.shared.AccountId;
import com.paymentledger.ledger.shared.Money;
import com.paymentledger.ledger.shared.TransactionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts seeded Transactions in memory the way the ledger posts them (ADR 0004): each Entry hands
 * out its Account's next entry_seq and records the Balance just after it, and the funds check
 * compares an amount with the Available Balance. Transactions must be posted in booking order, so
 * entry_seq follows Booking Time.
 */
final class SeedPosting {

  private final Map<AccountId, Book> books = new LinkedHashMap<>();
  private final SeedSink sink;
  private final SeedRandom random;

  SeedPosting(SeedSink sink, SeedRandom random) {
    this.sink = sink;
    this.random = random;
  }

  /** Lets Transactions post to the Account. */
  void open(Account account) {
    books.put(account.id(), new Book(account));
  }

  /** Whether the funds check lets the Account pay out the amount now. */
  boolean canPayOut(AccountId account, Money amount) {
    Book book = bookOf(account);
    return book.account.canPayOut(amount, Money.ofPence(book.balance));
  }

  /** The Account's Balance on its Normal Side now. */
  Money balanceOf(AccountId account) {
    return Money.ofPence(bookOf(account).balance);
  }

  /** Posts the Transaction, booked at the moment, and hands it to the sink. */
  PostedTransaction post(NewTransaction transaction, Instant bookingTime) {
    List<PostedEntry> posted = new ArrayList<>(transaction.entries().size());
    for (Entry entry : transaction.entries()) {
      Book book = bookOf(entry.account());
      book.balance =
          Math.addExact(book.balance, entry.balanceChange(book.account.normalSide()).pence());
      book.lastEntrySeq++;
      posted.add(new PostedEntry(entry, book.lastEntrySeq, Money.ofPence(book.balance)));
    }
    PostedTransaction postedTransaction =
        new PostedTransaction(
            new TransactionId(random.uuidV7(bookingTime)),
            transaction.kind(),
            bookingTime,
            transaction.payment().orElse(null),
            transaction.reverses().orElse(null),
            transaction.correction().orElse(null),
            TransactionSource.SEED,
            posted);
    sink.transaction(postedTransaction, random.uuidV4());
    return postedTransaction;
  }

  /** Hands the sink the balance row of every Account with an Entry, in the order they opened. */
  void finish() {
    books.forEach((id, book) -> {
      if (book.lastEntrySeq > 0) {
        sink.balance(new SeededBalance(id, Money.ofPence(book.balance), book.lastEntrySeq));
      }
    });
  }

  private Book bookOf(AccountId account) {
    Book book = books.get(account);
    if (book == null) {
      throw new IllegalArgumentException("Unknown Account " + account);
    }
    return book;
  }

  /** An Account's Balance and the last entry_seq handed out, as its balance row holds them. */
  private static final class Book {

    private final Account account;
    private long balance;
    private long lastEntrySeq;

    private Book(Account account) {
      this.account = account;
    }
  }
}
