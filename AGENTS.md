# grug-for-minecraft: agent notes

Loaded into every request, so every line has a cost: a line stays only if leaving it out would cost
a fresh agent a run, a wrong assumption or a broken machine. Findings belong in issues and pull
requests; delete lines that stopped being true. Detail moved out of this file lives in #369.

## How work gets done

One orchestrator agent works the open issues, often while the maintainer is
away. The maintainer reads the merged pull requests afterwards, on their Changes tabs. Pull requests
are by members only (#617).

**The goal is progress per hour.** The orchestrator is judged by how much verified work reaches
`main` per hour and by the research that decides what that work is or unblocks it, not by how
completely it measures a single task. Research is progress when it changes a decision or opens work,
and waste when it only restates what is known; balancing the two is the orchestrator's call. The
runner is the scarce resource, so the default is to keep every shared job slot busy and a subagent
on the next issue while the current one waits on CI: an idle slot is the one real waste. Choose the
next issue by what it unblocks and how much of it a subagent can finish without waiting, not by
what is easiest to close. Spend the rigor where a mistake would break `main` or lower coverage, and past
that bar prefer merging and fixing forward over polishing. The evidence rules for a claimed CI
saving exist so that no saving is claimed falsely; they are not a reason to slow the queue, and the
measurement runs while the next task is under way.

Work is pipelined rather than strictly serial, so the runner is the bottleneck and not the
orchestrator's attention: an implementation subagent works alone on its area, a second may start on a
disjoint area while the first waits on CI, and research subagents run in parallel freely. A full
`build.yml` dispatch still waits for any run in flight, and pull request runs may overlap only while
the 20 shared job slots allow it. The orchestrator may change its own process without asking, unless
the change is drastic, such as a different model or servers the maintainer would have to rent: it
applies the optimization, records it here or in the handoff, and reports what it changed.

**Context and compaction.** The orchestrator compacts its own session, so long runs continue while
the maintainer is away, and it never relies on the harness's automatic compaction, which leaves
nothing on disk. At a task boundary, when the last assistant message's `tokens.input` plus
`tokens.cache.read` is over about 250,000, it first writes the persistent record: this file if a rule
changed, the handoff's live section, the log, and each issue or pull request body, enough that a
fresh session resumes with nothing lost. Then it compacts with `opencode api post
/api/session/$OPENCODE_SESSION_ID/compact --data '{}'` (the body is required, even empty; `opencode
api get /api/session/$OPENCODE_SESSION_ID/message` gives the counts, from the last assistant
message's `tokens.input` plus `tokens.cache.read`) and checks that the compaction message reached
status `completed`. Never compact in the middle of a task, with a worktree that is not merged, or before the
record is written. If the call fails, retry at the next boundary and carry on; do not wait for the
maintainer to run `/compact` unless the session cannot continue at all.

**The daily meta pass.** Once a day, at a boundary rather than on a clock (after the day's last
merge, while a long dispatch runs, or before the Sunday release), the orchestrator improves the
process itself, because its throughput comes from its rules, briefs and tooling and not only from
the work. It reads the day's record rather than recalling it: the merged pull requests and their
diffs, the issues opened and closed, the CI runs through `ci_timing.py` (runner minutes per merge,
job walls and queue time, failure class, reruns), the cache store, the subagent reports, this file's
own diff, and the log. It adds one row to `~/agent-workspaces/meta-metrics.md` (date, merges, runner
minutes and their split, failures by class, research items and what each decided) and compares it
with the previous rows, so the last pass's change is judged by whether it worked. Then it makes at
most two changes, each backed by that day's numbers: a line here, in a brief template, a script or a
workflow, with what it expects to improve so the next pass can check. Anything larger becomes an
issue with the `research` label. It spends one to three subagents on the self-hosted model for the
data, and a day with nothing actionable says so in the log and costs no more. A line that the record
shows never mattered is deleted, because it costs every request, and a process improvement that
costs a day is the failure this pass exists to prevent.

**Other repositories.** `grug-rs` is the VM under every loader and `grug-tests` is the language spec,
and a change there can make every run faster or settle a language question; any other repository, a
compiler such as Clang included, may be needed the same way. Before optimizing one of them, read the
long-term plan first: `grug-ir`'s README and its `planned` and `maybe planned` issues, and the same
labels in `grug`, `grug-tests` and `grug-rs`. That plan compiles grug to its IR and host functions
ahead of time to LLVM IR that is inlined at runtime, with CI holding grug within 5% of C, so a
micro-optimization it would delete is not worth a branch or a measurement however much it wins
today; a change that survives the plan, or a measurement that tells what the plan is worth, is. Say
in the pull request which plan item the change survives or preempts. The orchestrator keeps a
single branch called `grug-for-minecraft` in each such repository, or a fork of it when it cannot
push there, and piles commits on top of it so a run here can fetch the commit, with the
language-level tests a VM change needs on the same-named branch in `grug-tests`. That branch or fork is the only write allowed
outside this repository: it never opens a pull request there, because that asks the maintainer's
teammates for reviews they did not sign up for, and it never tags or releases. A pull request here
that measures such a change pins `grugRsRevision` (or the equivalent) to the branch commit, names the
branch or fork and the commit in its body, says that no upstream pull request exists, and is not
merged unattended: the maintainer decides whether that revision goes to `main`. The measurement
follows the evidence rules like any other CI saving.

- **Orchestrator.** Reads the issue, writes the subagent a self-contained brief, reviews the
  subagent's diff against the issue, watches CI, merges, and keeps its own context small. The brief
  holds the issue and its acceptance points, the rules of this file that apply, the exact steps,
  that Minecraft runs only in CI, and how to report. After merging it removes the task's worktree
  and branch: `git worktree remove --force <path>`, check `git cherry origin/main <branch>` (all
  lines start with `-` after a rebase merge), `git branch -D <branch>`, `git worktree prune`.
- **Subagent.** Implements one issue in its own worktree from a fresh `origin/main`: `git worktree
  add ~/agent-workspaces/grug-for-minecraft-<task> -b <branch> origin/main`, and works only there.
  It opens the pull request in the same sitting with `Closes #<n>` (a draft if unfinished; the
  workflows trigger only on `pull_request`, so a branch without one is never verified), watches CI
  and fixes it until it is green. It reports in at most about 15 lines (pull request link, head SHA,
  CI result, measurements, surprises) and never merges. It never closes, reopens or marks an issue
  not planned either: whether a task is finished or should be closed is the orchestrator's decision,
  and a subagent that finds no saving reports the numbers and its recommendation.
- **Merging.** The orchestrator merges by rebase (`gh pr merge <n> --rebase`) once it has read the
  diff against the issue and judged that the change is unlikely to break `main` or lower coverage.
  Read the issue, the pull request description and the whole diff, ask what the change exists for
  and check that exact case (a watcher has to work while a run is still going, not only on a
  finished one), and think of an empty, odd or failing input; a subagent's check of the easy case
  proves nothing about the real one. Velocity is what the CI exists for: a change that touches
  `core/`, `mod_api.json`, the runner or a loader's adapters waits for the pull request's full run,
  while a change confined to `mods/`, to documentation or to a script with its own tests may merge
  on the jobs that exercise it, or on `pre-commit` alone when the orchestrator will watch the
  push-to-`main` run and fix forward. Every push to `main` is one merge, so a failure is attributed
  to that pull request, and a temporarily red `main` is acceptable. Never wait for CodeRabbit: it
  is slow and hits its review limit, so the review that counts is yours. Nothing is pushed to
  `main`. A quick edit to markdown only (an `AGENTS.md` fix, say) may be merged without waiting for
  any CI, and neither its pull request run nor the push-to-`main` run after it needs checking.
  Before merging a base pull request that another one is stacked on, retarget the other to `main`,
  or `--delete-branch` closes it.
- **Nothing waits for the maintainer.** A question goes in the chat when they are present and in an
  issue (label `research`) when not; work carries on with the most conservative choice, and a pull
  request is never left blocked on an answer.
- **When it fails.** A task that fails twice: stop, file an issue with what was learned, move on.
  If `main` goes red after a merge, stop merging and fix forward or revert, through a pull request.
- **Never, unattended:** publish outside the repository (for example a container image), change the
  ruleset or any repository setting (required status checks are #638), or delete anything you did
  not create (the worktree and branch of a task you just merged excepted).
- **This file.** A factual inaccuracy in it is fixed directly: the orchestrator merges an
  `AGENTS.md`-only pull request without waiting for CI (above), and the fix is not left for the
  maintainer. A substantial change to it, such as which models an agent may spawn, is the
  maintainer's call.
- **Report for the maintainer, when they return:** the pull request links to ctrl-click, what
  merged, what is still open, the questions as issues, and images to look at in one directory.

## Where Minecraft runs

Minecraft never runs on the maintainer's machine: it is pointless there and has hard-locked the
laptop (about 15 GB of RAM). Every game run goes through CI. Light jobs stay local: Gradle builds,
`javap`, the JaCoCo CLI, the Python and script tests, `pre-commit`.

- A pull request runs 10 jobs of `build.yml`, 9 when it changes only other mods and other loaders:
  `.github/scripts/reference-needed.py` skips the `reference` job then, and its log says why. The
  jar, installed and smoke jobs and two steps of the core tests run only on push to `main` and
  `workflow_dispatch`, and the Windows boot jobs only on a dispatch and on a push that adds a loader
  (#632), so a green pull request says nothing about them. `gh workflow run
  build.yml --ref <branch>` gives a branch the full pipeline, with `-f repeats=<n>` to repeat the
  suite in one session.
- A release takes its jar from a dispatch of `build.yml` on main, not from the push that merged the
  commit: the Windows boot jobs run only on a dispatch and for a loader a push adds (#632), so
  `release.yml` requires that dispatch.
- A release is published automatically every Sunday at 12:00 CET (11:00 UTC) from `main`'s head: the
  scheduled workflow dispatches the full `build.yml`, waits for it, tags the commit and lets
  `release.yml` attach the jar. That dispatch is the one run that must be green; a failure there
  blocks the release and is fixed before the next one, never by weakening the gate.
- Build's concurrency cancels an older run of the same ref, except on `main`. To run several
  variants at once, push the same commit to throwaway branches and dispatch each; delete them when
  done. An experiment that changes only `.github/scripts/` keeps the caches warm. Editing
  `.github/actions/setup-loader-build/action.yml`, a loader's `gradle.properties` or a build file
  misses the exact cache keys; editing `build.yml` does not, the 1.2.5 MCP snapshot excepted until
  #783. The Gradle dependencies fall back to the loader's previous entry (`restore-keys`), but the
  Minecraft toolchain has no fallback, so a miss there fetches it again, about 60 s on
  `1.20.6-forge`. The plan allows 20 concurrent jobs, shared by
  all runs: do not dispatch a full `build.yml` while another run is going.
- A loader job restores the project's `build/` and `.gradle/`, so Gradle's up-to-date check decides
  what runs; the cache key says only where that state lives, never what is safe to reuse. A task
  reading an undeclared input is therefore a CI correctness bug, not only a local one, and moving
  outputs out of `build/` drops them from reuse, so the action's paths must be told.
- Before pushing, check locally what can be checked: `pre-commit run --all-files`, the grug compile
  check, the Python tests. Wait with `.github/scripts/watch-run.sh <run-id>` in the background,
  never a fixed sleep: it exits 1 at the first failed job with the last 80 lines of its failed step,
  0 when all are green, 3 when GitHub could not be reached (no verdict). Get the id from `gh api
  'repos/grug-lang/grug-for-minecraft/actions/runs?branch=<b>'`; `gh pr checks --watch` fails right
  after a push and reads stale runs after a force-push. Read a job's whole log with `gh api
  repos/grug-lang/grug-for-minecraft/actions/jobs/<id>/logs`. Pushing freely to check a pipeline is
  fine; a push that shows no CI run usually means a conflict (`gh pr view <n> --json mergeable`).
- `python3 .github/scripts/ci_timing.py <run-id>` shows where a run's time went, with queue time.
- Any time saving is welcome, but the smaller it is, the more firmly it has to be proven: a job
  varies by up to a minute between runs, so run the control and the variant interleaved on
  throwaway branches, as many times each as the saving and the spread need to show it significant
  (a few seconds takes far more runs than a large saving), record every sample, and report the
  means, the spread and a significance test. There is no fixed number of runs and no fixed number
  of seconds below which a saving is dropped; one that is not shown significant is not claimed.
- Do not idle while the push-to-`main` run of a merged pull request goes: start the next task at
  once and watch that run in the background, so it is checked while the next subagent works. Only a
  task that depends on the merged change waits for it, and after a merge that touches
  `.github/actions/setup-loader-build/action.yml` that run is slow (the action is in the cache keys
  and the Minecraft toolchain has no fallback, so it is fetched again). If it fails, attribute it to
  that merge and deal with it while the next task runs.

### A red main or a flake

- A red `main` is not a stop sign. It comes from one merge, so it is attributed to that pull
  request: rerun the failed jobs once to tell a flake from a real failure, then fix forward or
  revert, and do not pause the work in flight. Keeping `main` green still matters, because a red
  `main` blocks the next release and makes the next pull request's comparisons noisy, but only the
  Sunday release has to be green.
- When a job fails, read the titles of the open issues (`gh issue list --state open`) before
  anything else: it is often a known flake. A rerun that passes still gets the occurrence added to
  that issue's body.
- A flake the pull request did not cause (its failing job exercises code it does not touch, or the
  same failure shows on another pull request or `main`) is fixed first, in its own pull request with
  the test that fails without the fix. Then rebase the original onto `main` and merge once its CI
  passes on that commit; a rerun that goes green does not count.
- When `main` is red, find the pull request of the commit
  (`gh api repos/grug-lang/grug-for-minecraft/commits/<sha>/pulls`) and compare its last run with
  the one on `main`. A job that failed on `main` and was missing or skipped on the pull request is
  the expected case for an optimistic merge; one that failed on the pull request too was merged
  red.

## Running a loader

CI runs `.github/scripts/run-loader.sh <loader> build|build-run|run|update-goldens` under
`xvfb-run`; locally only `build` is light enough. `build-run` is `build` and `run` in one Gradle
invocation (the loader jobs of a pull request use it); a Gradle failure before the game starts is
reported as a failed build, and the run timeout starts when Gradle starts `runClient`. `production`
gives the launcher's install its own limit (`GRUG_CI_INSTALL_TIMEOUT`) and starts the run timeout
at the line that reports the launch, which is `Launched` from portablemc 5.0.6.

- `run-loader.sh` is the only supported way: it resets `run/saves`, sets `GRUG_CI=true`, and
  publishes `grug-core` to `build/maven` first, which the four standalone loaders resolve. The 1.2.5
  loader clones a 170 MB MCP snapshot per worktree; pass `-Pgrug.mcpSnapshot=<path>` to reuse one.
- `GRUG_CI_MODS=a,b` (the `mods` input of `gh workflow run build.yml`) loads every mod but runs only
  the tests of those mods, in the usual path order; an unknown name fails before the game starts. A
  selection without `coverage` leaves the coverage gate short, so use it to try a mod, not to merge.
  A pull request's selection is chosen by `.github/scripts/affected-mods.py` from its diff (its CI log
  says why): a change confined to some mods' `grug` namespace runs those mods, the mods that name
  their files, and `coverage`; anything else, or a doubt, runs every mod. A dispatch (`mods` empty)
  and every push to `main` run every mod, which is how to force them.
- The suite fails fast at the first `[GRUG CI] FAIL`. A grug file that fails to compile is reported
  the same way, but check grug syntax before pushing: `~/agent-workspaces/grug-check.sh <worktree>`
  compiles every `.grug` file with `grugc`.
- Print diagnostics before `ALL N TESTS PASSED`: `run-loader.sh` stops reading the log there.
- Accepted goldens come from GitHub's runners, never a local run: a world capture differs slightly.
  A failing job uploads its capture (`gh run download <run-id> --name loader-<loader>-screenshots`).
  The suite fails fast, so when many goldens change, push the commit to a throwaway branch whose
  loader jobs run `update-goldens` and upload `mods/*/screenshots`, and dispatch it once.
  `update-goldens` writes every unmatched capture under the running loader's name. Look at every
  PNG before committing it.
- Coverage stays at 100% on `core`, and comes from `mods/coverage/` alone: cover a host function
  with a grug test there first, then a Java test, and `@GrugGenerated` only as a last resort.
  The gate counts only the unit tests, each game run's boot and `mods/coverage/`'s tests, so
  another mod's tests cannot be what reaches it; a
  class initializer counts wherever it ran, since it runs once per JVM. A test that
  hits a branch only when its timing happens to fall right (a poll landing while the client is
  behind the server) does not cover it, however many runs passed: the gate flakes. Make the hit
  deterministic first, by moving the decision into a pure function of the values the loader reads
  and unit-testing every combination (`GrugRenderSettle.lightSettled`); only if no test can force
  it, `@GrugGenerated` with a reason that says so.
  "A grug test cannot reach this" is not "no test can reach this".
- A coverage failure prints only the counts: `gh run download <run> --name coverage-report`, then
  look in each runner's XML for a `<line>` with `mi` or `mb` above 0. The report sums every runner,
  so a branch missed in one loader's adapter fails the gate even when another loader covers it.

## Writing

- No em-dashes in files, comments, commit messages or responses, except in quoted source text.
- GitHub prose (issues, pull requests, comments, commit messages) is never hard-wrapped: one line
  per paragraph or bullet. Repository files wrap at about 100 columns.
- Never repeat the title as the first line of an issue or pull request body.
- Commit messages state what changed and why in full sentences.
- A commit uses the repository's configured identity (`git config user.name` and `user.email`), never a
  synthetic one. GitHub counts a commit whose author it cannot link to an account as an unattributed
  change, and the `main` ruleset then requires an extra approval, which a rebase merge cannot give
  itself.
- Do not add repository documentation beyond this file.

## Errors are the only severity

grug has two outcomes, ok and err. `LOGGER.warn`, and a `LOGGER.error` that logs and carries on,
are a third severity: do not use them. Decide which of the two a warning, deprecation or soft
failure really is. `LOGGER.info` and `LOGGER.debug` are diagnostics, never a defect report.

- **An invariant throws** `Grug.fatal(...)`, which crashes the game on purpose even inside a script
  call, because the JNI layer would otherwise swallow the exception. Catching and carrying on past
  an invariant is the bug.
- **A bounded defect is reported and still fails its scope**: `TestTickContext.assert` uses
  `Grug.hostFunctionErrorHappened`, and a test file outside `tests/` fails the run, while the game
  keeps running.
- **A grug mod err is always contained**: the JNI layer prints and clears it and the script carries
  on. That is the sandbox, not a bug.
- Ok is silent, and "it works but the author should know" is not an option.

## Where things live

| path | what |
| :--- | :--- |
| `core/` | shared runtime: host functions, the test runner, `GrugRunWindow`, `GrugScreenshots` |
| `mod_api.json` | the host API mods may call |
| `mod_api.md` | generated names-only overview of `mod_api.json`: read it before writing grug or adding a host function, never edit it by hand |
| `mods/<mod>/code/*.grug` | the mod: `X-Block.grug`, `X_entity-BlockEntity.grug`, `X-Item.grug` |
| `mods/<mod>/tests/*.grug` | tests: `X-Test.grug`; one outside `tests/` is an err that fails the run |
| `mods/<mod>/about.json` | metadata, including the recreation fields on the first `derived_from` entry |
| `mods/<mod>/screenshots/` | golden references: a directory at any depth, `screenshots/` itself included, holds either only subdirectories or only PNGs named after the loader that produced them (`GrugScreenshots.validateReferenceTrees`) |
| `loaders/<loader>/` | one per Minecraft version and mod loader |
| `test-saves/<name>.zip`, `loaders/<loader>/test-save.txt` | the world a loader's tests start from |
| `recreation.py`, `about_schema.py`, `dead_links.py` | metadata validation, each with its own tests |

- The README calls `mod_api.json` frozen. That discourages growth for its own sake, not removals,
  replacements, test-only helpers or tooling fixes. Justify every change to it in the pull request
  description, one by one.
- The goal is to port every Java mod to grug for every Minecraft version ever released, starting
  from Alpha 1.1.2_01, and for every mod loader ever released, so design an entry for the ports
  known to need it, not only the one at hand; one that no committed mod calls yet goes in an issue
  instead. Porting is therefore most of the work and most pull requests will change only `mods/`, so
  a saving counts for each pull request it will affect and is not discounted by how few have that
  shape today.
- Compare every new or changed `mod_api.json` entry with its Forge and Fabric equivalents, and say
  in the pull request how it compares. They are a reference, not a design to copy.
- A host function added for a port is free of the reference's bugs: `mod_api.json` serves every mod.
  The port reproduces such a bug in its own grug code where it can, else records a deviation.
- Nothing outside this repository uses the API yet, so delete a host function outright rather than
  deprecating it, and keep no changelog.
- `mods/coverage/` is the reference for what the language and API accept; read it before
  inventing syntax.
- A port's tests are about the port: whether it behaves and looks like its reference. A test of a
  host function, a loader or the runner goes in `mods/coverage/` even when it needs a block with a
  GUI and adds no coverage, using that mod's blocks and giving them what the test needs.

## Versions are pinned

Every version is exact and bumped by hand, when there is a reason to; bumping grug-rs
(`grugRsRevision` in `core/build.gradle.kts`) changes VM behaviour. The root Gradle wrapper is
frozen at 8.14 (SpongePowered Mixin and ForgeGradle 6 do not support Gradle 9). Pin locations and
bump commands are in #369. `core/`'s main sources compile with `--release 8`, so no `Set.of`,
`List.of` or `String.isBlank` there (the tests may use them).

## grug language rules that cost runs

- [grug-tests](https://github.com/grug-lang/grug-tests) is grug's spec. Its
  [grammar](https://raw.githubusercontent.com/grug-lang/grug-tests/refs/heads/main/grug_grammar.lark)
  lists every operator and construct (there is no `%`; `Math.remainder` is in `mod_api.md`), and
  `tests/` settles edge cases: `ok`, `err`, `err_runtime`, `err_spaces`, `mod_api_schema` and
  `mod_api_semantics`; ignore `wip`. The
  [grug README](https://raw.githubusercontent.com/grug-lang/grug/refs/heads/main/README.md) is the
  overview. Fetch raw URLs.
- A call's arguments must stay on one line.
- Variables are declared at member scope. Helpers are `local` functions defined after their first
  call.
- Names are lowercase. There are no member-scope `local` constants, and `+` does not join strings.
- Resource and entity strings cannot be passed to or returned from helpers; inline the call.
- `==` on an `Item` compares grug entity IDs; use `equals(a, b)`.
- On Alpha, `item("minecraft:cobblestone")` resolves to the block, so `set_item_in_slot` crashes.
  Prefer names that resolve to items everywhere.
- `unwrap()` on an empty `Option` is a runtime error; probe with `.has()`. Host function errors
  carry a grug source position, so read it before guessing.
- A `while` or `if` condition is evaluated every time it is reached, so a host function or helper
  call in one runs again on every iteration or every record. Hoist a value that cannot change into
  a local variable.

## Writing tests

- `tick(ctx)` runs once per tick; `ctx.not_done()` keeps the test alive.
- The runner clears the test area before every test, so a test never compensates for an earlier one:
  it does not reset state a previous test left, close its screen, or guard against a leak. A test
  that opens a GUI is the one that closes it.
- A loader must name the level in its test save exactly, or the game generates a new random world.
- Block ids are handed out in name order, so a new block can move another's id. `high_block_id`
  depends on `zz_coverage_high_id` being last: name a new block in `mods/coverage/` to sort before
  it.
- Wait for a condition rather than a fixed tick count. A fixed count is right only when the thing
  waited for is itself a duration, such as an item travelling along a pipe. A GUI opening, a chunk
  arriving or a world settling has a condition, so wait on it even when it is awkward to name.
- The runner runs every test on the game's clock at the loader's maximum speed (#677): up to 10
  ticks pass in one frame, and a tick budget (200, or `set_max_ticks`) is multiplied by the speed.
  So a test never waits a tick count for something drawn or sent (a frame, a packet, the camera):
  wait for its condition. `GRUG_CI_MAX_GAME_SPEED=1` puts every test back at real time.
- `tolerance()` is a last resort for a rendering difference that cannot be fixed, never for a flaky
  capture. Each call needs a comment linking its tracking issue.
- Every response class in `mod_api.json` (a class the host passes to an export so it can answer,
  such as `ExtractResponse`) needs four tests in `mods/coverage/`, one per rule of `GrugResponse`:
  using the response after the export returned, answering twice with different answers, answering
  twice with the same answer, and returning without an answer. The base class enforces the rules
  once, so each class's tests prove it is wired to it.
- A fix for a defect that a failing or flaky run found ships with a test that fails without it, in
  the same pull request; passing runs afterwards are not evidence for a defect that failed one run
  in three. Prefer a grug test in `mods/coverage/`: it runs on every loader, whereas a Java test
  only reaches `core`. Loaders copy one another's adapter code, so a fix is not finished until every
  other loader's decompiled code has been read for the same defect, fixed in the same pull request,
  with the loaders found clean named. If the failure is intermittent, repeat the trigger many times
  inside one test, putting back the state it needs each time, and show it failing on `main`.

## Ports and the reference

- A port reproduces its reference exactly, in behaviour and in graphics, reference bugs included;
  there is no lesser standard. It targets the first entry of `about.json`'s `derived_from`: its
  `artifacts` are the reference files, its `runtime` is where they run, and `deviations` lists every
  known gap still to be closed, each with a tracking issue. A port is done when its `deviations` key
  is removed (the schema refuses an empty list), and from then on `Screenshot.tolerance` refuses any
  percentage above 0 in its tests (BuildCraft's checklist is #75).
- The one exception is precision: a reference's Java `float` becomes a grug `number`, a 64-bit
  double. Do not emulate float32 rounding, and derive tick counts from float64 arithmetic. A mod's
  configs are the defaults the reference ships with.
- Read the reference before inventing a model. BuildCraft 2.2.14 is already decompiled at
  `~/agent-workspaces/buildcraft-2.2.14-src/src`, with the original class files and textures in
  `classes/`; do not decompile it again. The jars are in `~/.technic/modpacks/tekkit/mods/` and
  Vineflower is at
  `~/.gradle/caches/forge_gradle/maven_downloader/org/vineflower/vineflower/1.10.1/vineflower-1.10.1.jar`.
  1.2.5 is decompiled under `loaders/1.2.5-forge/mcp125`; for other versions, decompile the
  exact jar from the Gradle caches rather than trusting memory.
- The reference run is the `reference` CI job: `GRUG_REFERENCE=1`, a harness-only `GRUG_MODS_DIR`
  and `GRUG_REFERENCE_MODS_DIR` at the mods `recreation.py stage` staged. Develop a reference
  fixture there, on a throwaway branch, never by launching the game locally.
- Mods ship copies of vanilla textures on purpose (preservation, and the running version often lacks
  them). Do not flag them as a licensing problem; a report about them from Mojang or Microsoft goes
  to the maintainer, and an agent never closes it.
- A rendering difference between Minecraft versions is usually vanilla, not a port bug, such as
  Alpha's GUI captures sitting one pixel right. Check the vanilla code before "fixing" the port.
- Only the reference's `runtime` is a strict graphical reference. BuildCraft and most mods never
  shipped for Alpha or Beta, so outside GUI elements nothing there says how a port should render:
  choose what players and modders would prefer, keep it the same across loaders where you can, and
  say what you chose in the pull request.

## Issues and pull requests

- Create an issue before a pull request, then set its type (`Bug`, `Feature` or `Task`) and then
  its effort (`High`, `Medium` or `Low`), in that order (Effort shows only once a type is set):
  `gh api repos/grug-lang/grug-for-minecraft/issues/<n> -X PATCH -f type=Task`, then
  `gh api repos/grug-lang/grug-for-minecraft/issues/<n>/issue-field-values -X POST --input -` with
  `{"issue_field_values":[{"field_id":36963465,"value":"Medium"}]}`.
- Give every new issue at least one fitting label (`gh label list`), with `--label` on
  `gh issue create`. Never add `planned` or `maybe planned`: the repository owner decides those.
- Record progress on an issue (what a pull request did, what is left) by rewriting its body, not
  in a comment: the body is what the next reader reads first.
- Run `git add -A && pre-commit run --all-files` twice before pushing: the first run may reformat
  and fail, the second must pass. It only sees tracked files, hence the `git add`.
