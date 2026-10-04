# grug for Minecraft · ![Coverage](https://img.shields.io/badge/coverage-100%25-brightgreen) ![Branches](https://img.shields.io/badge/branches-100%25-brightgreen)

[grug](https://github.com/grug-lang/grug)'s primary goal is to serve as a faithful digital preservation format for mods, so that players can continue enjoying the hard work of mod authors for decades to come.

https://github.com/user-attachments/assets/2a7949ae-643c-4274-9a06-12e528affe97

In the video above, four different Minecraft environments all hot-reload the same grug file:
* Minecraft 1.20.6 with Forge
* Minecraft Beta 1.7.3 with Ornithe
* Minecraft Beta 1.7.3 with StationAPI
* Minecraft Alpha 1.1.2_01 with Ornithe

> [!NOTE]
> `mod_api.json` is currently frozen. We will not be expanding the API until comprehensive test coverage and Continuous Integration (CI) pipelines are fully established. Once expansion resumes, new API additions will initially be limited to features Minecraft Alpha supports, so that mods stay compatible across every supported version from Alpha onward.

## Licensing

If a mod contains copyrighted material or prohibits redistribution, please [open a GitHub issue](https://github.com/grug-lang/grug-for-minecraft/issues) with supporting evidence.

Every mod must ship its license text as a non-empty `LICENSE` file next to its `about.json`. grug refuses to start when one is missing, so the requirement is enforced by the game itself rather than only by CI.

[`mods/examplemod`](mods/examplemod) is the reference mod that tutorials and other mods are meant to copy from. It's licensed under the [BSD Zero Clause License](https://spdx.org/licenses/0BSD.html), the license grug recommends for all mods written from scratch, so that snippets and files can be copied between mods as freely as possible.

## Long-term Plans

This repository aims to become a thoroughly vetted, centralized host for grug mods via the `mods/` directory at its root, rather than depending on external platforms like Modrinth or CurseForge. Support for downloading mods through APIs like Modrinth's may be added eventually, but isn't a priority given the `mods/` directory already covers hosting, discovery, and vetting on its own. Because `mod_api.json` restricts what mods can do, CI will be able to auto-merge pull requests that only modify grug code and come from a GitHub account listed as an author in the mod's `about.json`, while reviewers focus on resource files.

An in-game mod portal will let players browse, install, and update mods without restarting the game, get notified of new releases and changelogs, and even write new mods from scratch using a built-in text editor and Scratch-like blocks interface. Since `mod_api.json` specifies which versions of the game each function is available in (Minecraft Alpha, for example, didn't have a hunger system), the portal will also be able to disable grug files that call functions unavailable in the current version, using `e""` entity strings to detect when disabling one file cascades into disabling others that depend on it. Keeping mods centralized in this repository also means the portal can give players a way to freely copy-paste and search real-world grug code across every mod, whether to learn from it or reuse it in their own.

## For Players

In the future, `grug-for-minecraft` will ship ready-to-use releases that work out-of-the-box on Windows, macOS, and Linux.

### Test Keys

grug's test suite runs from inside the game, in a normal build, with no environment variable to set.
Press **R** in any world to run every test. It works the same whether you built grug from source or
installed a release, and the game stays open afterwards so you can press R again.

| Key | What it does |
| :--- | :--- |
| **M** | Toggles the window between its current size and a forced 1280x720. While it's on, the cursor's pixel position is kept in chat, in the same top-left-origin convention `Test.screenshot().equals` takes: open the screen you're writing a test against, hover the pixel you want, and read the coordinate off the latest `Cursor:` line. Press M first, or the coordinates you read won't be the ones CI sees. |
| **R** | Runs every test. It forces 1280x720 for the duration of the run and restores the previous resolution afterwards, so tests always run at the resolution their references were captured at. |

A failing test stops the run there and reports why in your chat. If M had already forced the
resolution, a run leaves it alone, so a second press of M is still the way back.

## For Developers

### Requirements

If you are developing or building `grug-for-minecraft` from source, your system must have the following tools installed and available on your system `PATH`:

*   **Java Development Kit (JDK) 21**: Required to compile the mod loaders (specifically Forge 1.20.6 requires Java 21).
*   **Git**: Used by the Gradle script to clone and fetch the `grug-rs` repository.
*   **Rust**: Required to compile [grug-rs](https://github.com/grug-lang/grug-rs) into a static library.
*   **Python 3**: Required to execute `generate.py`, which auto-generates the C JNI bindings and Java bridge files.
*   **C Compiler**: Required to compile the generated C code and the Rust static library into the final native shared library.

### Running from Source

Use the following Gradle commands to build and run the specific mod loader environments:

| Minecraft Version | Mod Loader | Command |
| :--- | :--- | :--- |
| **1.20.6** | Forge | `./gradlew :loaders:1.20.6-forge:runClient` |
| **1.2.5** | Forge | `./gradlew :loaders:1.2.5-forge:runClient` |
| **Beta 1.7.3** | Ornithe | `./gradlew :loaders:b1.7.3-ornithe:runClient` |
| **Beta 1.7.3** | StationAPI | `./gradlew :loaders:b1.7.3-stationapi:runClient` |
| **Alpha 1.1.2_01** | Ornithe | `./gradlew :loaders:a1.1.2_01-ornithe:runClient` |

### Multiplayer

**grug is singleplayer-only today.** No loader runs the test suite, a mod's `tick`, or a GUI on a
dedicated server, and no loader is exercised in multiplayer by CI. The table below says where each
one actually stands, because "not supported" and "cannot exist" are different answers and only the
first is a piece of work in progress.

| Minecraft Version | Mod Loader | Dedicated server exists? | grug on a dedicated server | `GUI.open` across a connection |
| :--- | :--- | :--- | :--- | :--- |
| **1.20.6** | Forge | yes | no, [#167](https://github.com/grug-lang/grug-for-minecraft/issues/167) | the server side of it already exists (`ServerPlayer.openMenu`), unexercised: [#167](https://github.com/grug-lang/grug-for-minecraft/issues/167) |
| **1.2.5** | Forge | yes, Minecraft 1.2.5 shipped one | no, and grug registers on the client side only: [#166](https://github.com/grug-lang/grug-for-minecraft/issues/166) | no |
| **Beta 1.7.3** | Ornithe | yes | blocks, items, block entities and recipes already register server-side; no runner: [#163](https://github.com/grug-lang/grug-for-minecraft/issues/163) | no, it reports a host-function error: [#164](https://github.com/grug-lang/grug-for-minecraft/issues/164) |
| **Beta 1.7.3** | StationAPI | yes | registration and recipes already run, because StationAPI's common event bus runs on a server; no runner or resource pack: [#165](https://github.com/grug-lang/grug-for-minecraft/issues/165) | no |
| **Alpha 1.1.2_01** | Ornithe | **no.** Ornithe ships no server jar for this version | not reachable | not reachable |

The Alpha row is about this loader rather than about Minecraft: Alpha 1.1.2_01 did have a dedicated
server in the real game, but Ornithe's version metadata for it says `"server": false` and has no
server download at all, so there is no second process for grug to run in. `a1.1.2_01-ornithe` is
therefore client-only by construction rather than by choice.

### Accepting Screenshot References

A screenshot test compares its capture against the numbered PNGs in the mod's `screenshots/<name>/`
directory, so a rendering that none of them represents yet has to be accepted explicitly. Do that
with the `update-goldens` phase instead of copying a file out of the artifacts directory by hand:

```sh
.github/scripts/run-loader.sh <loader-dir-name> update-goldens
```

The tests pass and every unmatched capture is written into the mod's reference directory as the next
number, so the new PNG turns up in `git status` for you to review and commit. Running the reference
itself with the same phase (with `GRUG_REFERENCE=1` and a harness-only `GRUG_MODS_DIR`) accepts the
reference's own pixels rather than the port's.
