# grug for Minecraft

[![Coverage](.github/badges/jacoco.svg)](https://github.com/MyNameIsTrez/grug-mod-loader-for-minecraft/actions/workflows/build.yml)
[![Branches](.github/badges/branches.svg)](https://github.com/MyNameIsTrez/grug-mod-loader-for-minecraft/actions/workflows/build.yml)

This is the grug mod loader for Minecraft.

See [my blog post](https://mynameistrez.github.io/2024/02/29/creating-the-perfect-modding-language.html) for an introduction to the grug modding language.

This mod currently only works in Minecraft 1.20.6, for Minecraft Forge [50.1.0](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.6.html).

## Building this mod

You must be on Linux, and are recommended to use VS Code.

See [ubuntu.com its guide](https://ubuntu.com/tutorials/install-jre) for installing the latest version of Java.

1. Run `git submodule update --init` to clone the subrepositories.
2. Press `Ctrl+Shift+B` to open the task menu, and select `Recompile`.
3. Run `./gradlew runClient` to launch the game, or press `F5` if you want to also attach VS Code's debugger.

See the [Getting Started with Forge](https://docs.minecraftforge.net/en/latest/gettingstarted/) page from Forge's website for more information.

### Troubleshooting

If you get `exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 68`, you'll need to update `gradle/wrapper/gradle-wrapper.properties` its `distributionUrl`, using the table from [this Stack Overflow answer](https://stackoverflow.com/a/75117113/13279557) based on what `java --version` prints for you.

If you get `Error getting artifact: net.minecraftforge:forge:1.20.6-50.1.0_mapped_official_1.20.6:null@jar from  MinecraftUserRepo`, you should try recloning this repository.

## Running the tests

Run `./gradlew jacocoTestReport`.

You can view the coverage results in your browser by opening the `build/reports/jacoco/test/html/index.html` file it generates.

If the coverage results are not updating, you can fix that by deleting the `build/jacoco/test.exec` file that gets generated.

## Running a local server

1. Edit `run/server.properties`, and change the `online-mode=false` to `online-mode=true`.
2. Edit `run/eula.txt` its `eula=false` to `eula=true`, making sure you read the eula.
3. In terminal 1, run `./gradlew runServer`.
4. In terminal 2, run `./gradlew runClient`.
5. In the client, click the `Multiplayer` button in the main menu, and use the `Add Server` button to add a server with `Server Address` value `127.0.0.1` (using `localhost` *does not* work).
6. Join the server.
