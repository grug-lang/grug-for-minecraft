#!/usr/bin/env python3
"""Tests that the 1.20.6 Forge classes a dedicated server loads name no client-only class (#1314).

The universal jar crashed a Forge 1.20.6 dedicated server at boot with
NoClassDefFoundError net/minecraft/client/gui/components/Renderable, raised while the verifier
linked ForgeAdapter: the server's adapter extended the client's, whose method bodies pass client
types around, and the verifier loads a class to check one is assignable to another. The development
runServer hides it because its classpath holds the client classes. So the client half lives in
ClientForgeAdapter, which only a client loads, and the classes below stay free of client packages.
"""

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
FORGE = (
    REPO
    / "loaders"
    / "1.20.6-forge"
    / "src"
    / "main"
    / "java"
    / "net"
    / "grug"
    / "minecraft"
    / "forge"
)

# What a dedicated server loads from this loader: the adapter base both sides share, the server's
# own adapter, and the control channel and run its adapter builds.
SERVER_PATH = [
    "ForgeAdapter.java",
    "ServerForgeAdapter.java",
    "GrugControlForge.java",
    "GrugServerRun.java",
]

# The game's client packages. Forge's own client package holds client events and so is one too.
CLIENT_PACKAGES = re.compile(
    r"\b(?:net\.minecraft\.client|net\.minecraftforge\.client|com\.mojang\.blaze3d)\b"
)


def strip_comments(source):
    """The source without its comments, which may name a client class to say why it is avoided."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    return re.sub(r"//[^\n]*", "", source)


def client_references(source):
    """The client package names that the code of one Java file mentions."""
    return sorted(set(CLIENT_PACKAGES.findall(strip_comments(source))))


class ClientReferencesTest(unittest.TestCase):
    def test_finds_an_import(self):
        self.assertEqual(
            ["net.minecraft.client"], client_references("import net.minecraft.client.Minecraft;\n")
        )

    def test_finds_a_qualified_name_in_code(self):
        self.assertEqual(
            ["net.minecraft.client"],
            client_references("var mc = (net.minecraft.client.Minecraft) x;\n"),
        )

    def test_finds_forges_client_package_and_blaze3d(self):
        source = "import net.minecraftforge.client.event.X;\nimport com.mojang.blaze3d.Y;\n"
        self.assertEqual(
            ["com.mojang.blaze3d", "net.minecraftforge.client"], client_references(source)
        )

    def test_ignores_comments(self):
        source = "/** net.minecraft.client.Minecraft */\n// com.mojang.blaze3d\nclass A {}\n"
        self.assertEqual([], client_references(source))

    def test_server_path_classes_exist(self):
        for name in SERVER_PATH:
            self.assertTrue((FORGE / name).is_file(), name)


class ServerPathTest(unittest.TestCase):
    def test_no_server_path_class_names_a_client_class(self):
        for name in SERVER_PATH:
            with self.subTest(name):
                self.assertEqual([], client_references((FORGE / name).read_text()))

    def test_the_client_half_exists_and_does_name_client_classes(self):
        # If the client half stopped naming client classes, the split above would be vacuous.
        client = (FORGE / "ClientForgeAdapter.java").read_text()
        self.assertIn("net.minecraft.client", client_references(client))

    def test_the_server_adapter_does_not_extend_the_client_one(self):
        server = strip_comments((FORGE / "ServerForgeAdapter.java").read_text())
        self.assertRegex(server, r"class ServerForgeAdapter extends ForgeAdapter\b")

    def test_the_factory_never_merges_the_two_adapters(self):
        # A conditional expression of the two types makes the verifier load both classes.
        base = strip_comments((FORGE / "ForgeAdapter.java").read_text())
        self.assertNotRegex(base, r"\?\s*new ServerForgeAdapter\(\)\s*:")
        self.assertNotRegex(base, r"\?\s*new ClientForgeAdapter\(\)\s*:")
        self.assertNotRegex(base, r"new ServerForgeAdapter\(\)\s*:\s*new ClientForgeAdapter\(\)")


if __name__ == "__main__":
    unittest.main()
