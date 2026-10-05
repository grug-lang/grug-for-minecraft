# grug-for-minecraft: agent notes

Agent-facing notes for this repository. This is not user documentation and it is not a findings
log: a finding belongs in the issue or pull request that acted on it. A line earns its place
here only if leaving it out would cost a fresh agent a run, a wrong assumption, or a broken
machine. Keep it short, prefer pointing at a file over restating it, and delete lines that
stopped being true.

## Machine limits

This is a ~15 GB laptop. More than two Minecraft clients at once has hard-locked it and killed
the desktop session. Run loaders serially, never more than two at a time, and never start a
client while another Gradle build is launching a game.

## Working alongside other agents

- Work in your own git worktree, one per task, and run the session from there:
  `git worktree add ~/agent-workspaces/grug-for-minecraft-<task> -b <branch>`. A shared checkout
  means two agents see each other's edits, branches and test runs, and every confusing failure
  becomes unattributable. Move the session into the worktree when the harness has a tool for that.
- Push that branch and open its pull request in the same sitting, a draft if the work is not
  finished: `git push -u origin <branch>`, then `gh pr create --draft`. Nothing verifies a branch
  that has no pull request, because the workflows trigger on `pull_request` and there is no `push:`
  trigger to fall back on. Keeping the pull request also outlives the branch: `refs/pull/<n>/head`
  still resolves after the branch is deleted, so without one the branch is the only ref and the
  cleanup below takes the commits with it.
- When a task lands, remove its worktree and branch from the main checkout:
  `git worktree remove --force <path>`, then `git cherry origin/main <branch>` (every line starts
  with `-` once a rebase merge carries the commits; `git branch --merged` reports the opposite),
  then `git branch -D <branch>` and `git worktree prune`.
- Each worktree gets its own `loaders/*/run/`, so game runs and screenshot artifacts do not
  collide through `run/saves`, `run/grug_mods` or the capture directory.
- The two-client limit above is for the whole machine, not per agent. Check what is already
  running (`pgrep -af java`) before starting a client, and wait rather than start a third.
- The 1.2.5 loader clones a 170 MB MCP snapshot into `build/mcp125` in every worktree. Point a
  new worktree at an existing checkout with `-Pgrug.mcpSnapshot=<path>` instead of cloning again.
- The Gradle daemon and the `~/.gradle` caches are shared. Two builds can run at once, but they
  can block on cache locks, so prefer to let another agent's build finish first.

## Writing and explaining

- Never use em-dashes in files, comments, commit messages or responses. Quoted source text that
  already contains one is the only exception.
- When explaining how code runs, walk through it in chronological order with numbered steps
  starting at 1. Use many small code blocks, one per moment, and keep the annotation next to the
  code it describes rather than describing a whole function up front. A step may hold more than
  one block, and a short fragment can stay inline in a sentence.
- Commit messages state what changed and why in full sentences; the reasoning goes in the body.

## Errors are the only severity

grug has two outcomes, ok and err, and no third. `LOGGER.warn`, and a `LOGGER.error` that logs and
carries on, are that third severity: do not use them. If you are designing a warning, a deprecation
notice, a soft failure, or a "just log it" path, stop. Decide which of the two it is, and make the
code report it as that.

`LOGGER.info` and `LOGGER.debug` are not a severity: they are the diagnostics an agent adds while
writing a PR, permanently or temporarily, so use them where they help, such as a line when the
player joins a world. They cannot report a defect; a defect is still a fatal or an err.

How bad the defect looks does not decide this. What decides it is whether the violation leaves
behaviour undefined, and which side of the sandbox the code is on.

- **An invariant throws.** Something whose violation leaves behaviour undefined goes through
  `throw Grug.fatal(...)`, which crashes the game on purpose even when a script called in, because
  one crash is better than a thousand players silently depending on a broken invariant. `fatal`
  exists precisely because the JNI layer would otherwise swallow the exception and let the script
  carry on. If you catch something and carry on past an invariant, the catch is the bug.
- **A bounded defect is reported, and still fails.** `Test.assert` uses
  `Grug.hostFunctionErrorHappened`, not `fatal`, because "a failed assertion should fail just the
  test, not take the whole game down". A mod-tree defect such as a test file outside `tests/` is the
  same severity: reported, and it fails the run. The scope fails either way, only the game keeps
  running.
- **A grug mod err is always contained.** The JNI layer prints and clears anything thrown inside a
  game function so the script carries on, which is the sandbox and is the product. Never design a
  mod-side err to stop the game, and never read "the script kept going" as the bug.
- Ok is silent. A file in the right shape produces no output at all, so a validator does not add
  chatter to say that it is fine.
- "It still works, but the author should know" is not an option. Either the behaviour is right, or
  it is an err that fails.

## Where things live

| path | what |
| :--- | :--- |
| `core/` | shared runtime: the grug language, host functions, the test runner, its window state machine (`GrugRunWindow`), `GrugScreenshots` |
| `mod_api.json` | the host API mods may call. Not frozen, but growth for its own sake is discouraged |
| `mods/<mod>/code/*.grug` | the mod: `X-Block.grug`, `X_entity-BlockEntity.grug`, `X-Item.grug` |
| `mods/<mod>/tests/*.grug` | tests: `X-Test.grug`, which is a `Test` entity like any other grug file |
| `mods/<mod>/assets/` | assets shipped with the mod |
| `mods/<mod>/data/` | data shipped with the mod |
| `mods/<mod>/about.json` | metadata, including the `recreation` block: reference, artifacts, runtime, deviations |
| `mods/<mod>/screenshots/<name>/N.png` | golden references, one per distinct rendering |
| `loaders/<loader>/` | one per Minecraft version and mod loader; `src/main/java` holds its adapter |
| `test-saves/<name>.zip` and `loaders/<loader>/test-save.txt` | the world a loader's tests start from |
| `.github/scripts/run-loader.sh` | the only supported way to run a loader |
| `recreation.py`, `about_schema.py`, `dead_links.py` | metadata validation, each with its own tests |

The README calls `mod_api.json` frozen. That discourages API growth for its own sake, not real
changes: removing or replacing entries, adding test-only helpers, and fixing tooling are all
fine, so do not block those on the freeze.

Any file named `*-Test.grug` under `mods/` joins every loader's suite, so tests live in
`mods/<mod>/tests/`. One that does not is an err, not a warning: `GrugFileIndex` reports it to the
player in chat and as a `[GRUG CI] FAIL` line, which fails the run. It matters because
`GrugTestRunner` sorts tests by path, so a test in `code/` runs out of name order, and the tests
share one world. `mods/coverage/` is the reference for what the language and API accept; read its
`code/` and `tests/` before inventing syntax.

## Running a loader

```sh
.github/scripts/run-loader.sh <loader> build|run|update-goldens
```

It stages the test save into `loaders/<loader>/run/saves`, sets `GRUG_CI=true`, and launches.
For a headless run:

```sh
xvfb-run -a -s "-screen 0 1280x720x24 +extension RANDR +extension GLX" \
  .github/scripts/run-loader.sh 1.2.5-forge run
```

- The real tasks live in `loaders/<loader>/build.gradle`; the four standalone loaders also have a
  `root.gradle` that the root project's `runClient` execs, so both invocations reach them. A
  plain `runClient` is for playing and leaves `run/saves` alone; only `run-loader.sh` resets it.
- The four standalone loaders resolve `net.grug:grug-core` from the checkout's `build/maven`, which
  `gradle/grug-core.gradle` names and `core` publishes into under a version stamped with the commit.
  Both entry points above publish it first; a loader's own build run on its own asks for the
  coordinate of the commit it is on and fails to resolve if that commit has not been published here.
- The suite fails fast at the first `[GRUG CI] FAIL`. To verify tests that run after a known
  failure, temporarily copy the capture from
  `loaders/<loader>/run/grug-screenshot-artifacts/<mod>/screenshots/<name>/N.png` into the golden
  directory as the next number, re-run, then delete it.
- `update-goldens` accepts an unmatched capture as the next number and never overwrites one.
  Goldens are per distinct rendering, not per loader.
- Coverage must stay at 100% on `core`. Loader UI/GL classes are excluded by
  `.github/scripts/combine-coverage.py`.
- `GrugTestRunner` prints every test's time, slowest first, just before `ALL N TESTS PASSED`. Print
  there and not after it: `run-loader.sh` stops watching the log at that announcement, so anything
  below it is never read.

## Versions are pinned, and there is no Dependabot

Every version this repository builds against is pinned to an exact value, and every one of them has to
be bumped by hand:

| what | pinned where | how to bump |
| :--- | :--- | :--- |
| Gradle plugins | each `loaders/*/build.gradle` or `build.gradle.kts` | `id '...' version '...'` |
| Gradle distributions | `distributionSha256Sum` in each `gradle-wrapper.properties` | `curl -sSL <url>.sha256` |
| GitHub Actions | a commit SHA with the release as a comment in each workflow | `gh api repos/<owner>/<repo>/git/ref/tags/<tag>` |
| Python | `pip install <name>==<version>` in the workflow that needs it | PyPI |
| grug-rs | `grugRsRevision` in `core/build.gradle.kts` | `git ls-remote` |
| Minecraft, mappings, libraries | `gradle.properties` | already exact |

Two of these were not, and cost time rather than safety. A dynamic plugin selector such as
`[6.0.24,6.2)` or `0.7.+` is not a version: Gradle has to ask the repository what matches now on every
configuration, and `1.20.6-forge` spent 28 of its 63 second Build step there (#191). Pinning it locally
saved 0s to 2s, because a warm `~/.gradle` already has the answer, so only CI shows the difference.

A `distributionSha256Sum` is checked only when the distribution is downloaded, so a wrong value fails
on a fresh machine and never on a warm one. Each was verified against the downloaded zip before being
committed, and enforcing it looks like this:

```
Expected checksum: '00000010d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa'
  Actual checksum: '61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa'
```

To test that a new checksum is enforced rather than merely present, delete the extracted distribution
or point `GRADLE_USER_HOME` at an empty directory first. Otherwise a warm machine passes either way.

## Reading CI timings

Measure before and after from the workflow's own job timings rather than estimating:

```sh
gh api "repos/grug-lang/grug-for-minecraft/actions/runs/<id>/jobs?per_page=100"
```

Each step has `started_at` and `completed_at`. Within a step, the gaps between `> Task` lines in
`gh api .../actions/runs/<id>/logs > logs.zip` say where the time went; cargo's own `Finished
\`release\` profile ... in Xs` line is the only trustworthy figure for a Rust build, because Gradle
buffers a subprocess's output and flushes it when the task ends.

Four traps, each of which cost a run here:

- **`gh workflow run --ref <branch>` measures cold.** Caches written by a pull request live on
  `refs/pull/<n>/merge` and a dispatch on the branch cannot read them. Push a commit to measure warm.
- **A cache is written once per key per ref, and only when it is absent.** Two consequences, and both
  have cost a run here. The first is that changing a cache's *list of paths* while leaving its key
  alone does nothing at all: the stale entry restores successfully and the save is then skipped, so the
  change silently has no effect. The symptom is a cache that *hits* when you expected a miss, not one
  that restores unchanged. `build.yml` now hashes itself into all of its keys, which is what makes the
  path list part of what the key describes.
- **Caches are scoped to a ref, and a pull request's are not the base branch's.** The same key exists
  as a separate entry on `refs/pull/<n>/merge` for each open pull request, and `main` cannot read any
  of them. Verified: `pre-commit-Linux-b34053ba...` was present three times at once, on three different
  pull request merge refs. So every open branch writes its own copy of the same gigabytes, and a cache
  measured on a pull request is discarded when it merges.
- **Each new pull request therefore starts cold, and that is where the cost is.** `build.yml` triggers on
  `pull_request` and `workflow_dispatch` only, with no `push`, so merging to `main` runs nothing and there
  is no `main` run to be cold. One cold run per pull request, then every push to it hits, which is where
  the saving lands. A push that touches neither `build.yml` nor a properties file moves no key and keeps
  every cache; one that does touch either pays to repopulate.
- **The repo cache store is 10GB and evicts, and the churn above is what fills it.** Every new
  generation of a key costs another immutable entry, and four generations of a 424MB entry plus four of
  a 93MB one existed on one pull request simultaneously. Eviction takes out the live ForgeGradle cache,
  which presents as one loader suddenly being slow. Look for superseded generations first:
  `gh api "repos/grug-lang/grug-for-minecraft/actions/caches?per_page=100"`, keeping the newest per key
  per ref.
- **The first run of a cache cannot tell you what it is worth.** Every change here needs two runs, and
  a run whose first job repopulated everything is not a measurement of anything.

`main` moves while a branch is open, so a baseline taken against an old `main` is not comparable to
the branch after a rebase: the test count changes. Take the baseline from
`gh workflow run build.yml --ref main` when the comparison matters.

## grug language rules that cost runs

- The [grug README](https://raw.githubusercontent.com/grug-lang/grug/refs/heads/main/README.md) is
  authoritative on what the language is and is not. Its advanced example shows virtually every
  feature grug has, so a feature missing from that example does not exist. Its Links section names
  the other implementations and `grug-tests`, the official suite, and its How? section links
  `grug-tests`' grammar and the `mod_api.json` schema. Fetch the raw URL rather than a github.com
  one: it is the same text without the HTML around it.
- A call's arguments must stay on one line.
- No chained calls: `x.unwrap().damage()` is rejected. Bind `x.unwrap()` to a local first.
- Variables are declared at member scope. Helpers are `local` functions and must be defined
  after the first place they are called.
- Names must be lowercase. There are no member-scope `local` constants, and `+` works on numbers,
  not strings.
- Resource and entity strings cannot be passed to or returned from helpers; inline the call.
- `==` on an `Item` compares grug entity IDs, not the game object. Use `equals(a, b)`.
- On Alpha, `item("minecraft:cobblestone")` falls through to the block of that name, so it comes
  back as a `Block` and `set_item_in_slot` crashes. Prefer names that resolve to items everywhere.
- `Option` has `.has()` and `.unwrap()`, and `unwrap()` on an empty option is a runtime error.
  Host function errors carry a grug source position; read it before guessing.

## Writing tests

- `run()` is called once per game tick. `Test.not_done()` keeps the test alive; returning without
  it ends the test.
- `Test.get_origin()` is the player's position plus 3. Fixtures are built in bands above it, and
  they are left behind for later tests, so pick a free band. The pipe tests use +25, +35, +40,
  +45 and +50.
- The bands are measured from the player, so their absolute height depends on where the player
  spawns, and both Alpha and Beta 1.7.3 cap the world at 128 blocks. `test-saves/b1.7.3.zip` puts
  the player on a column whose surface is y=70, so the top band lands at y=124 there. A new band
  goes below +50, not above it, or the highest one runs off the top of the world and `place_block`
  reports it.
- A loader has to name the level its test save contains. `startGame` creates the level when the name
  does not resolve, so the wrong name is not a no-op: it generates a fresh world with a random
  spawn, which is how the b1.7.3 runs got a different player height every time.
- A fixed tick budget races the engine, and a setup at tick 0 can run before the client has
  received the chunk it builds in. Prefer waiting for a condition, and use `Option.has()` for
  probing, because `Test.assert` fails the test on the spot.
- A new screenshot must be accepted through `update-goldens`, never copied by hand.

## Fidelity and the reference

- A port targets the build in `mods/<mod>/about.json`'s `recreation` block: `artifacts` are the
  reference files, `runtime` is where they run, and `deviations` lists every deliberate
  difference. `about_schema.py` refuses `"fidelity": "exact"` while any deviation is present, so
  the deviations array is the checklist for `exact` (BuildCraft's is issue #75).
- Read the reference code before inventing a model. The pinned BuildCraft jars are on this
  machine under `~/.technic/modpacks/tekkit/mods/`, and a decompiler (Vineflower) is in the
  Gradle cache. `PipeItemsWood` and `PipeLogicWood` are good examples of what that saved.
- A rendering difference between two Minecraft versions is usually a vanilla difference, not a
  port bug. Beta 1.7.3 and 1.2.5 shade GUI items differently, which is why 1.2.5 has its own
  golden. Check the vanilla code before "fixing" the port.
- The reference run is manual: `GRUG_REFERENCE=1` plus a harness-only mods directory. It is not
  part of CI, because fidelity is currently behavioral rather than exact.

## Instrumenting the game

- 1.2.5's game is decompiled under `loaders/1.2.5-forge/build/mcp125`. A temporary print in that
  tree works if the one file is recompiled with the JDK 8 `javac` into `bin/minecraft`; the
  loader build repacks it. Revert it before committing, and remember the tree is a git checkout.
- Other loaders' jars come from the Gradle caches. When the vanilla behaviour matters, decompile
  the exact version rather than trusting memory of another one.

## Workflow

- Create an issue before a pull request. Rewrite a thin issue rather than deleting it.
- Pull requests merge only with the maintainer's authorization. The repository merges by rebase,
  not squash.
- Do not add repository documentation. This file is the exception, and findings still belong in
  issues and pull requests.
- When more than one path is plausible, save progress in a draft pull request whose description
  carries every measurement and the open question.
- Wait with a tool that watches, not with a hardcoded sleep. `sleep 30` and then look guesses how
  long something takes, so it either burns a turn or reports a stale result. Let the tool block:
  `gh run watch <id> --repo grug-lang/grug-for-minecraft --exit-status --interval 20` exits
  non-zero when the run fails, and `gh pr view <n> --json state,mergeable,mergeStateStatus` settles
  whether a merge is possible. Keep `sleep` for waiting on this machine, where nothing is polling,
  such as a game starting.
- `gh pr checks <n> --watch --fail-fast` looks like the same tool and is not, in two ways this
  session paid for. It exits non-zero on a perfectly healthy run, because a pull request reports no
  checks at all for the few seconds after a push and it treats that as an error instead of waiting.
  And it watches the pull request, so a force-push leaves it reading the superseded run's result. Get
  the run id from `gh api repos/grug-lang/grug-for-minecraft/actions/runs?branch=<branch>` and watch
  that.
- Check whether a push actually triggered CI before believing it did. A branch that does not merge
  has no merge ref, so a `pull_request` workflow has nothing to check out and reports no run at all,
  which reads like a delivery failure but is a conflict. `gh pr view <n> --json mergeable` says
  which it is; `mergeable: CONFLICTING` means rebase, and `UNKNOWN` means GitHub is still
  computing, so wait rather than concluding anything.
- Run `pre-commit run --all-files` after staging a new file, or re-run it once the file is tracked:
  it only sees files git already tracks, so an untracked file is skipped and the hook still reports
  success, and CI then reformats it.
