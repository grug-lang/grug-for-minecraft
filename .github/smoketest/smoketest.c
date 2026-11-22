// The smoketest host (#1585, part of #1548): runs mods/*/tests/*.grug logic tests against a toy
// world, in milliseconds and without Minecraft, on grug-rs's C API.
//
//   smoketest --contract                    every mod_api.json entry must have an implementation
//   smoketest <repo> <test.grug>...         run tests, e.g. coverage/tests/block_query-Test.grug
//   smoketest <repo> --compile-only         parse and compile every mods/*/code and tests file
//
// Every entry of mod_api.json is registered with grug-rs, so its own check that nothing is missing
// passes. An entry this file does not implement gets a stub that raises a runtime error naming it,
// so a test that needs it fails by name instead of reading a wrong value. Graphics calls are
// meant to be stubs that answer a value equal to itself; none is needed by a stage 1 test yet.
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// The parts of grug.h this host uses. grug.h itself is not valid C, so the declarations are
// repeated, as core/src/main/native/adapter.c repeats them. The layouts mirror the pinned revision.
struct grug_source_span { size_t offset; size_t line; };
struct grug_error {
    uint8_t kind[4];
    char *function_name, *file_path;
    struct { char* line; size_t len; } source;
    struct grug_source_span span;
    char *error_message, *error_string;
};
struct grug_file_info {
    char *path, *file_name, *mod_name, *entity_type, *entity_name;
    uint64_t file_id;
    struct grug_error error;
};
struct grug_files_slice { struct grug_file_info* ptr; size_t len; };
struct grug_runtime_error {
    uint32_t error_kind;
    struct { void* frames; size_t len; } call_stack;
    char *export_fn_name, *script_path;
    struct grug_source_span err_span;
    struct { char* ptr; size_t len; } source_line;
    char *error_message, *error_string;
};
struct grug_runtime_error_handler {
    void* user_data;
    void (*drop_fn)(void*);
    void (*handler_fn)(void*, struct grug_runtime_error*);
};
struct grug_backend { void* obj; void* vtable; };
struct grug_init_settings {
    const char* mod_api_path;
    size_t mod_api_path_len;
    const char* mods_dir_path;
    size_t mods_dir_path_len;
    struct grug_runtime_error_handler runtime_error_handler;
    uint64_t poll_interval;
    struct grug_backend backend;
};
struct grug_compile_settings {
    bool (*is_unsafe_mod)(void*, const char*, size_t);
    void* user_data;
};
enum { TYPE_VOID, TYPE_BOOL, TYPE_NUMBER, TYPE_STRING, TYPE_ID, TYPE_RESOURCE, TYPE_ENTITY };
struct grug_type {
    uint32_t type;
    union { struct { char* name; struct grug_type* generics; size_t generics_len; } id; char* s; } data;
};
union grug_value { double _number; bool _bool; const char* _string; uint64_t _id; };
typedef union grug_value (*host_fn)(void* gst, const union grug_value args[], const struct grug_type generics[]);

extern void* grug_init(struct grug_init_settings, struct grug_error* out_error);
extern struct grug_init_settings grug_default_settings(void);
extern void grug_deinit(void* state);
extern struct grug_error* grug_register_host_fn(void*, const char*, host_fn);
extern struct grug_error* grug_register_host_method(void*, const char*, const char*, host_fn);
extern struct grug_error* grug_all_host_fns_registered(void*);
extern struct grug_files_slice grug_compile_all_files(void*, struct grug_compile_settings);
extern void* grug_create_entity(void*, uint64_t file_id);
extern uint64_t grug_get_on_fn_id(void*, const char* entity_type, const char* on_fn_name);
extern bool grug_call_export_fn(void*, void* entity, uint64_t fn_id, const union grug_value* args, size_t n);
extern void grug_deinit_entity(void*, void* entity);
extern void grug_set_runtime_error(void*, const char* message);

// ---------------------------------------------------------------------------------------------
// Objects. A grug value of a class type is an id; here it indexes this table and is never reused
// within a test. The table is emptied between tests, so a handle a test saved cannot leak across.
enum kind { K_NONE, K_LEVEL, K_VEC3, K_OPTION, K_CTX, K_ME };
struct obj { enum kind kind; double x, y, z; bool has; union grug_value val; };
static struct obj* objs;
static size_t nobjs, capobjs;

static uint64_t new_obj(enum kind kind) {
    if (nobjs == capobjs) {
        capobjs = capobjs ? capobjs * 2 : 256;
        objs = realloc(objs, capobjs * sizeof *objs);
        if (!objs) abort();
    }
    memset(&objs[nobjs], 0, sizeof objs[nobjs]);
    objs[nobjs].kind = kind;
    return nobjs++ + 1;
}
static struct obj* get_obj(uint64_t id, enum kind kind) {
    return id >= 1 && id <= nobjs && objs[id - 1].kind == kind ? &objs[id - 1] : NULL;
}

static union grug_value num(double d) { union grug_value v; v._number = d; return v; }
static union grug_value boolean(bool b) { union grug_value v; v._bool = b; return v; }
static union grug_value str(const char* s) { union grug_value v; v._string = s; return v; }
static union grug_value id(uint64_t i) { union grug_value v; v._id = i; return v; }
static union grug_value none(void) { union grug_value v; memset(&v, 0, sizeof v); return v; }

static void* g_state;

// A host function error: the script stops at the call, which is how the real game reports it.
static union grug_value fail(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
static union grug_value fail(const char* fmt, ...) {
    char buf[512];
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    __builtin_va_end(ap);
    grug_set_runtime_error(g_state, buf);
    return none();
}

// Strings handed to grug must outlive the call; a test's strings are freed with its arena.
static char** arena;
static size_t narena, caparena;
static const char* keep(char* s) {
    if (narena == caparena) {
        caparena = caparena ? caparena * 2 : 64;
        arena = realloc(arena, caparena * sizeof *arena);
        if (!arena) abort();
    }
    return arena[narena++] = s;
}
static void free_test_state(void) {
    for (size_t i = 0; i < narena; i++) free(arena[i]);
    narena = 0;
    nobjs = 0;
}

// ---------------------------------------------------------------------------------------------
// The toy world: a sparse list of the blocks that are not air. A real level is a chunk store; no
// test reads more than a few hundred positions.
struct block { long x, y, z; char* name; };
static struct block* world;
static size_t nworld, capworld;
static double origin_x = 8.5, origin_y = 70.5, origin_z = 8.5;
static const long world_height = 256;

static struct block* find_block(long x, long y, long z) {
    for (size_t i = 0; i < nworld; i++)
        if (world[i].x == x && world[i].y == y && world[i].z == z) return &world[i];
    return NULL;
}
static void set_block(long x, long y, long z, const char* name) {
    struct block* b = find_block(x, y, z);
    if (!b) {
        if (nworld == capworld) {
            capworld = capworld ? capworld * 2 : 256;
            world = realloc(world, capworld * sizeof *world);
            if (!world) abort();
        }
        b = &world[nworld++];
        b->x = x; b->y = y; b->z = z; b->name = NULL;
    }
    free(b->name);
    b->name = strdup(name);
}
static void clear_world(void) {
    for (size_t i = 0; i < nworld; i++) free(world[i].name);
    nworld = 0;
}

// ---------------------------------------------------------------------------------------------
// Test state, the part of the runner a test can reach.
static long cur_tick;
static bool not_done_called;
static char* expected_error;

#define ARGS void* gst, const union grug_value a[], const struct grug_type g[]
#define UNUSED (void)gst; (void)a; (void)g

static bool is_whole(double d) { return isfinite(d) && d == floor(d); }

// TestTickContext
static union grug_value TTC_assert(ARGS) {
    UNUSED;
    if (!a[1]._bool) return fail("%s", a[2]._string);
    return none();
}
static union grug_value TTC_not_done(ARGS) { UNUSED; not_done_called = true; return none(); }
static union grug_value TTC_current_tick(ARGS) { UNUSED; return num((double)cur_tick); }
static union grug_value TTC_expect_error(ARGS) {
    UNUSED;
    free(expected_error);
    expected_error = strdup(a[1]._string);
    return none();
}
static union grug_value TTC_get_client_level(ARGS) { UNUSED; return id(new_obj(K_LEVEL)); }
static union grug_value TTC_get_origin(ARGS) {
    UNUSED;
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = origin_x; objs[v - 1].y = origin_y; objs[v - 1].z = origin_z;
    return id(v);
}

// Test (the entity `me`)
static union grug_value Test_is_reference(ARGS) { UNUSED; return boolean(false); }
static union grug_value Test_loader_name(ARGS) { UNUSED; return str("smoketest"); }

// Level
static union grug_value Level_is_air(ARGS) {
    UNUSED;
    struct block* b = find_block((long)floor(a[1]._number), (long)floor(a[2]._number), (long)floor(a[3]._number));
    return boolean(!b || strcmp(b->name, "minecraft:air") == 0);
}
static union grug_value Level_get_block(ARGS) {
    UNUSED;
    struct block* b = find_block((long)floor(a[1]._number), (long)floor(a[2]._number), (long)floor(a[3]._number));
    return str(b ? b->name : "minecraft:air");
}
static union grug_value Level_place_block(ARGS) {
    UNUSED;
    double x = a[1]._number, y = a[2]._number, z = a[3]._number;
    if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return fail("Level.place_block: a coordinate is not finite");
    if (y < 0 || y >= world_height)
        return fail("Level.place_block: the game did not put %s at (%g, %g, %g)", a[4]._string, x, y, z);
    if (!strchr(a[4]._string, ':')) return fail("Level.place_block: '%s' is not a block name", a[4]._string);
    set_block((long)floor(x), (long)floor(y), (long)floor(z), a[4]._string);
    return none();
}
static union grug_value Level_get_time(ARGS) { UNUSED; return num((double)cur_tick); }

// Vec3
static union grug_value Vec3_x(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->x : 0); }
static union grug_value Vec3_y(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->y : 0); }
static union grug_value Vec3_z(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->z : 0); }
static union grug_value Vec3_new(ARGS) {
    UNUSED;
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = a[0]._number; objs[v - 1].y = a[1]._number; objs[v - 1].z = a[2]._number;
    return id(v);
}
static union grug_value Vec3_zero(ARGS) { UNUSED; return id(new_obj(K_VEC3)); }

// Option: the value is stored as the raw grug value, which is the same size for every $Value.
static union grug_value Option_new(ARGS) { UNUSED; return id(new_obj(K_OPTION)); }
static union grug_value Option_has(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_OPTION); return boolean(o && o->has); }
static union grug_value Option_set(ARGS) {
    UNUSED;
    struct obj* o = get_obj(a[0]._id, K_OPTION);
    if (o) { o->has = true; o->val = a[1]; }
    return none();
}
static union grug_value Option_unwrap(ARGS) {
    UNUSED;
    struct obj* o = get_obj(a[0]._id, K_OPTION);
    if (!o || !o->has) return fail("Option.unwrap: the Option is empty");
    return o->val;
}

// Math
static union grug_value Math_remainder(ARGS) {
    UNUSED;
    if (a[1]._number == 0) return fail("Math.remainder: the divisor is 0");
    return num(fmod(a[0]._number, a[1]._number));
}
static union grug_value Math_floor(ARGS) { UNUSED; return num(floor(a[0]._number)); }
static union grug_value Math_sin(ARGS) { UNUSED; return num(sin(a[0]._number)); }
static union grug_value Math_cos(ARGS) { UNUSED; return num(cos(a[0]._number)); }
static union grug_value Math_pi(ARGS) { UNUSED; return num(3.14159265358979323846); }
static union grug_value Math_tau(ARGS) { UNUSED; return num(2 * 3.14159265358979323846); }

// String
static union grug_value String_length(ARGS) { UNUSED; return num((double)strlen(a[0]._string)); }
static union grug_value String_concat(ARGS) {
    UNUSED;
    size_t n = strlen(a[0]._string), m = strlen(a[1]._string);
    char* s = malloc(n + m + 1);
    memcpy(s, a[0]._string, n);
    memcpy(s + n, a[1]._string, m + 1);
    return str(keep(s));
}
static union grug_value String_from_number(ARGS) {
    UNUSED;
    char buf[64];
    if (is_whole(a[0]._number) && fabs(a[0]._number) < 1e15) snprintf(buf, sizeof buf, "%.0f", a[0]._number);
    else snprintf(buf, sizeof buf, "%g", a[0]._number);
    return str(keep(strdup(buf)));
}

// Host functions generic over their value: dispatch on the resolved generic like the real adapter.
static union grug_value equals(ARGS) {
    (void)gst;
    switch (g[0].type) {
    case TYPE_NUMBER: return boolean(a[0]._number == a[1]._number);
    case TYPE_BOOL: return boolean(a[0]._bool == a[1]._bool);
    case TYPE_STRING: return boolean(strcmp(a[0]._string, a[1]._string) == 0);
    default: return boolean(a[0]._id == a[1]._id);
    }
}
static void print_value(const union grug_value* v, const struct grug_type* t) {
    switch (t->type) {
    case TYPE_NUMBER: printf("%g", v->_number); break;
    case TYPE_BOOL: printf("%s", v->_bool ? "true" : "false"); break;
    case TYPE_STRING: printf("%s", v->_string); break;
    default: printf("<object %llu>", (unsigned long long)v->_id);
    }
}
static union grug_value print1(ARGS) { (void)gst; print_value(&a[0], &g[0]); putchar('\n'); return none(); }
static union grug_value print2(ARGS) {
    (void)gst;
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar('\n');
    return none();
}
static union grug_value print3(ARGS) {
    (void)gst;
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar(' ');
    print_value(&a[2], &g[2]); putchar('\n');
    return none();
}

// ---------------------------------------------------------------------------------------------
// The registry: every entry of mod_api.json, and which of them have an implementation.
struct api_row { const char* cls; const char* name; };
static const struct api_row api_rows[] = {
#include "api_table.inc"
};
#define NAPI (sizeof api_rows / sizeof api_rows[0])

struct impl { const char* cls; const char* name; host_fn fn; };
static const struct impl impls[] = {
    {"TestTickContext", "assert", TTC_assert},
    {"TestTickContext", "not_done", TTC_not_done},
    {"TestTickContext", "current_tick", TTC_current_tick},
    {"TestTickContext", "expect_error", TTC_expect_error},
    {"TestTickContext", "get_client_level", TTC_get_client_level},
    {"TestTickContext", "get_origin", TTC_get_origin},
    {"Test", "is_reference", Test_is_reference},
    {"Test", "loader_name", Test_loader_name},
    {"Level", "is_air", Level_is_air},
    {"Level", "get_block", Level_get_block},
    {"Level", "place_block", Level_place_block},
    {"Level", "get_time", Level_get_time},
    {"Vec3", "x", Vec3_x},
    {"Vec3", "y", Vec3_y},
    {"Vec3", "z", Vec3_z},
    {"Vec3", "new", Vec3_new},
    {"Vec3", "zero", Vec3_zero},
    {"Option", "new", Option_new},
    {"Option", "has", Option_has},
    {"Option", "set", Option_set},
    {"Option", "unwrap", Option_unwrap},
    {"Math", "remainder", Math_remainder},
    {"Math", "floor", Math_floor},
    {"Math", "sin", Math_sin},
    {"Math", "cos", Math_cos},
    {"Math", "pi", Math_pi},
    {"Math", "tau", Math_tau},
    {"String", "length", String_length},
    {"String", "concat", String_concat},
    {"String", "from_number", String_from_number},
    {NULL, "equals", equals},
    {NULL, "print", print1},
    {NULL, "print2", print2},
    {NULL, "print3", print3},
};
#define NIMPL (sizeof impls / sizeof impls[0])

static bool same(const char* a, const char* b) { return (a == NULL) == (b == NULL) && (!a || !strcmp(a, b)); }
static host_fn find_impl(const char* cls, const char* name) {
    for (size_t i = 0; i < NIMPL; i++)
        if (same(impls[i].cls, cls) && !strcmp(impls[i].name, name)) return impls[i].fn;
    return NULL;
}
static void show(const char* cls, const char* name) { printf("  %s%s%s\n", cls ? cls : "", cls ? "." : "", name); }

// The contract check: an entry of mod_api.json with no implementation, and an implementation of
// an entry mod_api.json does not have (a typo, or a function that was removed).
static int contract(void) {
    size_t implemented = 0, missing = 0, orphans = 0;
    for (size_t i = 0; i < NAPI; i++) {
        if (find_impl(api_rows[i].cls, api_rows[i].name)) implemented++;
        else missing++;
    }
    for (size_t i = 0; i < NIMPL; i++) {
        bool found = false;
        for (size_t j = 0; j < NAPI && !found; j++) found = same(impls[i].cls, api_rows[j].cls) && !strcmp(impls[i].name, api_rows[j].name);
        if (!found) { if (!orphans++) puts("implemented but not in mod_api.json:"); show(impls[i].cls, impls[i].name); }
    }
    if (missing) {
        puts("in mod_api.json with no implementation:");
        for (size_t i = 0; i < NAPI; i++)
            if (!find_impl(api_rows[i].cls, api_rows[i].name)) show(api_rows[i].cls, api_rows[i].name);
    }
    printf("contract: %zu entries, %zu implemented, %zu missing, %zu orphans\n", NAPI, implemented, missing, orphans);
    return missing || orphans ? 1 : 0;
}

static union grug_value unimplemented(ARGS) {
    UNUSED;
    return fail("the smoketest does not implement this mod_api.json entry yet");
}

// ---------------------------------------------------------------------------------------------
static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static char last_error[4096];
static void on_runtime_error(void* data, struct grug_runtime_error* e) {
    (void)data;
    snprintf(last_error, sizeof last_error, "%s", e && e->error_string ? e->error_string : "(no message)");
}

static int run_test(const char* label, uint64_t file_id, uint64_t tick_fn, uint64_t init_fn, int max_ticks) {
    free_test_state();
    clear_world();
    cur_tick = 0;
    free(expected_error);
    expected_error = NULL;
    void* entity = grug_create_entity(g_state, file_id);
    if (!entity) { printf("FAIL %s: could not create the entity\n", label); return 1; }
    uint64_t ctx = new_obj(K_CTX);
    union grug_value arg = id(ctx);
    int rc = 0;
    last_error[0] = 0;
    if (init_fn != UINT64_MAX) {
        uint64_t ictx = new_obj(K_CTX);
        union grug_value iarg = id(ictx);
        // A false with no error recorded is a file that has no init, which most tests do not.
        if (!grug_call_export_fn(g_state, entity, init_fn, &iarg, 1) && last_error[0]) {
            printf("FAIL %s: init stopped: %s\n", label, last_error);
            rc = 1;
        }
    }
    while (!rc) {
        last_error[0] = 0;
        not_done_called = false;
        bool completed = grug_call_export_fn(g_state, entity, tick_fn, &arg, 1);
        if (!completed) {
            if (expected_error && strstr(last_error, expected_error)) { printf("PASS %s (expected error)\n", label); break; }
            if (expected_error) printf("FAIL %s: expected an error containing '%s', but got: %s\n", label, expected_error, last_error);
            else printf("FAIL %s: %s\n", label, last_error);
            rc = 1;
        } else if (not_done_called) {
            if (++cur_tick >= max_ticks) { printf("FAIL %s: exceeded %d ticks\n", label, max_ticks); rc = 1; }
        } else {
            if (expected_error) { printf("FAIL %s: expected an error containing '%s', but it completed without one\n", label, expected_error); rc = 1; }
            else printf("PASS %s\n", label);
            break;
        }
    }
    grug_deinit_entity(g_state, entity);
    return rc;
}

int main(int argc, char** argv) {
    if (argc == 2 && !strcmp(argv[1], "--contract")) return contract();
    if (argc < 3) {
        fprintf(stderr, "usage: smoketest --contract | smoketest <repo> --compile-only | smoketest <repo> <test.grug>...\n");
        return 2;
    }
    char api_path[4096], mods_path[4096];
    snprintf(api_path, sizeof api_path, "%s/mod_api.json", argv[1]);
    snprintf(mods_path, sizeof mods_path, "%s/mods", argv[1]);
    // SMOKETEST_MODS points at another mods directory, to time a subset of the files.
    if (getenv("SMOKETEST_MODS")) snprintf(mods_path, sizeof mods_path, "%s", getenv("SMOKETEST_MODS"));

    double t0 = now_ms();
    struct grug_init_settings s = grug_default_settings();
    s.mod_api_path = api_path; s.mod_api_path_len = strlen(api_path);
    s.mods_dir_path = mods_path; s.mods_dir_path_len = strlen(mods_path);
    // A non-null user_data: grug-rs reads a null first field as "no handler" (see adapter.c).
    s.runtime_error_handler.user_data = (void*)1;
    s.runtime_error_handler.handler_fn = on_runtime_error;
    s.poll_interval = 3600 * 1000;
    struct grug_error err;
    memset(&err, 0, sizeof err);
    g_state = grug_init(s, &err);
    if (!g_state) { fprintf(stderr, "grug_init: %s\n", err.error_string ? err.error_string : "failed"); return 2; }

    size_t implemented = 0;
    for (size_t i = 0; i < NAPI; i++) {
        host_fn f = find_impl(api_rows[i].cls, api_rows[i].name);
        implemented += f != NULL;
        if (!f) f = unimplemented;
        struct grug_error* e = api_rows[i].cls ? grug_register_host_method(g_state, api_rows[i].cls, api_rows[i].name, f)
                                               : grug_register_host_fn(g_state, api_rows[i].name, f);
        if (e) { fprintf(stderr, "register %s: %s\n", api_rows[i].name, e->error_string); return 2; }
    }
    struct grug_error* missing = grug_all_host_fns_registered(g_state);
    if (missing) { fprintf(stderr, "grug-rs: %s\n", missing->error_string); return 2; }
    double t1 = now_ms();

    struct grug_files_slice files = grug_compile_all_files(g_state, (struct grug_compile_settings){0});
    double t2 = now_ms();
    size_t bad = 0;
    for (size_t i = 0; i < files.len; i++) {
        if (files.ptr[i].file_id == UINT64_MAX) {
            bad++;
            if (bad <= 5) fprintf(stderr, "compile error in %s: %s\n", files.ptr[i].path, files.ptr[i].error.error_string);
        }
    }
    printf("registered %zu entries (%zu implemented), init+register %.2f ms; compiled %zu files (%zu with errors) in %.2f ms\n",
           NAPI, implemented, t1 - t0, files.len, bad, t2 - t1);

    int rc = 0;
    if (!strcmp(argv[2], "--compile-only")) { grug_deinit(g_state); return bad ? 1 : 0; }
    uint64_t tick_fn = grug_get_on_fn_id(g_state, "Test", "tick");
    uint64_t init_fn = grug_get_on_fn_id(g_state, "Test", "init");
    int ran = 0;
    for (int i = 2; i < argc; i++) {
        size_t found = files.len;
        for (size_t j = 0; j < files.len; j++) if (!strcmp(files.ptr[j].path, argv[i])) found = j;
        if (found == files.len || files.ptr[found].file_id == UINT64_MAX) { printf("FAIL %s: not compiled\n", argv[i]); rc = 1; continue; }
        double a = now_ms();
        rc |= run_test(argv[i], files.ptr[found].file_id, tick_fn, init_fn, 200);
        printf("  %s: %.3f ms\n", argv[i], now_ms() - a);
        ran++;
    }
    printf("ran %d tests, %s\n", ran, rc ? "FAILED" : "all passed");
    free_test_state();
    clear_world();
    free(world); free(objs); free(arena); free(expected_error);
    grug_deinit(g_state);
    return rc;
}
