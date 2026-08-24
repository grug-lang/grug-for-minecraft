#!/usr/bin/env python3
"""
Reads mod_api.json and emits adapter_generated.c: JNI method-id caching,
host_fn wrappers, and grug host-function/method registration, for every
entry in "host_functions" and every method of every "classes" entry.

Fixed lifecycle code (grug_init, entity create/destroy, compile/update,
runtime error handling) lives in adapter.c and is untouched by this script.

Convention: every "number"-typed value crosses the JNI boundary as a
Java double, matching grug_value._number (a C double) exactly. Java-side
methods that logically want an int or float cast internally.
"""

import json
import sys


def grug_type_name(type_field):
    return type_field if isinstance(type_field, str) else type_field["name"]


def jni_descriptor(type_name):
    if type_name == "number":
        return "D"
    if type_name == "string":
        return "Ljava/lang/String;"
    if type_name == "bool":
        return "Z"
    return "J"  # any other name is an opaque id (entity/class instance)


def value_union_field(type_name):
    if type_name == "number":
        return "_number"
    if type_name == "string":
        return "_string"
    if type_name == "bool":
        return "_bool"
    return "_id"


def jni_call_suffix(type_name):
    if type_name == "number":
        return "Double"
    if type_name == "bool":
        return "Boolean"
    return "Long"  # ids; strings are handled separately (CallStaticObjectMethod)


def c_arg_expr(type_name, index):
    if type_name == "number":
        return f"(jdouble)args[{index}]._number"
    if type_name == "string":
        return f"arg_str_{index}"
    if type_name == "bool":
        return f"(jboolean)args[{index}]._bool"
    return f"(jlong)args[{index}]._id"


def build_signature(param_types, return_type):
    params_desc = "".join(jni_descriptor(t) for t in param_types)
    ret_desc = "V" if return_type is None else jni_descriptor(return_type)
    return f"({params_desc}){ret_desc}"


def gen_wrapper(java_name, param_types, return_type):
    c_name = f"host_{java_name}"
    jm = f"jm_{java_name}"
    lines = [
        f"union grug_value {c_name}(void* gst, const union grug_value args[]) {{",
        "    (void)gst;",
        "    JNIEnv* env; FILL_ENV(env);",
    ]

    string_indices = [i for i, t in enumerate(param_types) if t == "string"]
    for i in string_indices:
        lines.append(
            f"    jstring arg_str_{i} = (*env)->NewStringUTF(env, args[{i}]._string);"
        )

    call_args = ", ".join(c_arg_expr(t, i) for i, t in enumerate(param_types))
    full_args = f"env, game_functions_class, {jm}" + (
        f", {call_args}" if call_args else ""
    )

    if return_type is None:
        lines.append(f"    (*env)->CallStaticVoidMethod({full_args});")
        lines.append("    CHECK(env);")
        result_expr = "(union grug_value){0}"
    elif return_type == "string":
        lines.append(
            f"    jstring res = (jstring)(*env)->CallStaticObjectMethod({full_args});"
        )
        lines.append("    CHECK(env);")
        lines.append(
            "    const char* res_utf = (*env)->GetStringUTFChars(env, res, NULL);"
        )
        lines.append(
            "    char* res_owned = strdup(res_utf); // intentionally leaked: grug borrows this pointer"
        )
        lines.append("    (*env)->ReleaseStringUTFChars(env, res, res_utf);")
        lines.append("    (*env)->DeleteLocalRef(env, res);")
        result_expr = "(union grug_value){._string = res_owned}"
    else:
        jni_type = {"number": "jdouble", "bool": "jboolean"}.get(return_type, "jlong")
        suffix = jni_call_suffix(return_type)
        lines.append(
            f"    {jni_type} res = (*env)->CallStatic{suffix}Method({full_args});"
        )
        lines.append("    CHECK(env);")
        field = value_union_field(return_type)
        cast = {"_number": "(double)res", "_bool": "(bool)res", "_id": "(uint64_t)res"}[
            field
        ]
        result_expr = f"(union grug_value){{.{field} = {cast}}}"

    for i in string_indices:
        lines.append(f"    (*env)->DeleteLocalRef(env, arg_str_{i});")

    lines.append(f"    return {result_expr};")
    lines.append("}")
    return "\n".join(lines)


def collect_functions(mod_api):
    functions = []

    for name, decl in mod_api.get("host_functions", {}).items():
        param_types = [grug_type_name(p["type"]) for p in decl.get("parameters", [])]
        return_type = (
            grug_type_name(decl["return_type"]) if "return_type" in decl else None
        )
        functions.append(
            {
                "java_name": name,
                "kind": "host_fn",
                "grug_name": name,
                "param_types": param_types,
                "return_type": return_type,
            }
        )

    for class_name, class_decl in mod_api.get("classes", {}).items():
        for method_name, decl in class_decl.get("methods", {}).items():
            param_types = [class_name] + [
                grug_type_name(p["type"]) for p in decl.get("parameters", [])
            ]
            return_type = (
                grug_type_name(decl["return_type"]) if "return_type" in decl else None
            )
            functions.append(
                {
                    "java_name": f"{class_name}_{method_name}",
                    "kind": "method",
                    "grug_class": class_name,
                    "grug_method": method_name,
                    "param_types": param_types,
                    "return_type": return_type,
                }
            )

    return functions


def generate(mod_api):
    functions = collect_functions(mod_api)
    out = [
        "// AUTO-GENERATED by generate.py from mod_api.json. Do not edit by hand.",
        '#include "adapter_shared.h"',
        "#include <string.h>",
        "",
    ]

    for fn in functions:
        out.append(f"static jmethodID jm_{fn['java_name']};")
    out.append("")

    for fn in functions:
        out.append(gen_wrapper(fn["java_name"], fn["param_types"], fn["return_type"]))
        out.append("")

    out.append(
        "void resolve_generated_method_ids(JNIEnv* env, jclass game_functions_class) {"
    )
    for fn in functions:
        sig = build_signature(fn["param_types"], fn["return_type"])
        out.append(
            f'    jm_{fn["java_name"]} = (*env)->GetStaticMethodID(env, game_functions_class, "{fn["java_name"]}", "{sig}");'
        )
    out.append("}")
    out.append("")

    out.append("void register_generated_host_fns(void* state) {")
    for fn in functions:
        c_name = f"host_{fn['java_name']}"
        if fn["kind"] == "host_fn":
            out.append(
                f'    grug_register_host_fn(state, "{fn["grug_name"]}", {c_name});'
            )
        else:
            out.append(
                f'    grug_register_method(state, "{fn["grug_class"]}", "{fn["grug_method"]}", {c_name});'
            )
    out.append("}")

    return "\n".join(out)


def main():
    if len(sys.argv) != 3:
        print(
            "usage: generate.py <mod_api.json> <output adapter_generated.c>",
            file=sys.stderr,
        )
        sys.exit(1)
    with open(sys.argv[1]) as f:
        mod_api = json.load(f)
    with open(sys.argv[2], "w") as f:
        f.write(generate(mod_api))


if __name__ == "__main__":
    main()
