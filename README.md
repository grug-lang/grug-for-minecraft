# grug for Minecraft

[grug](https://github.com/grug-lang/grug) its primary goal is to serve as a faithful digital preservation format for mods, so that players can continue enjoying the hard work of mod authors for decades to come. If a mod contains copyrighted material or prohibits redistribution, please [open a GitHub issue](https://github.com/grug-lang/grug-for-minecraft/issues) with supporting evidence.

<video src="https://github.com/user-attachments/assets/2a7949ae-643c-4274-9a06-12e528affe97" width="100%" autoplay controls loop muted></video>

In the video above, four different Minecraft environments all hot-reload the same grug file:
* Minecraft 1.20.6 with Forge
* Minecraft Beta 1.7.3 with Ornithe
* Minecraft Beta 1.7.3 with StationAPI
* Minecraft Alpha 1.1.2_01 with Ornithe

To cover the full range of Minecraft versions, `grug-for-minecraft` currently uses Forge, Ornithe, and StationAPI.

> [!NOTE]
> `mod_api.json` is currently frozen. We will not be expanding the API until comprehensive test coverage and Continuous Integration (CI) pipelines are fully established.

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
