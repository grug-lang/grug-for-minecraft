#!/usr/bin/env python3
"""Tests the type tables generate.py uses to turn grug types into Java and JNI types.

These tables are the only type-dependent input to the generated GenericGameFunctions trampolines, so
a bug there breaks every combination that contains one type rather than a single bad permutation.
Testing the tables directly is exhaustive over that failure domain, where exercising the trampolines'
4^n permutations is not.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import generate


class TypeTableTest(unittest.TestCase):
    def test_jni_descriptor(self):
        self.assertEqual(generate.jni_descriptor("number"), "D")
        self.assertEqual(generate.jni_descriptor("bool"), "Z")
        for type_name in ("string", "resource", "entity"):
            self.assertEqual(generate.jni_descriptor(type_name), "Ljava/lang/String;")
        # Everything else is an opaque handle, passed as a jlong.
        for type_name in ("id", "level", "player"):
            self.assertEqual(generate.jni_descriptor(type_name), "J")

    def test_java_type(self):
        self.assertEqual(generate.java_type("number"), "double")
        self.assertEqual(generate.java_type("bool"), "boolean")
        for type_name in ("string", "resource", "entity"):
            self.assertEqual(generate.java_type(type_name), "String")
        self.assertEqual(generate.java_type("id"), "long")

    def test_java_class_type(self):
        self.assertEqual(generate.java_class_type("number"), "Double")
        self.assertEqual(generate.java_class_type("bool"), "Boolean")
        self.assertEqual(generate.java_class_type("string"), "String")
        self.assertEqual(generate.java_class_type("id"), "Long")

    def test_c_type(self):
        self.assertEqual(generate.c_type("number"), "jdouble")
        self.assertEqual(generate.c_type("bool"), "jboolean")
        self.assertEqual(generate.c_type("string"), "jstring")
        self.assertEqual(generate.c_type("id"), "jlong")

    def test_build_signature(self):
        self.assertEqual(generate.build_signature([], None), "()V")
        self.assertEqual(generate.build_signature(["number"], "bool"), "(D)Z")
        self.assertEqual(
            generate.build_signature(["id", "string"], "number"),
            "(JLjava/lang/String;)D",
        )
        self.assertEqual(generate.build_signature(["bool", "number", "id"], None), "(ZDJ)V")

    def test_grug_type_name(self):
        self.assertEqual(generate.grug_type_name("number"), "number")
        self.assertEqual(generate.grug_type_name({"name": "string"}), "string")


class GenericBridgeExpansionTest(unittest.TestCase):
    def bridge_method_count(self, generic_count):
        function = {
            "java_name": "print",
            "used_generics": [f"T{i}" for i in range(generic_count)],
            "param_types": [f"T{i}" for i in range(generic_count)],
            "param_names": [f"arg{i}" for i in range(generic_count)],
            "return_type": None,
        }
        return generate.generate_generic_java_bridge([function]).count("public static")

    def test_expands_every_type_combination(self):
        for generic_count in (1, 2, 3):
            with self.subTest(generic_count=generic_count):
                self.assertEqual(
                    self.bridge_method_count(generic_count),
                    len(generate.BASE_TYPES) ** generic_count,
                )


if __name__ == "__main__":
    unittest.main()
