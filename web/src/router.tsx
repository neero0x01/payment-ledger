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
import { AccountsScreen } from "./AccountsScreen";
import { App } from "./App";
import { theme } from "./theme";
import { TransferPage } from "./TransferPage";

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
  component: AccountDetailScreen,
});

const transferRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/transfer",
  component: TransferPage,
});

const routeTree = rootRoute.addChildren([
  indexRoute,
  accountsRoute,
  accountRoute,
  transferRoute,
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
