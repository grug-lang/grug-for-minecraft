# grug-for-minecraft: agent notes

Agent-facing notes, loaded into every request, so every line has a cost. A line earns its place
only if leaving it out would cost a fresh agent a run, a wrong assumption, or a broken machine.
Findings belong in issues and pull requests; point at a file rather than restating it, and delete
lines that stopped being true. Detail moved out of this file lives in #369.

## Machine limits

The machine has about 15 GB of RAM, and more than two Minecraft clients at once has hard-locked it.
The limit is machine-wide, not per agent: check `pgrep -af java` before starting a client, wait
rather than start a third, and never start one while another Gradle build is launching a game.

## Working alongside other agents

- One git worktree per task: `git worktree add ~/agent-workspaces/grug-for-minecraft-<task> -b
  <branch>`, and work only there. Each worktree gets its own `loaders/*/run/`.
- Push and open the pull request in the same sitting, a draft if unfinished. The workflows trigger
  only on `pull_request`, so a branch without one is never verified, and `refs/pull/<n>/head`
  keeps the commits after the branch is deleted.
- When a task lands: `git worktree remove --force <path>`, `git cherry origin/main <branch>` (all
  lines start with `-` after a rebase merge), `git branch -D <branch>`, `git worktree prune`.
- The 1.2.5 loader clones a 170 MB MCP snapshot per worktree; pass `-Pgrug.mcpSnapshot=<path>` to
  reuse an existing one. `~/.gradle` is shared, so concurrent builds can block on cache locks.

## Writing

- No em-dashes in files, comments, commit messages or responses, except in quoted source text.
- GitHub prose (issues, pull requests, comments, commit messages) is never hard-wrapped: one line
  per paragraph or bullet. Repository files keep their own wrapping.
- Never repeat the title as the first line of an issue or pull request body.
- Commit messages state what changed and why in full sentences.

## Errors are the only severity

grug has two outcomes, ok and err. `LOGGER.warn`, and a `LOGGER.error` that logs and carries on,
are a third severity: do not use them. Decide which of the two a warning, deprecation or soft
failure really is. `LOGGER.info` and `LOGGER.debug` are diagnostics, never a defect report.

- **An invariant throws** `Grug.fatal(...)`, which crashes the game on purpose even inside a script
  call, because the JNI layer would otherwise swallow the exception. Catching and carrying on past
  an invariant is the bug.
- **A bounded defect is reported and still fails its scope**: `Test.assert` uses
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
  replacements, test-only helpers or tooling fixes. Every change to it must be justified: once the
  pull request is ready for review or merge, explain to the user why each change was made.
- The goal is to port every Java mod to grug, so design an entry for the ports known to need it,
  not only the one at hand: generalising it a little for a need later ports are known to have is
  fine. An entry or parameter that no committed mod calls yet is not; record it in an issue instead.
- Compare every new or changed `mod_api.json` entry with its Forge and Fabric equivalents, and say
  in the pull request how it compares. They are a reference to compare and contrast against, not a
  design to copy.
- A host function added for a port is free of the reference's bugs, even where the reference's own
  version of it was buggy: `mod_api.json` serves every mod, not only the one being ported. The port
  reproduces such a bug in its own grug code where it can, and records it as a deviation where it
  cannot.
- Nothing outside this repository uses the API yet, so delete a host function outright rather than
  deprecating it, and keep no changelog. Revisit when the first external mod exists.
- `mods/coverage/` is the reference for what the language and API accept; read it before
  inventing syntax.
- A port's tests are about the port: whether it behaves and looks like its reference. A test of a
  host function, a loader or the runner goes in `mods/coverage/` even when it needs a block with a
  GUI and adds no coverage, using that mod's blocks and giving them what the test needs.

## Running a loader

```sh
xvfb-run -a -s "-screen 0 1280x720x24 +extension RANDR +extension GLX" \
  .github/scripts/run-loader.sh <loader> build|run|update-goldens
```

- `run-loader.sh` is the only supported way: it resets `run/saves`, sets `GRUG_CI=true`, and
  publishes `grug-core` to `build/maven` first, which the four standalone loaders resolve.
- Repeat the suite in one session with `GRUG_CI_REPEATS=<n>` plus a raised `GRUG_CI_RUN_TIMEOUT`
  locally, or `gh workflow run build.yml --ref <branch> -f repeats=<n>` on CI. Dispatches of
  `build.yml` on one branch cancel each other, so to run several at once, which a failure that
  is rare needs, push the same commit to throwaway branches and dispatch each (the runners are
  GitHub's, so this costs your machine nothing). Delete them when done. Read a job's log with
  `gh api repos/<repo>/actions/jobs/<id>/logs`.
- The suite fails fast at the first `[GRUG CI] FAIL`. A grug file that fails to compile makes it
  hang until the timeout instead (#366), so check grug syntax before pushing.
- `update-goldens` replaces only that loader's own reference. A GUI capture may come from it, but a
  world capture differs slightly between local runs and CI, so take it from the failing CI job:
  `gh run download <run-id> --name loader-<loader>-screenshots`. Look at every PNG before
  committing it.
- Coverage stays at 100% on `core`. Cover a host function from a committed mod's own path first,
  then a grug test in `mods/coverage/`, then a Java test, and `@GrugGenerated` only as a last
  resort. "A grug test cannot reach this" is not "no test can reach this".
- A coverage failure prints only the counts. The gate uploads `coverage-report` even when it
  fails, so `gh run download <run> --name coverage-report` is the fast route: each runner's XML
  has a `<line>` with `mi` or `mb` above 0 for a missed instruction or branch. The report sums
  every runner, so a branch missed in one loader's adapter fails the gate even when another loader
  covers it. A coverage job cancelled before that upload still leaves the per-runner exec and class
  artifacts for `.github/scripts/combine-coverage.py`.
- Print diagnostics before `ALL N TESTS PASSED`: `run-loader.sh` stops reading the log there.

## Versions are pinned

Every version is exact and bumped by hand, when there is a reason to. grug-rs (`grugRsRevision` in
`core/build.gradle.kts`) is a VM behaviour change.
The root Gradle wrapper is frozen at 8.14 because the abandoned SpongePowered Mixin plugin and
ForgeGradle 6 do not support Gradle 9. Read a major version before taking it. Pin
locations and bump commands, and why dynamic selectors and checksums were pinned, are in #369.

## grug language rules that cost runs

- [grug-tests](https://github.com/grug-lang/grug-tests) is grug's spec. Its
  [grammar](https://raw.githubusercontent.com/grug-lang/grug-tests/refs/heads/main/grug_grammar.lark)
  lists every operator and construct (there is no `%`), and `tests/` settles edge cases: `ok`,
  `err`, `err_runtime`, `err_spaces`, `mod_api_schema` and `mod_api_semantics`; ignore `wip`. The
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

- `run()` is called once per tick; `me.not_done()` keeps the test alive.
- The runner clears the test area before every test, so a test starts from a clean test area and
  never compensates for an earlier test: it does not reset state a previous test left, close a
  screen a previous test opened, or guard against a leak at all. A test that opens a GUI is the one
  that closes it.
- A loader must name the level in its test save exactly, or the game generates a new random world.
- Wait for a condition rather than a fixed tick count. A fixed number of ticks is right only when
  the thing waited for is itself a duration with no condition to observe, such as an item travelling
  along a pipe, which just takes time. A wait for a GUI to open, a chunk to arrive or a world to
  settle has a condition, so wait on it even when the condition is awkward to name.
- `tolerance()` is a last resort for a rendering difference that cannot be fixed, never for a flaky
  capture. Each call needs a comment linking its tracking issue.
- Every response class in `mod_api.json` (a class the host passes to an export so it can answer, such as `ExtractResponse`) needs four tests in `mods/coverage/`, one per rule of `GrugResponse`: using the response after the export returned, answering twice with different answers, answering twice with the same answer, and returning without an answer. The base class enforces the rules once, so each class's tests prove it is wired to it.
- A fix for a defect that a failing or flaky run found ships with a test that fails without it, in
  the same pull request. Runs that pass afterwards are not evidence for a defect that failed one run
  in three. Prefer a grug test in `mods/coverage/`, as for coverage above: it runs on every loader,
  including ones added later, whereas a Java test only exists in `core`, which no loader code
  reaches, so write one only when no grug test can reach the defect. Loaders copy one another's
  adapter code, so a fix for one loader is not finished until every other loader's decompiled code
  has been read for the same defect: the twin usually sits in each loader that copied the code, and
  stays hidden wherever that loader's own query cannot see it, which is what lets it flake later.
  Fix it in the same pull request, and say which loaders were checked and found clean. If the
  failure is intermittent, repeat the trigger many times inside one test, putting back each time the
  state the failure needs, so one pass hits it, and show the test failing on `main` plus only the
  test.

## Ports and the reference

- A port reproduces its reference exactly, in behaviour and in graphics, reference bugs included;
  there is no lesser standard. It targets the first entry of `about.json`'s `derived_from`: its
  `artifacts` are the reference files, its `runtime` is where they run, and `deviations` lists every
  known gap still to be closed, each with a tracking issue. A port is done when its `deviations` key is
  removed (the schema refuses an empty list), and from then on `Screenshot.tolerance` refuses any percentage above 0 in its tests
  (BuildCraft's checklist is #75).
- The one exception is precision: a reference's Java `float` becomes a grug `number`, a 64-bit
  double, because the difference is not perceptible to players. Do not emulate float32 rounding,
  and derive tick counts from float64 arithmetic.
- When a ported mod has configs, a port reproduces the default config: the values the reference ships
  with and runs with when nobody edits the file.
- Read the reference before inventing a model. BuildCraft 2.2.14 is already decompiled at
  `~/agent-workspaces/buildcraft-2.2.14-src/src`, with the original class files and textures in
  `classes/`; do not decompile it again. The jars are in `~/.technic/modpacks/tekkit/mods/` and
  Vineflower is at
  `~/.gradle/caches/forge_gradle/maven_downloader/org/vineflower/vineflower/1.10.1/vineflower-1.10.1.jar`.
- Mods ship copies of vanilla textures on purpose, as a preservation effort: a mod runs on every
  supported version, often with textures the running version lacks, and asking players to point
  grug at extra Minecraft installations would stop some from ever running it. Do not flag them as a
  licensing problem, but a report about them from Mojang or Microsoft goes to the maintainer, and
  an agent never closes it.
- A rendering difference between Minecraft versions is usually vanilla, not a port bug, such as
  Alpha's GUI captures sitting one pixel right. Check the vanilla code before "fixing" the port.
- Only the reference's `runtime` is a strict graphical reference. BuildCraft and most mods never
  shipped for Alpha or Beta, so outside GUI elements nothing there says how a port should render:
  choose what players and modders would prefer, keep it the same across loaders where you can, and
  say what you chose in the pull request.
- The reference run is manual: `GRUG_REFERENCE=1` plus a harness-only mods directory.

## Instrumenting the game

- 1.2.5 is decompiled under `loaders/1.2.5-forge/build/mcp125`, a git checkout. A temporary print
  works if that one file is recompiled with JDK 8 `javac` into `bin/minecraft`; revert it after.
- For other versions, decompile the exact jar from the Gradle caches rather than trusting memory.

## Workflow

- Create an issue before a pull request, then set its type (`Bug`, `Feature` or `Task`) and then its
  effort (`High`, `Medium` or `Low`), in that order, since Effort only shows once a type is set:
  `gh api repos/grug-lang/grug-for-minecraft/issues/<n> -X PATCH -f type=Task`, then
  `gh api repos/grug-lang/grug-for-minecraft/issues/<n>/issue-field-values -X POST --input -` with
  `{"issue_field_values":[{"field_id":36963465,"value":"Medium"}]}`.
- Give every new issue at least one fitting label (`gh label list` shows them, for example `bug`, `feature`, `docs`, `backend`, `frontend`), with `--label` on `gh issue create`. Never add `planned` or `maybe planned`: the repository owner decides those.
- Merge only with the maintainer's authorization, by rebase. Nothing is pushed to `main`. A pull
  request that changes only markdown merges without waiting for CI.
- Do not add repository documentation beyond this file.
- When the user has to look at images, copy them into one directory you choose and give its path.
- Wait with a tool that watches, never a fixed sleep: run `gh run watch <id> --repo
  grug-lang/grug-for-minecraft --exit-status --interval 20` in the background so a long run costs
  no turns. Get the id from `gh api 'repos/grug-lang/grug-for-minecraft/actions/runs?branch=<b>'`;
  `gh pr checks --watch` fails right after a push and reads stale runs after a force-push.
- A push that shows no CI run usually means a conflict: check `gh pr view <n> --json mergeable`.
- `pre-commit run --all-files` only sees tracked files, so re-run it after `git add` of a new file.
