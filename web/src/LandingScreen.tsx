import {
  Alert,
  Box,
  Button,
  Container,
  Divider,
  Link,
  Stack,
  Typography,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type Session, startDemoSession } from "./ledgerApi";
import { LoginScreen } from "./LoginScreen";

/**
 * What a visitor sees before logging in: the claim, how the system is built, and one click to try
 * it as a Customer with their own Demo Sandbox or as the demo Operator.
 */
export function LandingScreen() {
  const queryClient = useQueryClient();
  const demo = useMutation({
    mutationFn: (role: Session["role"]) => startDemoSession(role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["session"] }),
  });

  return (
    <Container component="main" maxWidth="md" sx={{ py: 6 }}>
      <Stack spacing={4}>
        <Stack spacing={2}>
          <Typography variant="overline" component="p">
            Payment Ledger
          </Typography>
          <Typography variant="h4" component="h1">
            A double-entry payment ledger that never double-charges, never loses
            an event, never applies one twice and can prove every Balance.
          </Typography>
          <Typography color="text.secondary">
            Java and Spring Boot, a transactional outbox to Kafka, CQRS with a
            separate projector, and Postgres, behind a React front end. Try it
            with your own funded Demo Sandbox: no sign-up needed.
          </Typography>
        </Stack>

        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <Button
            variant="contained"
            size="large"
            disabled={demo.isPending}
            onClick={() => demo.mutate("CUSTOMER")}
          >
            Try as customer
          </Button>
          <Button
            variant="outlined"
            size="large"
            disabled={demo.isPending}
            onClick={() => demo.mutate("OPERATOR")}
          >
            Try as operator
          </Button>
          <Button size="large" component={Link} href="/docs">
            API docs
          </Button>
        </Stack>
        {demo.isError && (
          <Alert severity="warning">
            The demo couldn't start: {demo.error.message}
          </Alert>
        )}

        <Box>
          <Typography variant="h6" component="h2" gutterBottom>
            How it's built
          </Typography>
          <ArchitectureDiagram />
        </Box>

        <Divider />
      </Stack>
      <LoginScreen />
    </Container>
  );
}

type Node = { x: number; y: number; w: number; h: number; lines: string[] };

const NODES: Node[] = [
  { x: 10, y: 120, w: 130, h: 60, lines: ["Web app", "React + MUI"] },
  {
    x: 200,
    y: 90,
    w: 200,
    h: 120,
    lines: [
      "Ledger",
      "modular monolith",
      "accounts · posting · payments",
      "outbox · views",
    ],
  },
  { x: 460, y: 15, w: 150, h: 50, lines: ["FPS simulator", "Faster Payments"] },
  { x: 460, y: 110, w: 150, h: 50, lines: ["Postgres", "ledger + outbox"] },
  { x: 460, y: 205, w: 150, h: 50, lines: ["Kafka", "events"] },
  { x: 640, y: 205, w: 110, h: 50, lines: ["Projector"] },
  { x: 640, y: 110, w: 110, h: 50, lines: ["Read Model", "Postgres"] },
];

const ARROWS = [
  // The page calls the ledger's REST API.
  "M140,150 H198",
  // Payments go to the scheme over HTTP; outcomes come back as signed callbacks.
  "M300,90 V40 H458",
  // Each posting commits Entries and outbox rows in one database transaction.
  "M400,135 H458",
  // The polling publisher sends outbox rows to Kafka.
  "M300,210 V230 H458",
  "M610,230 H638",
  "M695,205 V162",
  // The views module reads the Read Model.
  "M695,110 V90 H402",
];

/** The ledger, its two extracted services, and how events flow from the outbox to the Read Model. */
function ArchitectureDiagram() {
  const theme = useTheme();
  const stroke = theme.palette.text.secondary;
  return (
    <Box
      component="svg"
      role="img"
      aria-label="Architecture diagram: the web app calls the ledger, which posts to Postgres with an outbox, sends Faster Payments to the FPS simulator, and publishes events through Kafka to the projector, which builds the Read Model the ledger reads"
      viewBox="0 0 760 265"
      sx={{ width: "100%", height: "auto", display: "block" }}
    >
      <defs>
        <marker
          id="arrow"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerWidth="7"
          markerHeight="7"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill={stroke} />
        </marker>
      </defs>
      {ARROWS.map((d) => (
        <path
          key={d}
          d={d}
          fill="none"
          stroke={stroke}
          strokeWidth={1.5}
          markerEnd="url(#arrow)"
        />
      ))}
      {NODES.map((node) => (
        <g key={node.lines[0]}>
          <rect
            x={node.x}
            y={node.y}
            width={node.w}
            height={node.h}
            rx={8}
            fill={theme.palette.background.paper}
            stroke={theme.palette.primary.main}
            strokeWidth={1.5}
          />
          {node.lines.map((line, i) => (
            <text
              key={line}
              x={node.x + node.w / 2}
              y={
                node.y + node.h / 2 + (i - (node.lines.length - 1) / 2) * 16 + 4
              }
              textAnchor="middle"
              fontSize={i === 0 ? 14 : 11}
              fontWeight={i === 0 ? 600 : 400}
              fill={
                i === 0
                  ? theme.palette.text.primary
                  : theme.palette.text.secondary
              }
              fontFamily={theme.typography.fontFamily}
            >
              {line}
            </text>
          ))}
        </g>
      ))}
    </Box>
  );
}
