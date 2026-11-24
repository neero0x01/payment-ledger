package com.paymentledger.fpssimulator;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Scheme Submission as the ledger sends it: a slimmed-down ISO 20022 pacs.008 with the End-to-End
 * Id, the debtor's account, the creditor at another bank and the amount in pence.
 */
record Pacs008Lite(
    @Nullable String endToEndId,
    @Nullable UUID debtorAccountId,
    @Nullable String creditorSortCode,
    @Nullable String creditorAccountNumber,
    @Nullable String creditorName,
    long amountPence) {}
