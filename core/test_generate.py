#!/usr/bin/env python3
"""Tests the type tables generate.py uses to turn grug types into Java and JNI types.

These tables are the only type-dependent input to the generated GenericHostFunctions trampolines, so
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


class HostWrapperTest(unittest.TestCase):
    def test_a_cleared_exception_is_not_read_as_a_string_result(self):
        wrapper = generate.gen_wrapper("get_block", [], "string")
        # HOST_CHECK reports the exception a host function threw, and CallStaticObjectMethod answers
        # NULL in that case. Reading that NULL with GetStringUTFChars would take the JVM down, so the
        # wrapper must carry on with the empty string instead.
        self.assertIn('res == NULL ? "" : (*env)->GetStringUTFChars(env, res, NULL)', wrapper)
        self.assertIn("if (res != NULL) {", wrapper)

    def test_a_thrown_exception_is_reported_rather_than_swallowed(self):
        # A host function that throws is a defect in grug rather than in the script. The wrapper used
        # to describe and clear the exception, after which the script carried on with a zero value,
        # the test reported PASS, and the log held a stack that named no test (#890). It hands the
        # exception to the state now, for every return type, because even a call that threw still has
        # to answer the VM a value. The name it passes is what the report falls back to when the JVM
        # dropped the exception's stack.
        for return_type in (None, "string", "bool", "number", "id"):
            with self.subTest(return_type=return_type):
                wrapper = generate.gen_wrapper("get_block", [], return_type)
                self.assertIn('HOST_CHECK(env, gst, "get_block");', wrapper)
                # CHECK alone prints and clears, which is the swallow this test exists for.
                self.assertNotIn("CHECK(env);", wrapper)

    def test_an_argument_that_cannot_be_passed_is_the_adapters_failure(self):
        # The NewStringUTF conversions run before the call, so an exception between them is this
        # adapter's own failure and the Java function never ran. Without its own check the call
        # below would be skipped and HOST_CHECK would report the adapter's defect as a host
        # function's, naming the wrong side.
        wrapper = generate.gen_wrapper("print", ["string"], None)
        conversion = wrapper.index("NewStringUTF(env, args[0]._string)")
        argument_check = wrapper.index('HOST_ARG_CHECK(env, gst, "print"')
        call = wrapper.index("CallStaticVoidMethod")
        self.assertLess(conversion, argument_check)
        self.assertLess(argument_check, call)
        # The aborted call still answers a value the VM may use, so a string result is the empty
        # string rather than a null pointer.
        self.assertIn(
            '(union grug_value){._string = ""});',
            generate.gen_wrapper("get_block", ["string"], "string"),
        )


class HostArgumentCheckTest(unittest.TestCase):
    def test_the_macro_reports_and_returns(self):
        # The macro's return is what keeps the pending exception from reaching the call below it,
        # and the report is what names the adapter rather than the function.
        header = os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            "src",
            "main",
            "native",
            "adapter_shared.h",
        )
        with open(header, encoding="utf-8") as source:
            text = source.read()
        self.assertIn("grug_report_host_argument_threw(env, state, function);", text)
        self.assertIn("return aborted;", text)


if __name__ == "__main__":
    unittest.main()
