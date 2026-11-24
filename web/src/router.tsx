import { CssBaseline, ThemeProvider } from "@mui/material";
import { LocalizationProvider } from "@mui/x-date-pickers/LocalizationProvider";
import { AdapterDayjs } from "@mui/x-date-pickers/AdapterDayjs";
import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createRootRoute,
  createRoute,
  createRouter,
  type RouterHistory,
  redirect,
  RouterProvider,
} from "@tanstack/react-router";
import "dayjs/locale/en-gb";
import { AccountDetailScreen } from "./AccountDetailScreen";
import { validateAccountSearch } from "./accountSearch";
import { AccountsScreen } from "./AccountsScreen";
import { App } from "./App";
import { NewPaymentScreen } from "./NewPaymentScreen";
import { PaymentScreen } from "./PaymentScreen";
import { theme } from "./theme";
import { TransactionScreen } from "./TransactionScreen";

const rootRoute = createRootRoute({ component: App });

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/accounts" });
  },
});

const accountsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/accounts",
  component: AccountsScreen,
});

const accountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/accounts/$accountId",
  validateSearch: validateAccountSearch,
  component: AccountDetailScreen,
});

const transactionRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/transactions/$transactionId",
  component: TransactionScreen,
});

const paymentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/payments/$paymentId",
  component: PaymentScreen,
});

const newPaymentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/payments/new",
  component: NewPaymentScreen,
});

const routeTree = rootRoute.addChildren([
  indexRoute,
  accountsRoute,
  accountRoute,
  transactionRoute,
  paymentRoute,
  newPaymentRoute,
]);

export function createAppRouter(history?: RouterHistory) {
  return createRouter({ routeTree, history });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}

/** Everything the screens need around them: the theme, dates, server state and routes. */
export function AppProviders({
  queryClient,
  router,
}: {
  queryClient: QueryClient;
  router: ReturnType<typeof createAppRouter>;
}) {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <LocalizationProvider dateAdapter={AdapterDayjs} adapterLocale="en-gb">
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </LocalizationProvider>
    </ThemeProvider>
  );
}
