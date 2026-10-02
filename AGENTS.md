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
- The suite fails fast at the first `[GRUG CI] FAIL`. To verify tests that run after a known
  failure, temporarily copy the capture from
  `loaders/<loader>/run/grug-screenshot-artifacts/<mod>/screenshots/<name>/N.png` into the golden
  directory as the next number, re-run, then delete it.
- `update-goldens` accepts an unmatched capture as the next number and never overwrites one.
  Goldens are per distinct rendering, not per loader.
- Coverage must stay at 100% on `core`. Loader UI/GL classes are excluded by
  `.github/scripts/combine-coverage.py`.

## grug language rules that cost runs

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
  they are left behind for later tests, so pick a free band. The pipe tests use +30, +40, +45,
  +50 and +55.
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
- Run `pre-commit run --all-files` after staging a new file, or re-run it once the file is tracked:
  it only sees files git already tracks, so an untracked file is skipped and the hook still reports
  success, and CI then reformats it.
