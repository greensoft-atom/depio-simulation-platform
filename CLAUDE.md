# CLAUDE.md — background/

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

# Background project

How work is done in this folder. The repository's own [CLAUDE.md](../CLAUDE.md) applies
too (think first, simplest change, surgical edits, verifiable goals); this file adds the
standing instructions for the backend, its docs and its client.

## How to work

- **Keep going.** Work continuously through [docs/plan.md](docs/plan.md), "What to do
  next, in order", **strictly in that order**. Check the plan before proposing work; do not
  jump to a later or more interesting item.
- **Every unit of work goes the same way:**
  1. **Design first.** Write or update the design doc (`docs/detailed-design/`, a D-_n_ in
     the decision log when a choice is made) before any code.
  2. **Test first.** Write the test, watch it fail for the right reason, then implement.
  3. **Mutation check.** Break each new rule on purpose and confirm a test catches it. A
     surviving mutant means a missing test; add it.
  4. **Live drill** when the behaviour crosses processes: `client/headless-drill.sh` against
     the real stack from a release.
  5. **Measure** when the tick, the payload or the bandwidth could move: before and after,
     on the same machine, with `TickBenchmark`.
  6. **Docs stay current:** the design doc, `plan.md`, the defect register `docs/defects.md`
     (every defect found gets an ID), the glossary, requirement questions (Q-_n_).
  7. **Commit and push per unit**, with the word check below.
- **Senior rigour, honest reporting.** Report what was measured, what failed (with the
  output) and what was not verified. The dev VM is loaded, so latency numbers vary between
  runs; say so rather than overclaim.
- **Decisions that are the user's** (product, balance, scope, cost) are surfaced with a
  recommendation and recorded as an open question, not taken silently.
- Reports to the user are short: what changed, what was found, what is open, what is next.

## Standing rules

- **The owner's standing decisions, the machine and its traps** are in
  [docs/development/02-working-here.md](docs/development/02-working-here.md), the copy
  that travels with the repository (this file is not committed). Keep both current: a
  new standing rule goes in both, and anything important learned goes into a tracked
  document, not only into Claude's memory.
- **Balance is Claude's** (Q-48): item levels, gems, shop prices, rewards, each number
  with its reason; not asked.
- **No third-party payment provider or sign-in for now** (Q-52): payments simulated.
- **Put off by the owner (2026-10-05):** the Unity editor's check (Q-54) and an admin
  panel (Q-55). Not proposed until the owner brings them back.
- **Java 21 only, MySQL 8.4, and the release carries its own runtime, MySQL and nginx**
  (Q-56, plan item 80). **Production is RHEL 9.x** (glibc 2.34), this machine Ubuntu
  24.04: native code is built in a Rocky Linux 9 container, releases checked in UBI 9.
- **Naming.** The word excluded by the naming rule in [docs/README.md](docs/README.md#naming)
  is never used: not in prose, identifiers, file names, paths or commit messages. That
  section is the only place it is written. The glossary has the substitutes.
- **Namespace `backend`:** Java `com.backend.*`, installed under `/opt/backend`, OS user
  `backend`; C# `Backend.Client.Core` and `Backend.Client.Unity`.
- **Never edit an applied Flyway migration**; add the next one. V1 (schema) and V2 (seed) are
  the baseline the owner had squashed from V1–V34 before launch (2026-10-04, D-75); from V3 on
  schema changes are forward-only, expand then contract (06 §8).
- **No third-party Redis server, client library or tool**, tests included; the store is
  j-redis.
- **Certificate and domain are deferred by the user.** Do not raise them or work on them
  until the user brings them back.
- **Client scope:** the engine-free C# core, the headless driver, and since 2026-10-04 (Q-51,
  plan item 77) the Unity layer as scripts: logic in the core where it can be tested, the
  Unity scripts thin. There is no Unity here: say what was and was not compiled.
- **Ports 6381 and 8082 belong to other processes.** Drills use 6390 (j-redis), 6392 (its
  replica, `FAILOVER=1`), 8093 (platform), 8094 (gateway), 9011 (arena), 9195 (platform's
  metrics), 9196 (platform's admin API), 9197, 9198 and 9199 (the arena's, the gateway's and
  the worker's metrics), and MySQL servers of their own on 3307 (a primary), 3308 (its
  replica, `MYSQL_FAILOVER=1`) and 3309 (the backup drill's scratch server).
- Push to `main`; never rewrite pushed history. Temporary files go in the session scratchpad.

## Commands

```bash
# Backend: build and test everything (offline)
cd backend && export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH && /opt/maven/bin/mvn -o install

# Release, for drills
MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh

# Client core tests (look for "Total tests:")
cd client && DOTNET_NOLOGO=1 /opt/dotnet/dotnet test --logger "console;verbosity=normal"

# The Unity package: the core built into it, then its scripts compiled against stubs (nothing run)
client/unity-package.sh && client/unity-check.sh

# Live drill; scenarios: play resume coldresume badticket lifecycle lobby replaced badsession duel walkover decline party rffa coop banned removed phrase equip boost team tournament teammatch teamcup roundrobin social rename apply levels gems purchase pass skin milestone season achievements goals prediction guest stats domination tag maze sandbox notice
TMPDIR=<scratchpad> client/headless-drill.sh [scenario ...]
# Over TLS, where "untrusted" is a scenario too (without TLS=1 it fails: it needs a certificate)
TLS=1 TMPDIR=<scratchpad> client/headless-drill.sh play untrusted

# The backups' own drill: the stream, dump, copy off the site, weekly proof under writes, a restore to a
# moment and the refusals, on private MySQL servers (3307 primary, 3309 scratch) seeded from backend_dev
TMPDIR=<scratchpad> backend/scripts/backup-drill.sh

# Tick cost (from backend/), before and after a change that could move it
java -XX:+UseZGC -XX:+ZGenerational -jar tools/target/tools-0.1.0-SNAPSHOT-all.jar \
     [tanks] [shapes] [ticks] [mapSize] [startLevel] [mazeSeed]     # defaults: 150 1500 20000 22000 1 0

# The result rate by thread count (from backend/); only on a database named *_test, which it wipes
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.ApplyBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 400 1,2,4,8

# Retention's purge rate, the worker's own batches (from backend/); only on a database named *_test, which it wipes
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.PurgeBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 1

# A rating board's place, top to bottom (from backend/); only on a database named *_test, which it wipes
java -cp tools/target/tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.RankBenchmark \
     "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true" backend backend-dev-password 200000 5000 21
```

MySQL for tests and drills: `backend` / `backend-dev-password` on 127.0.0.1:3306, databases
`backend_dev` and `backend_test`.

**Committing.** The word check runs over the staged lines, the staged file names and the
message, and blocks the commit on any hit (`g[a]me` so this file passes its own check):

```bash
git add background/ && hits=$( { git diff --cached -- . ':(exclude)background/docs/README.md' | grep '^+' ; \
  git diff --cached --name-only ; cat "$MSG" ; } | grep -i -c "g[a]me" ); \
if [ "$hits" != "0" ]; then echo "WORD CHECK FAILED"; else \
  git -c user.name=dev -c user.email=redcarrot0803@gmail.com commit -q -F "$MSG" && git push -q origin main; fi
```

Messages end with the `Co-Authored-By:` line the session's attribution gives (it names the model in use).
