# qits-agents-daemon

The process inside a project's **long-running agent** container (qits-2519), published as the
`qits/agent` image. One container holds one agent (`developer` of project `qits`, say). The daemon:

- puts the project's wrapper checkout on the kept work volume and keeps it on every commit the
  platform takes;
- pushes the agent's branches for it;
- keeps one Claude session alive, fed with the agent's turns and reported back.

It talks to qits-agents-service **only through its REST doors** (qits-2518), with the agent's own
token. There is no socket and no frame codec.

## Modules

| Module | Artifact | Holds |
| --- | --- | --- |
| `agents-daemon-protocol` | `eu.wohlben.qits:qits-agents-daemon-protocol` | `AgentImage.VERSION`, filtered from the release version. qits-agents-service pins this jar; its version is the `qits/agent` tag. |
| `agents-daemon` | `eu.wohlben.qits:qits-agents-daemon` | The Quarkus command-mode app (native in the image). |

`docker/Dockerfile` builds the image on `qits/workspace-base` (git, JDK, node, claude, the qits CLI,
`qits-browser-mcp`, `/usr/local/bin/qits-git-credential`).

## Environment

What `AgentSpecs` in qits-agents-service gives the container. The project id is read from `launch`.

| Name | Value |
| --- | --- |
| `QITS_AGENTS_DAEMON_PROJECT` | project slug, `qits` |
| `QITS_AGENTS_DAEMON_AGENT_NAME` | e.g. `developer` |
| `QITS_AGENTS_DAEMON_WRAPPER` | the wrapper repository, `qits-qits` |
| `QITS_AGENTS_DAEMON_GIT_BASE` | `https://githost.qits.<domain>/git/<projectSlug>` |
| `QITS_AGENTS_DAEMON_SERVICE_URL` | `https://agents.qits.<domain>/agents` |
| `QITS_TOKEN` | the agent's own commissioned token |
| `QITS_DOMAIN` | the platform domain |
| `GIT_CONFIG_GLOBAL=/etc/qits-gitconfig`, `QITS_GIT_AUTH_*` | so git authenticates with `QITS_TOKEN` |

Optional, derived from `QITS_DOMAIN` when unset: `QITS_AGENTS_DAEMON_EVENTS_URL`
(`https://events.qits.<domain>`), `QITS_AGENTS_DAEMON_PROJECTS_URL`
(`https://projects.qits.<domain>`), `QITS_PLATFORM_MCP_URL` (`https://mcp.qits.<domain>/mcp`).
`QITS_AGENTS_DAEMON_WORKSPACE` (`/workspace`) and `QITS_AGENTS_DAEMON_CLAUDE_MOUNT` (`/claude-home`)
exist for tests and hand runs. A container missing a required name logs `Not starting: the
container's environment lacks [...]` and idles.

## Behaviour

**Provisioner.** If `/workspace/<wrapper>/.git` is absent: `git clone <gitBase>/<wrapper>`, every
submodule url rewritten to its sibling `<gitBase>/<name>` and initialised, then each submodule
`git switch -q main` and `git merge --ff-only origin/main`. The volume is kept across restarts, so
later starts only catch up. A failed clone is retried every 30 s.

**MainFollower.** On `SCMPublishCommit` of this project for repository `R` (the wrapper or one of its
submodules; `repoId` is resolved through `GET /projects/api/repositories/{id}` when the event has no
`repoName`): `git fetch --prune origin` in `R`, and on `main` a `git merge --ff-only origin/main`. A
wrapper move materialises newly declared submodules only — existing ones follow their own `main`,
never the gitlink. A 60 s backstop fetches and fast-forwards everything. It moves only a repository
on `main` with no tracked changes (`git status --porcelain --untracked-files=no`, plus
`--ignore-submodules=all` for the wrapper); anything else is skipped and named on the next report.
It never resets, stashes, cleans or forces.

**OriginPush.** Every 10 s, in the wrapper, every submodule and every repository a worktree of
theirs holds: each local branch other than `main` whose tip origin lacks is pushed, `git push
origin <branch>:<branch>`, never `--force`. A refusal is not retried until the branch tip changes.
Detached HEADs are never pushed.

**AgentKeeper.** One interactive, `REPOSITORY`-scoped session of surface `project.agent` in
`/workspace/<wrapper>`, with MCP `qits` (the agent's bearer) and `browser`, the launch's model and
effort, and an appended system prompt of the overseer brief followed by the launch's `brief`.

- `launch` is read at start, every 30 s and on every `ProjectAgentChanged` for this agent.
- A crash relaunches on a ladder (5 s doubling to 5 min, reset after 10 min up, never giving up),
  resuming `launch.sessionId` (else the last session seen) only when the **session ledger** places
  it at the launch's generation and its transcript is on `/claude-home`. The ledger,
  `/workspace/.qits-agents-daemon/sessions` on the kept work volume, records `<generation>
  <sessionId>` for every session the daemon launched for a generation, so a new container never
  resumes the previous generation's session that `launch.sessionId` may still name.
- A newer generation ends the session and launches a fresh one; RECOVERY is its first turn.
- Every fresh session past generation 1 begins with its generation's recovery prompt: the pending
  RECOVERY turn of that generation, else the row's `recoveryPrompt` (`GET .../agents/{name}`) when
  the RECOVERY turn was already acknowledged. Typed once per session.
- Turns (read every 10 s and after a launch) are typed one at a time at the session's idle, RECOVERY
  first then by `createdAt`. A typed turn is acknowledged with `delivered` only once the session
  took it (its `UserPromptSubmit` hook, BUSY, for that command) at the running generation; nothing
  else is typed meanwhile, and a session that ends first leaves the turn pending for the next one.
- Reports: STARTING at launch, IDLE/BUSY from the harness hooks (loopback webhook on 127.0.0.1:13337),
  EXITED or FAILED on exit, always with the running generation and a session id the ledger does not
  place at another generation.
- On container stop the keeper stops first: nothing is typed, acknowledged, reported or launched
  while the sessions are terminated.
- `launch` answering 409 (RETIRED) stops the session; the daemon idles and keeps reading `launch`.

**OverseerBrief.** The platform-owned half of the system prompt: the checkout follows every commit
and is read-only; each item gets worktrees under `/workspace/worktrees/<qualified id>/`; a subagent
does the work; pushes happen automatically; worktrees are removed through the repository that made
them.

**Events.** One SSE connection to `https://events.qits.<domain>/events/api/stream?names=
SCMPublishCommit,ProjectAgentChanged`, reconnecting with backoff (1 s doubling to 30 s), dropped
after 60 s of silence, and followed by a full catch-up (backstop + `launch`) after every connect.

## Log lines

| Line | Meaning |
| --- | --- |
| `INFO MainFollower moved <R> <old7>..<new7> (<event\|timer>)` | `R`'s main moved. The live check reads this. |
| `INFO MainFollower materialised <path>` | a newly declared submodule was cloned onto its main |
| `WARN MainFollower skipped <R>: <paths>` | not moved: tracked changes, off `main`, or diverged (once per cause) |
| `INFO OriginPush pushed <R> <branch> <sha7>` | a branch reached origin |
| `WARN OriginPush refused <R> <branch>: <first line>` | the host said no; retried only on a new tip |
| `INFO AgentKeeper launched <command> at generation <n> (fresh\|resuming <id>)` | a session started |
| `INFO AgentKeeper typed <KIND> turn <id> into <command>` | a turn was typed into the session |
| `INFO AgentKeeper typed the generation <n> recovery prompt into <command> (from the agent's row)` | a fresh session's recovery prompt, its RECOVERY turn already acknowledged |
| `INFO AgentKeeper: <command> took the generation <n> recovery prompt` | the session submitted it |
| `INFO AgentKeeper acknowledged <KIND> turn <id>` | `delivered` landed, after the session took the turn |
| `INFO AgentKeeper: <what> typed into <command> was not taken; it stays pending` | the session ended first |
| `INFO AgentKeeper: not resuming <id> at generation <n>: it belongs to <generation m\|no generation this daemon recorded>` | `launch.sessionId` is not this generation's |
| `INFO SessionLedger recorded <id> at generation <n>` | a session placed at its generation |
| `INFO AgentKeeper stopping: no turn is acknowledged from now on` | the container is stopping |
| `INFO AgentKeeper: session <command> exited (exit <n>; relaunching in <s> s)` | a crash, on the ladder |
| `INFO AgentKeeper: the agent is retired (...)` | `launch` answered 409 |
| `INFO EventFollower connected to <url>` | the event stream is up |

## Contracts

Consumer pacts, flat in `pacts/`: `qits-agents-daemon_qits-agents-service.json` (the four doors, in
the provider state `a running agent calling as itself`, and `getAgent` in `a running agent`) and
`qits-agents-daemon_qits-projects-service.json` (`getRepository`). The daemon serves no HTTP and
records no golden masters.
