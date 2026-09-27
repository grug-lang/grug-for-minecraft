# grug for Minecraft · ![Coverage](.github/badges/coverage.svg) ![Branches](.github/badges/branches.svg)

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
| **Beta 1.7.3** | Ornithe | `./gradlew :loaders:b1.7.3-ornithe:runClient` |
| **Beta 1.7.3** | StationAPI | `./gradlew :loaders:b1.7.3-stationapi:runClient` |
| **Alpha 1.1.2_01** | Ornithe | `./gradlew :loaders:a1.1.2_01-ornithe:runClient` |

### Screenshot Tests

Alongside the logic tests, a test can assert that a screen looks exactly right, by comparing a
rectangle of the rendered frame against reference PNGs committed next to it. A test does this with
`Test.assert_screenshot_equals(reference, x1, y1, x2, y2)`, where the rectangle is in pixels with the
origin at the top left of the window.

Graphics tests are **singleplayer-only**. That limit comes from the game rather than from grug:
`GUI.open` already refuses to open a screen in multiplayer. Every supported loader implements the
screenshot functions, but a test should still guard them with `Test.graphics_tests_supported()`.

`reference` names a **directory** of numbered PNGs (`1.png`, `2.png`, ...), not a single file, and the
assertion passes if the capture is pixel-identical to *any* of them. The same UI renders differently
on each Minecraft version and platform (fonts, item sprites, GUI scaling), so every accepted
appearance gets its own file and one shared `.grug` test stays green on all of them. Adding a new
accepted rendering is a one-file pull request.

A mod's `screenshots/` tree is checked: every directory under it must be either a **group** (only
subdirectories) or a **reference** (only `1.png`, `2.png`, ... with no gaps), never both, and every
file must be a lowercase `.png` named after a positive number. A test run refuses to start on a
violation, so an author sees every problem locally; CI fails the same way, because it runs the exact
same check rather than a second implementation.

Screenshot tests are captured at a fixed **1280x720**, and each comparison is exact: a single
differing pixel from every reference fails the test. CI already runs at that resolution on a pinned
`ubuntu-24.04` image so that Mesa and the font stack stay identical between runs.

While a test run has a screen open, the mouse cursor is parked in a corner of the window, so the
slot-hover highlight (and any tooltip) can't leak into a capture. The cursor is never drawn into the
framebuffer, so this only removes that incidental state.

To find the coordinates for a new test, on any loader:

| Key | What it does |
| :--- | :--- |
| **M** | Toggles the window between its current size and a forced 1280x720. While it's on, the cursor's pixel position is kept in chat, in the same top-left-origin convention `Test.assert_screenshot_equals` takes: open the screen you're writing a test against, hover the pixel you want, and read the coordinate off the latest `Cursor:` line. Press M first, or the coordinates you read won't be the ones CI sees. |
| **R** | Runs every test. It forces 1280x720 for the duration of the run and restores the previous resolution afterwards, so tests always run at the resolution their references were captured at. |

These are letters rather than function keys so they can't collide with a vanilla default binding,
and so they don't need an Fn key on laptops. `M` and `R` are unbound in every vanilla version.

Beware that the game draws its GUI scaled: it lays the screen out in a fixed virtual space and
scales that up to the window, so at 1280x720 everything is drawn 3x. The coordinates the M readout
reports are real screen pixels and are what the crop should use; the scaling is just a reminder that
they won't match the numbers in the screen's own layout code.

A brand-new test needs no reference directory: its first run creates the directory and writes the
capture as `1.png`, telling you it did. From then on the test compares against that reference.

To add a reference for a new platform or version, run the test and let it fail: the capture is written
to `grug-screenshot-artifacts/<reference path>/<n>.png`, where `<n>` is the number it would take in
the reference directory, along with a `diff.png` that highlights the pixels differing from the
closest reference in red over a faded copy of the capture (the same rendering ImageMagick's `compare`
produces, computed in-process). CI uploads that directory, which is how missing references for a
platform get collected. To accept the capture, copy it into the reference directory under the same
name and re-run.

Because each comparison is exact, references are tied to the rendering stack. Generate them on the
same platform CI uses (`ubuntu-24.04` with the same Xvfb display) or they will not match. After an
intentional UI change, delete the directory and regenerate it on each accepted platform, or the test
will keep passing against the stale references.
