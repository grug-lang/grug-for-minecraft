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

## Recreating a mod

A mod that recreates another mod carries a `recreation` block in its `about.json`. It records what is being recreated, how to install and run the reference, and how captures are compared, so the recreation can be verified against the real thing instead of against a memory of it.

The block is self-contained and declarative. `reference` pins the source revision, `artifact` pins the exact bytes by `sha256` (the hash is the authority, not the url), `runtime` names the environment the reference runs in, `install` says how the reference is installed, `capture` fixes the viewport, camera, tick count and diff tolerance, and `deviations` lists the differences that are intentional so a reader does not "fix" them.

`install` is a tagged union keyed by how the reference is installed, not a list of steps: `mods_folder`, `jarmod`, `coremod`, `javaagent`, `tweak_class`, `launcher_profile`, `server_plugin`, `datapack`, `installer` and `source_build`, each with the files, libraries, config and JVM arguments it needs. Nothing executable is stored inline; a hash-pinned artifact is the only source of bytes.

One rule jsonschema cannot express is enforced by `about_schema.py`: `artifact.mirrors` may only be non-empty when `artifact.redistribution.allowed` is true, because a mirror redistributes the artifact.

### One test, both sides

`Test.is_reference()` is true only in the reference run, which sets `GRUG_REFERENCE=1` (or `-Dgrug.reference=1`) and loads no grug port. A recreation test branches on it for block names and setup, and asserts shared state (vanilla blocks, chest contents, dropped item entities) that both sides must produce, so one test file covers the port on every loader and the real mod on its own.

### Two tiers

Verifying the port needs only the port and the committed goldens, and runs on every push. Regenerating the goldens is the only part that needs the reference artifact, and it is a separate manual job (`Regenerate Goldens`), so losing the artifact degrades regenerating rather than verifying.

The `artifact` tier of that workflow downloads the pinned artifact and checks it against the pinned hash before anything uses it:

```sh
python3 recreation.py plan mods/buildcraft/about.json
python3 recreation.py verify mods/buildcraft/about.json --output reference-artifact.jar
```

`recreation.py plan` prints the runtime, the pinned reference, the install, and the capture settings, so a reviewer can see what running the reference would involve without running it.

## Long-term Plans

This repository aims to become a thoroughly vetted, centralized host for grug mods via the `mods/` directory at its root, rather than depending on external platforms like Modrinth or CurseForge. Support for downloading mods through APIs like Modrinth's may be added eventually, but isn't a priority given the `mods/` directory already covers hosting, discovery, and vetting on its own. Because `mod_api.json` restricts what mods can do, CI will be able to auto-merge pull requests that only modify grug code and come from a GitHub account listed as an author in the mod's `about.json`, while reviewers focus on resource files.

An in-game mod portal will let players browse, install, and update mods without restarting the game, get notified of new releases and changelogs, and even write new mods from scratch using a built-in text editor and Scratch-like blocks interface. Since `mod_api.json` specifies which versions of the game each function is available in (Minecraft Alpha, for example, didn't have a hunger system), the portal will also be able to disable grug files that call functions unavailable in the current version, using `e""` entity strings to detect when disabling one file cascades into disabling others that depend on it. Keeping mods centralized in this repository also means the portal can give players a way to freely copy-paste and search real-world grug code across every mod, whether to learn from it or reuse it in their own.

## For Players

In the future, `grug-for-minecraft` will ship ready-to-use releases that work out-of-the-box on Windows, macOS, and Linux.

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

### Test Keys

| Key | What it does |
| :--- | :--- |
| **M** | Toggles the window between its current size and a forced 1280x720. While it's on, the cursor's pixel position is kept in chat, in the same top-left-origin convention `Test.assert_screenshot_equals` takes: open the screen you're writing a test against, hover the pixel you want, and read the coordinate off the latest `Cursor:` line. Press M first, or the coordinates you read won't be the ones CI sees. |
| **R** | Runs every test. It forces 1280x720 for the duration of the run and restores the previous resolution afterwards, so tests always run at the resolution their references were captured at. |
