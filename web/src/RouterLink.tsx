import { Link as MuiLink } from "@mui/material";
import { createLink } from "@tanstack/react-router";

/** An MUI link that navigates through the router, with typed `to`, params and search. */
export const RouterLink = createLink(MuiLink);
