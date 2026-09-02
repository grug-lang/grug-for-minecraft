#!/usr/bin/env python3
"""
Reads mod_api.json and emits:
1. adapter_generated.c: JNI method-id caching, host_fn wrappers,
   grug host-function/method registration, and JNI entry points for
   strongly-typed ExportFns calls.
2. ExportFns.java: Strong-typed Java wrappers for every entity export function.
3. GenericGameFunctions.java: Auto-generated Java overloads to prevent autoboxing over JNI.
"""

import itertools
import json
import os
import sys
from typing import Any, Dict, List, Optional, Tuple, Union

BASE_TYPES: List[str] = ["number", "bool", "string", "id"]


def jni_mangle(name: str) -> str:
    return name.replace("_", "_1")


def grug_type_name(type_field: Union[str, Dict[str, Any]]) -> str:
    return type_field if isinstance(type_field, str) else type_field["name"]


def jni_descriptor(type_name: str) -> str:
    if type_name == "number":
        return "D"
    if type_name in ("string", "resource", "entity"):
        return "Ljava/lang/String;"
    if type_name == "bool":
        return "Z"
    return "J"  # opaque handle / ID


def java_type(type_name: str) -> str:
    if type_name == "number":
        return "double"
    if type_name in ("string", "resource", "entity"):
        return "String"
    if type_name == "bool":
        return "boolean"
    return "long"


def java_class_type(type_name: str) -> str:
    if type_name == "number":
        return "Double"
    if type_name == "bool":
        return "Boolean"
    if type_name in ("string", "resource", "entity"):
        return "String"
    return "Long"


def c_type(type_name: str) -> str:
    if type_name == "number":
        return "jdouble"
    if type_name in ("string", "resource", "entity"):
        return "jstring"
    if type_name == "bool":
        return "jboolean"
    return "jlong"


def value_union_field(type_name: str) -> str:
    if type_name == "number":
        return "_number"
    if type_name in ("string", "resource", "entity"):
        return "_string"
    if type_name == "bool":
        return "_bool"
    return "_id"


def get_enum_name(t: str) -> str:
    if t == "number":
        return "GRUG_TYPE_ENUM_NUMBER"
    if t == "bool":
        return "GRUG_TYPE_ENUM_BOOL"
    if t == "string":
        return "GRUG_TYPE_ENUM_STRING"
    return "GRUG_TYPE_ENUM_ID"


def jni_call_suffix(type_name: str) -> str:
    if type_name == "number":
        return "Double"
    if type_name == "bool":
        return "Boolean"
    if type_name in ("string", "resource", "entity"):
        return "Object"
    return "Long"


def c_arg_expr(type_name: str, index: int) -> str:
    if type_name == "number":
        return f"(jdouble)args[{index}]._number"
    if type_name in ("string", "resource", "entity"):
        return f"arg_str_{index}"
    if type_name == "bool":
        return f"(jboolean)args[{index}]._bool"
    return f"(jlong)args[{index}]._id"


def build_signature(param_types: List[str], return_type: Optional[str]) -> str:
    params_desc = "".join(jni_descriptor(t) for t in param_types)
    ret_desc = "V" if return_type is None else jni_descriptor(return_type)
    return f"({params_desc}){ret_desc}"


def gen_wrapper(
    java_name: str,
    param_types: List[str],
    return_type: Optional[str],
    used_generics: Optional[List[str]] = None,
    combo: Optional[Tuple[str, ...]] = None,
) -> str:
    used_generics = used_generics or []
    combo = combo or tuple()

    concrete_map = dict(zip(used_generics, combo))

    resolved_params = [concrete_map.get(t, t) for t in param_types]
    resolved_return = (
        concrete_map.get(return_type, return_type) if return_type else None
    )

    suffix = "_" + "_".join(combo) if combo else ""
    c_name = f"host_{java_name}{suffix}"
    jm = f"jm_{java_name}{suffix}"

    lines = [
        f"static union grug_value {c_name}(void* gst, const union grug_value args[]) {{",
        "    (void)gst;",
        "    JNIEnv* env; FILL_ENV(env);",
    ]

    string_indices = [
        i
        for i, t in enumerate(resolved_params)
        if t in ("string", "resource", "entity")
    ]
    for i in string_indices:
        lines.append(
            f"    jstring arg_str_{i} = (*env)->NewStringUTF(env, args[{i}]._string);"
        )

    call_args = ", ".join(c_arg_expr(t, i) for i, t in enumerate(resolved_params))
    full_args = f"env, game_functions_class, {jm}" + (
        f", {call_args}" if call_args else ""
    )

    if resolved_return is None:
        lines.append(f"    (*env)->CallStaticVoidMethod({full_args});")
        lines.append("    CHECK(env);")
        result_expr = "(union grug_value){0}"
    elif resolved_return in ("string", "resource", "entity"):
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
        jni_type = {"number": "jdouble", "bool": "jboolean"}.get(
            resolved_return, "jlong"
        )
        c_suffix = jni_call_suffix(resolved_return)
        lines.append(
            f"    {jni_type} res = (*env)->CallStatic{c_suffix}Method({full_args});"
        )
        lines.append("    CHECK(env);")
        field = value_union_field(resolved_return)
        cast = {"_number": "(double)res", "_bool": "(bool)res", "_id": "(uint64_t)res"}[
            field
        ]
        result_expr = f"(union grug_value){{.{field} = {cast}}}"

    for i in string_indices:
        lines.append(f"    (*env)->DeleteLocalRef(env, arg_str_{i});")

    lines.append(f"    return {result_expr};")
    lines.append("}")
    return "\n".join(lines)


def gen_factory(java_name: str, used_generics: List[str]) -> str:
    lines = [
        f"static grug_host_fn_t reg_{java_name}(const struct grug_type* generics) {{"
    ]

    def build_tree(depth: int, prefix: str = "") -> str:
        if depth == len(used_generics):
            return f"    return host_{java_name}_{prefix.strip('_')};\n"

        out = ""
        for bt in BASE_TYPES:
            enum_name = get_enum_name(bt)
            condition = f"generics[{depth}].type == {enum_name}"
            # Fallback to ID on the last branch to ensure C always returns a value
            if bt == "id":
                out += f"    /* fallback id */\n"
                out += build_tree(depth + 1, prefix + "id_")
            else:
                out += f"    if ({condition}) {{\n"
                out += "    " + build_tree(depth + 1, prefix + bt + "_").replace(
                    "\n", "\n    "
                )
                out += f"    }}\n"
        return out

    lines.append(build_tree(0))
    lines.append("}")
    return "\n".join(lines)


def collect_functions(mod_api: Dict[str, Any]) -> List[Dict[str, Any]]:
    functions: List[Dict[str, Any]] = []
    for name, decl in mod_api.get("host_functions", {}).items():
        param_types = [grug_type_name(p["type"]) for p in decl.get("parameters", [])]
        return_type = (
            grug_type_name(decl["return_type"]) if "return_type" in decl else None
        )

        # We also need the original parameter names for generating the Java bridge
        param_names = [p["name"] for p in decl.get("parameters", [])]

        functions.append(
            {
                "java_name": name,
                "kind": "host_fn",
                "grug_name": name,
                "param_types": param_types,
                "param_names": param_names,
                "return_type": return_type,
                "used_generics": decl.get("used_generics", []),
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

            param_names = ["self"] + [p["name"] for p in decl.get("parameters", [])]

            functions.append(
                {
                    "java_name": f"{class_name}_{method_name}",
                    "kind": "method",
                    "grug_class": class_name,
                    "grug_method": method_name,
                    "param_types": param_types,
                    "param_names": param_names,
                    "return_type": return_type,
                    "used_generics": class_decl.get("used_generics", []),
                }
            )

    return functions


def collect_exports(mod_api: Dict[str, Any]) -> List[Dict[str, Any]]:
    exports: List[Dict[str, Any]] = []
    for entity_name, entity_decl in mod_api.get("entities", {}).items():
        for fn_decl in entity_decl.get("export_functions", []):
            fn_name = fn_decl["name"]
            param_list = [
                (p["name"], grug_type_name(p["type"]))
                for p in fn_decl.get("parameters", [])
            ]
            exports.append(
                {
                    "entity_type": entity_name,
                    "fn_name": fn_name,
                    "parameters": param_list,
                }
            )
    return exports


def generate_java_exports(exports: List[Dict[str, Any]]) -> str:
    lines = [
        "package net.grug.minecraft.grug;",
        "",
        "public class ExportFns {",
    ]
    for exp in exports:
        entity_type = exp["entity_type"]
        fn_name = exp["fn_name"]
        params = exp["parameters"]
        method_name = f"{entity_type}_{fn_name}"
        native_name = f"native_{method_name}"

        java_param_decls = ["long entityHandle"]
        call_args = ["Grug.statePtr", "entityHandle"]

        for p_name, p_type in params:
            java_param_decls.append(f"{java_type(p_type)} {p_name}")
            call_args.append(p_name)

        param_str = ", ".join(java_param_decls)
        call_str = ", ".join(call_args)

        lines.append(f"    public static boolean {method_name}({param_str}) {{")
        lines.append(f"        return {native_name}({call_str});")
        lines.append("    }")
        lines.append("")

        native_param_decls = ["long statePtr", "long entityHandle"]
        for p_name, p_type in params:
            native_param_decls.append(f"{java_type(p_type)} {p_name}")

        native_param_str = ", ".join(native_param_decls)
        lines.append(
            f"    private static native boolean {native_name}({native_param_str});"
        )
        lines.append("")

    lines.append("}")
    return "\n".join(lines)


def generate_c_exports(exports: List[Dict[str, Any]]) -> str:
    lines: List[str] = []
    for exp in exports:
        entity_type = exp["entity_type"]
        fn_name = exp["fn_name"]
        params = exp["parameters"]
        raw_method_name = f"native_{entity_type}_{fn_name}"
        c_func_name = (
            f"Java_net_grug_minecraft_grug_ExportFns_{jni_mangle(raw_method_name)}"
        )
        c_params = [
            "JNIEnv *env",
            "jclass clazz",
            "jlong statePtr",
            "jlong entityHandle",
        ]
        for p_name, p_type in params:
            c_params.append(f"{c_type(p_type)} {p_name}")

        param_str = ", ".join(c_params)
        lines.append(f"JNIEXPORT jboolean JNICALL {c_func_name}({param_str}) {{")
        lines.append(
            f'    jstring j_entity_type = (*env)->NewStringUTF(env, "{entity_type}");'
        )
        lines.append(f'    jstring j_fn_name = (*env)->NewStringUTF(env, "{fn_name}");')
        lines.append(
            "    jlong fn_id = Java_net_grug_minecraft_grug_Grug_nativeGetExportFnId(env, clazz, statePtr, j_entity_type, j_fn_name);"
        )
        lines.append("    (*env)->DeleteLocalRef(env, j_entity_type);")
        lines.append("    (*env)->DeleteLocalRef(env, j_fn_name);")
        lines.append("    if (fn_id == -1L) return JNI_FALSE;")
        lines.append("")

        num_args = len(params)
        if num_args > 0:
            lines.append(f"    union grug_value args[{num_args}];")
            for i, (p_name, p_type) in enumerate(params):
                field = value_union_field(p_type)
                if p_type in ("string", "resource", "entity"):
                    lines.append(
                        f"    const char* c_str_{i} = (*env)->GetStringUTFChars(env, {p_name}, NULL);"
                    )
                    lines.append(f"    args[{i}].{field} = c_str_{i};")
                else:
                    lines.append(
                        f"    args[{i}].{field} = ({'double' if p_type == 'number' else 'uint64_t'}){p_name};"
                    )

        lines.append("")
        args_ptr = "args" if num_args > 0 else "NULL"
        lines.append(
            f"    bool res = grug_call_export_fn((void*)(intptr_t)statePtr, (void*)(intptr_t)entityHandle, (uint64_t)fn_id, {args_ptr}, {num_args});"
        )
        for i, (p_name, p_type) in enumerate(params):
            if p_type in ("string", "resource", "entity"):
                lines.append(
                    f"    (*env)->ReleaseStringUTFChars(env, {p_name}, c_str_{i});"
                )

        lines.append("    return res ? JNI_TRUE : JNI_FALSE;")
        lines.append("}")
        lines.append("")

    return "\n".join(lines)


def generate_generic_java_bridge(functions: List[Dict[str, Any]]) -> str:
    lines = [
        "// AUTO-GENERATED by generate.py from mod_api.json. Do not edit by hand.",
        "package net.grug.minecraft.grug;",
        "",
        "public class GenericGameFunctions {",
    ]

    for fn in functions:
        used_generics = fn["used_generics"]
        if not used_generics:
            continue

        base_name = fn["java_name"]
        combinations = list(itertools.product(BASE_TYPES, repeat=len(used_generics)))

        for combo in combinations:
            concrete_map: Dict[str, str] = dict(zip(used_generics, combo))
            param_types: List[str] = fn["param_types"]
            return_type: Optional[str] = fn["return_type"]
            param_names: List[str] = fn["param_names"]

            resolved_params: List[str] = [concrete_map.get(t, t) for t in param_types]
            resolved_return: Optional[str] = (
                concrete_map.get(return_type, return_type)
                if return_type is not None
                else None
            )

            java_param_list = [
                f"{java_type(t)} {name}"
                for t, name in zip(resolved_params, param_names)
            ]
            call_args = ", ".join(param_names)

            ret_type = (
                java_type(resolved_return) if resolved_return is not None else "void"
            )
            suffix = "_" + "_".join(combo)
            method_name = f"{base_name}{suffix}"

            lines.append(
                f"    public static {ret_type} {method_name}({', '.join(java_param_list)}) {{"
            )

            if resolved_return:
                cast_type = java_class_type(resolved_return)
                lines.append(
                    f"        return ({cast_type}) GameFunctions.{base_name}({call_args});"
                )
            else:
                lines.append(f"        GameFunctions.{base_name}({call_args});")

            lines.append("    }")
            lines.append("")

    lines.append("}")
    return "\n".join(lines)


def generate(mod_api: Dict[str, Any]) -> str:
    functions = collect_functions(mod_api)
    exports = collect_exports(mod_api)

    out = [
        "// AUTO-GENERATED by generate.py from mod_api.json. Do not edit by hand.",
        '#include "adapter_shared.h"',
        "#include <string.h>",
        "",
        "enum grug_type_enum {",
        "    GRUG_TYPE_ENUM_VOID,",
        "    GRUG_TYPE_ENUM_BOOL,",
        "    GRUG_TYPE_ENUM_NUMBER,",
        "    GRUG_TYPE_ENUM_STRING,",
        "    GRUG_TYPE_ENUM_ID,",
        "    GRUG_TYPE_ENUM_RESOURCE,",
        "    GRUG_TYPE_ENUM_ENTITY",
        "};",
        "",
        "struct grug_type {",
        "    uint32_t type;",
        "    union {",
        "        struct {",
        "            char* name;",
        "            struct grug_type* generics;",
        "            size_t generics_len;",
        "        } id;",
        "        char* resource_extension;",
        "        char* entity_type;",
        "    } data;",
        "};",
        "",
        "extern uint64_t grug_get_on_fn_id(void* state, const char* entity_type, const char* on_fn_name);",
        "extern bool grug_call_export_fn(void* state, void* entity_handle, uint64_t on_fn_id, const union grug_value* args, size_t args_len);",
        "extern jlong Java_net_grug_minecraft_grug_Grug_nativeGetExportFnId(JNIEnv *env, jclass clazz, jlong statePtr, jstring entityType, jstring fnName);",
        "",
        "typedef union grug_value (*grug_host_fn_t)(void *state, const union grug_value args[]);",
        "typedef grug_host_fn_t (*grug_generic_host_fn_t)(const struct grug_type *generics);",
        "extern void grug_register_generic_fn(void *state, const char *name, grug_generic_host_fn_t fn);",
        "extern void grug_register_generic_method(void *state, const char *class_name, const char *method_name, grug_generic_host_fn_t fn);",
        "",
    ]

    # Pre-declare method IDs
    for fn in functions:
        used_generics = fn["used_generics"]
        if not used_generics:
            out.append(f"static jmethodID jm_{fn['java_name']};")
        else:
            combinations = list(
                itertools.product(BASE_TYPES, repeat=len(used_generics))
            )
            for combo in combinations:
                suffix = "_" + "_".join(combo)
                out.append(f"static jmethodID jm_{fn['java_name']}{suffix};")
    out.append("")

    # Generate all function wrappers and routing factories
    for fn in functions:
        used_generics = fn["used_generics"]
        if not used_generics:
            out.append(
                gen_wrapper(fn["java_name"], fn["param_types"], fn["return_type"])
            )
        else:
            combinations = list(
                itertools.product(BASE_TYPES, repeat=len(used_generics))
            )
            for combo in combinations:
                out.append(
                    gen_wrapper(
                        fn["java_name"],
                        fn["param_types"],
                        fn["return_type"],
                        used_generics,
                        combo,
                    )
                )
                out.append("")
            out.append(gen_factory(fn["java_name"], used_generics))
        out.append("")

    # Method ID Resolution
    out.append(
        "void resolve_generated_method_ids(JNIEnv* env, jclass game_functions_class) {"
    )
    out.append(
        '    jclass generic_game_functions_class = (*env)->FindClass(env, "net/grug/minecraft/grug/GenericGameFunctions");'
    )
    for fn in functions:
        used_generics = fn["used_generics"]
        if not used_generics:
            sig = build_signature(fn["param_types"], fn["return_type"])
            out.append(
                f'    jm_{fn["java_name"]} = (*env)->GetStaticMethodID(env, game_functions_class, "{fn["java_name"]}", "{sig}");'
            )
        else:
            combinations = list(
                itertools.product(BASE_TYPES, repeat=len(used_generics))
            )
            for combo in combinations:
                suffix = "_" + "_".join(combo)
                concrete_map: Dict[str, str] = dict(zip(used_generics, combo))
                param_types: List[str] = fn["param_types"]
                return_type: Optional[str] = fn["return_type"]

                resolved_params: List[str] = [
                    concrete_map.get(t, t) for t in param_types
                ]
                resolved_return: Optional[str] = (
                    concrete_map.get(return_type, return_type)
                    if return_type is not None
                    else None
                )

                sig = build_signature(resolved_params, resolved_return)
                out.append(
                    f'    jm_{fn["java_name"]}{suffix} = (*env)->GetStaticMethodID(env, generic_game_functions_class, "{fn["java_name"]}{suffix}", "{sig}");'
                )

    out.append("    (*env)->DeleteLocalRef(env, generic_game_functions_class);")
    out.append("}")
    out.append("")

    # Registration hooks
    out.append("void register_generated_host_fns(void* state) {")
    for fn in functions:
        is_generic = bool(fn["used_generics"])
        c_name = f"reg_{fn['java_name']}" if is_generic else f"host_{fn['java_name']}"

        if fn["kind"] == "host_fn":
            reg_func = (
                "grug_register_generic_fn" if is_generic else "grug_register_host_fn"
            )
            out.append(f'    {reg_func}(state, "{fn["grug_name"]}", (void*){c_name});')
        else:
            reg_func = (
                "grug_register_generic_method" if is_generic else "grug_register_method"
            )
            out.append(
                f'    {reg_func}(state, "{fn["grug_class"]}", "{fn["grug_method"]}", (void*){c_name});'
            )
    out.append("}")
    out.append("")
    out.append(generate_c_exports(exports))

    return "\n".join(out)


def main() -> None:
    if len(sys.argv) not in (3, 4, 5):
        print(
            "usage: generate.py <mod_api.json> <output adapter_generated.c> [<output ExportFns.java>] [<output GenericGameFunctions.java>]",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(sys.argv[1]) as f:
        mod_api = json.load(f)

    with open(sys.argv[2], "w") as f:
        f.write(generate(mod_api))

    if len(sys.argv) >= 4:
        exports = collect_exports(mod_api)
        os.makedirs(os.path.dirname(sys.argv[3]), exist_ok=True)
        with open(sys.argv[3], "w") as f:
            f.write(generate_java_exports(exports))

    if len(sys.argv) == 5:
        functions = collect_functions(mod_api)
        os.makedirs(os.path.dirname(sys.argv[4]), exist_ok=True)
        with open(sys.argv[4], "w") as f:
            f.write(generate_generic_java_bridge(functions))


if __name__ == "__main__":
    main()
