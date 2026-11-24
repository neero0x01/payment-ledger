import type { Payment, PaymentRequest } from "./ledgerApi";
import { parsePounds } from "./money";

/** What the Customer has typed into the New Payment form. */
export type NewPaymentForm = {
  type: Payment["type"];
  from: string;
  to: string;
  sortCode: string;
  accountNumber: string;
  name: string;
  amount: string;
};

export const EMPTY_FORM: NewPaymentForm = {
  type: "INTERNAL_TRANSFER",
  from: "",
  to: "",
  sortCode: "",
  accountNumber: "",
  name: "",
  amount: "",
};

/** The ledger refuses a Payment of more than £1,000,000.00. */
const MAX_AMOUNT_PENCE = 1_000_000_00;

export type FormProblems = Partial<Record<keyof NewPaymentForm, string>>;

/** The form field for each Payment request field the ledger may name in a problem. */
const FORM_FIELD: Record<string, keyof NewPaymentForm> = {
  debtorAccountId: "from",
  "payee.accountId": "to",
  "payee.sortCode": "sortCode",
  "payee.accountNumber": "accountNumber",
  "payee.name": "name",
  amountPence: "amount",
};

/**
 * The ledger's problem placed beside the form field it names, or nothing when it names no field
 * of this form.
 */
export function problemsFromLedger(
  field: string | undefined,
  detail: string,
): FormProblems {
  const formField = field === undefined ? undefined : FORM_FIELD[field];
  return formField ? { [formField]: detail } : {};
}

/**
 * Checks the form the way the ledger will, so the Customer sees each problem beside its field
 * before anything is sent. Gives the Payment request when there are none.
 */
export function validate(
  form: NewPaymentForm,
):
  | { request: PaymentRequest; problems?: undefined }
  | { request?: undefined; problems: FormProblems } {
  const problems: FormProblems = {};
  if (!form.from) problems.from = "Choose the Account to pay from";

  const sortCode = form.sortCode.replace(/[\s-]/g, "");
  const accountNumber = form.accountNumber.trim();
  const name = form.name.trim();
  if (form.type === "INTERNAL_TRANSFER") {
    if (!form.to) problems.to = "Choose the Account to pay";
    else if (form.to === form.from) {
      problems.to = "Choose an Account other than the one paying";
    }
  } else {
    if (!/^\d{6}$/.test(sortCode)) {
      problems.sortCode = "Enter the six-digit sort code";
    }
    if (!/^\d{8}$/.test(accountNumber)) {
      problems.accountNumber = "Enter the eight-digit account number";
    }
    if (!name) problems.name = "Enter the Payee's name";
  }

  const amountPence = parsePounds(form.amount);
  if (amountPence === undefined) {
    problems.amount = "Enter an amount in pounds, such as 30.00";
  } else if (amountPence === 0) {
    problems.amount = "Enter an amount more than £0.00";
  } else if (amountPence > MAX_AMOUNT_PENCE) {
    problems.amount = "Enter an amount of at most £1,000,000.00";
  }

  if (Object.keys(problems).length > 0 || amountPence === undefined) {
    return { problems };
  }
  return {
    request: {
      debtorAccountId: form.from,
      payee:
        form.type === "INTERNAL_TRANSFER"
          ? { accountId: form.to }
          : { sortCode, accountNumber, name },
      amountPence,
    },
  };
}
