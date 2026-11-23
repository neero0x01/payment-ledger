import { createMemoryHistory } from "@tanstack/react-router";
import { QueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { AppProviders, createAppRouter } from "./router";

/** Renders the whole app at a path, as a browser opening that URL would. */
export function renderApp(path = "/") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createAppRouter(
    createMemoryHistory({ initialEntries: [path] }),
  );
  render(<AppProviders queryClient={queryClient} router={router} />);
  return { router };
}

export async function logIn(user: UserEvent, password = "local-customer") {
  await user.type(await screen.findByLabelText("Username"), "local-customer");
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Log in" }));
}
