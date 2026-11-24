# qits-agents-daemon — working notes

Read `README.md` first: it is the design. This file is the conventions on top of it.

## What it is

The entrypoint of the `qits/agent` image: one long-running project agent per container. It keeps the
wrapper checkout on every commit, pushes the agent's branches, and keeps one Claude session alive
against qits-agents-service's REST doors.

## Modules

- `agents-daemon-protocol/` — `AgentImage.VERSION` and nothing else. Published; qits-agents-service
  pins it. Its resource is filtered: never write a literal version into it.
- `agents-daemon/` — the Quarkus command-mode app. `AgentDaemon` wires; `Provisioner`, `Checkout`,
  `MainFollower`, `OriginPush`, `AgentKeeper`, `EventFollower`, `HookWebhook` do the work.

## Build and test

    ./mvnw verify

That is the gate, and a clone alone runs it green: the git tests use real repositories in temp dirs
(`GitFixture`), the keeper is tested against fakes, the doors against pact mock servers. The one
external address is the platform Maven registry (`qits-maven`); its pins in the root pom are exact
and moved by qits-maintenance, never by hand. The image is built by CI (`docker/Dockerfile`).

Generated files compare by default and fail with a diff; `-Dgolden.update=true` rewrites them:

| File | Written by |
|---|---|
| `pacts/qits-agents-daemon_qits-agents-service.json` | `AgentsServicePactTest` |
| `pacts/qits-agents-daemon_qits-projects-service.json` | `ProjectsServicePactTest` |

Every outbound REST call is a row of one of those pacts. A new call is a new row first.

## The never-reset rule

The checkout under `/workspace/<wrapper>` is somebody's view of the estate, and dirt in it is
somebody's unfinished work. Nothing in this daemon runs `git reset`, `stash`, `checkout --`,
`clean`, `push --force` or any other forced or destructive git command — not to unstick a
fast-forward, not to retry a push. A repository that cannot move is skipped and named
(`WARN MainFollower skipped …` and the next report's `detail`); a refused push waits for a new tip.
Keep it that way.

## Conventions

- All git goes through `Git` (argv, never a shell; no prompt; one timeout).
- `AgentKeeper` runs on one thread; hooks hand over to it. Its seams (`AgentsDoors`, `Harness`,
  `Scheduler`, the clock) are what its test fakes.
- Never log `QITS_TOKEN`.
- Log lines named in the README are read by live checks: change them only with the README.
