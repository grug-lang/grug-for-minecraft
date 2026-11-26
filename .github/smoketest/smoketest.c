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
// Sections, in file order (search "SECTION:"). Each new entry goes inside its own section, so
// parallel changes stay local; one file on purpose, to be split into a unity build at ~5000 lines.
//   Objects and string arena
//   World storage
//   Runner state
//   TestTickContext
//   Test (the entity me)
//   Level
//   Vec3 and BlockPos
//   Option
//   Math
//   String
//   Generic host functions and print
//   Items and resource locations
//   BlockEntity
//   Item entities
//   Runner settings and graphics stubs
//   Registry and contract
//   Runner (run_test, --all, main)
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

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
extern void grug_set_next_entity_id(void*, uint64_t next_id);

// =============================================================================================
// SECTION: Objects and string arena
// =============================================================================================
// Objects. A grug value of a class type is an id; here it indexes this table and is never reused
// within a test. The table is emptied between tests, so a handle a test saved cannot leak across.
// One kind per line, so a new kind in another change is a separate hunk.
enum kind {
    K_NONE,
    K_LEVEL,
    K_VEC3,
    K_OPTION,
    K_CTX,
    K_ME,
    K_RL,
    K_ITEM,
    K_BLOCKPOS,
    K_PLAYER,
    K_EXTRACT_RESPONSE,
    K_ADD_RESPONSE,
    K_SHOT,
    K_ITEM_STACK,
    K_ITEM_ENTITY,
    K_ENTITY,
    K_USE_CTX,
    K_COLOR,
    K_GUI,
    K_DISPLAY_CTX,
    K_SHAPE_CTX,
    K_OVERLAY_RENDER,
    K_OVERLAY_CLICK,
    K_OVERLAY_KEY,
    K_OVERLAY_SCROLL,
    K_FILE,
    K_LIQUID_RESPONSE,
    K_WORLD_CTX,
    K_WORLD_KEY_CTX,
    K_BINDING_CTX,
    K_CONFIG,
    K_SQUARES,
};
struct obj {
    enum kind kind;
    double x, y, z;
    bool has;
    bool open;  // a response: valid until the call it was passed to returns
    union grug_value val;
    const char* str;
};
static struct obj* objs;
static size_t nobjs, capobjs;

static void die_oom(void) { fputs("smoketest: out of memory\n", stderr); abort(); }  // SMOKETEST_EXCL: no test can force an allocation failure
static void* xrealloc(void* p, size_t n) { void* q = realloc(p, n); if (!q) die_oom(); return q; }  // SMOKETEST_EXCL_BRANCH: the failing arm needs an allocation failure, which no test can force
static void* xmalloc(size_t n) { void* q = malloc(n); if (!q) die_oom(); return q; }  // SMOKETEST_EXCL_BRANCH: the failing arm needs an allocation failure, which no test can force
static char* xstrdup(const char* s) { char* q = strdup(s); if (!q) die_oom(); return q; }  // SMOKETEST_EXCL_BRANCH: the failing arm needs an allocation failure, which no test can force

static uint64_t new_obj(enum kind kind) {
    if (nobjs == capobjs) {
        capobjs = capobjs ? capobjs * 2 : 256;
        objs = xrealloc(objs, capobjs * sizeof *objs);
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

// The three coordinates of a call, floored. A host function receives a double, and casting one that
// is not finite or does not fit a long is undefined, so a call with such a coordinate fails by name.
static bool pos_arg(const union grug_value a[], long out[3], const char* who) {
    for (int i = 0; i < 3; i++) {
        double d = a[1 + i]._number;
        if (!isfinite(d) || fabs(d) > 1e15) { fail("%s: a coordinate is not a finite number within 1e15", who); return false; }
        out[i] = (long)floor(d);
    }
    return true;
}

// Strings handed to grug must outlive the call; a test's strings are freed with its arena.
static char** arena;
static size_t narena, caparena;
static const char* keep(char* s) {
    if (narena == caparena) {
        caparena = caparena ? caparena * 2 : 64;
        arena = xrealloc(arena, caparena * sizeof *arena);
    }
    return arena[narena++] = s;
}
static void free_test_state(void) {
    for (size_t i = 0; i < narena; i++) free(arena[i]);
    narena = 0;
    nobjs = 0;
}

// =============================================================================================
// SECTION: World storage
// =============================================================================================
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
            world = xrealloc(world, capworld * sizeof *world);
        }
        b = &world[nworld++];
        b->x = x; b->y = y; b->z = z; b->name = NULL;
    }
    free(b->name);
    b->name = xstrdup(name);
}
static void clear_world(void) {
    for (size_t i = 0; i < nworld; i++) free(world[i].name);
    nworld = 0;
}

// The light and the spawn spaces of the toy world, which the scan and the light reads model
// (#1255). They are a model and not Minecraft's own light: a torch gives 14 and falls off one per
// block of distance, a solid block anywhere above a space takes its sky light to 0, and a space a
// hostile mob may stand in is an air block with a solid block under it and air above it. That is
// enough to place one square a mod marks and one it does not, which is all a logic test reads.
static int toy_emission(const char* name) {
    if (!name) return 0;
    if (!strcmp(name, "minecraft:torch")) return 14;
    if (!strcmp(name, "minecraft:redstone_torch")) return 7;
    if (!strcmp(name, "minecraft:glowstone")) return 15;
    if (!strcmp(name, "minecraft:lava")) return 15;
    if (!strcmp(name, "minecraft:jack_o_lantern")) return 15;
    return 0;
}
// Whether a block is one a mob cannot stand in or on: everything but air, the torches and the
// liquids. Leaves count as solid, as they do before 1.20.6.
static bool toy_solid(const char* name) {
    if (!name || !strcmp(name, "minecraft:air")) return false;
    if (!strcmp(name, "minecraft:torch")) return false;
    if (!strcmp(name, "minecraft:redstone_torch")) return false;
    if (!strcmp(name, "minecraft:lava")) return false;
    if (!strcmp(name, "minecraft:water")) return false;
    return true;
}
static bool toy_liquid(const char* name) {
    return name && (!strcmp(name, "minecraft:lava") || !strcmp(name, "minecraft:water"));
}
static int toy_block_light(long x, long y, long z) {
    struct block* here = find_block(x, y, z);
    if (here && toy_solid(here->name)) return 0;
    int best = 0;
    for (size_t i = 0; i < nworld; i++) {
        int emission = toy_emission(world[i].name);
        if (!emission) continue;
        long d = labs(world[i].x - x) + labs(world[i].y - y) + labs(world[i].z - z);
        int light = emission - (int)d;
        if (light > best) best = light;
    }
    return best;
}
static int toy_sky_light(long x, long y, long z) {
    struct block* here = find_block(x, y, z);
    if (here && toy_solid(here->name)) return 0;
    for (size_t i = 0; i < nworld; i++)
        if (world[i].x == x && world[i].z == z && world[i].y > y && toy_solid(world[i].name)) return 0;
    return 15;
}
static bool toy_spawn_space(long x, long y, long z) {
    if (y < 0 || y >= world_height) return false;
    struct block* here = find_block(x, y, z);
    if (here && toy_solid(here->name)) return false;
    if (toy_liquid(here ? here->name : NULL)) return false;
    struct block* above = find_block(x, y + 1, z);
    if (above && toy_solid(above->name)) return false;
    struct block* below = find_block(x, y - 1, z);
    return below && toy_solid(below->name);
}
// One square a scan kept: its position and the two lights a mod's rule reads.
struct square { long x, y, z; int block_light, sky_light; };
struct square_scan { struct square* squares; size_t count; };
static struct square_scan* scans;
static size_t nscans, capscans;
static void clear_scans(void) {
    for (size_t i = 0; i < nscans; i++) free(scans[i].squares);
    nscans = 0;
}
// The camera and the local player's feet a world frame draws from (#1255). A test moves the feet
// with TestTickContext.set_player_position; run_test puts them back around the room before every
// test, the way the room is put back.
static double player_x = 8.5, player_y = 67.5, player_z = 8.5;
// The drawn mark a world frame sets and the passes' exports, which main() looks up once. The
// overlays themselves are the one entity per file that make_overlays creates: the host gives each
// overlay file one handle for every export, so a member render_hud sets is the one render_world and
// world_binding read (#1749's review).
static uint64_t world_render_fn, world_key_fn, world_binding_fn;
static bool world_overlay_drawn_flag;
static const char* world_pass_mod;
// The one entity per overlay file, made by make_overlays below and asked by the world passes here.
struct overlay {
    void* entity;
    const char* name;  // the clean entity name GrugOverlayScripts keys the file by
    const char* mod;
    const char* path;
};
static struct overlay* overlays;
static size_t noverlays, capoverlays;
// Java's (long) cast of a double, defined with the other converts below.
static int64_t java_long(double d);
static bool overlay_disabled(const char* path);
static void overlay_render_world(void);
static void overlay_press_world_key(double code, bool ctrl);
static void clear_overlays(void);

// =============================================================================================
// SECTION: Runner state
// =============================================================================================
// Test state, the part of the runner a test can reach.
static long cur_tick;
static bool not_done_called;
static char* expected_error;
static bool quiet;
static bool stubbed;  // the running test called a graphics or capability stub, so a pass proves less

struct impl { const char* cls; const char* name; host_fn fn; };
// An implementation registers itself where it is defined, with REGISTER right after its function,
// into a linker section, so adding an entry touches no shared line (GNU ld's __start_/__stop_).
extern const struct impl __start_smoke_impls[], __stop_smoke_impls[];
#define SMOKE_CAT2(a, b) a##b
#define SMOKE_CAT(a, b) SMOKE_CAT2(a, b)
#define REGISTER(cls, name, fn) \
    static const struct impl SMOKE_CAT(smoke_impl_, __LINE__) __attribute__((used, section("smoke_impls"), aligned(8))) = {cls, name, fn}
#define ARGS void* gst, const union grug_value a[], const struct grug_type g[]
#define UNUSED (void)gst; (void)a; (void)g
#include "api_table.inc"
// One row per option and per key binding a mod declares in its about.json, which the host owns: the
// smoketest has no config file, so a read answers the declared default.
#include "mod_config.inc"

// The scan GrugSpawnSquares.scan makes: z, then y, then x from the lowest, around the feet block.
static uint64_t toy_scan(double radius) {
    long r = (long)radius;
    size_t count = 0, cap = 64;
    struct square* squares = xmalloc(cap * sizeof *squares);
    // Java floors the feet's block, as MathHelper.floor_double does: a feet x of -18.5 is in -19.
    long cx = java_long(floor(player_x)), cy = java_long(floor(player_y)), cz = java_long(floor(player_z));
    for (long dz = -r; dz <= r; dz++)
        for (long dy = -r; dy <= r; dy++)
            for (long dx = -r; dx <= r; dx++) {
                long x = cx + dx, y = cy + dy, z = cz + dz;
                if (!toy_spawn_space(x, y, z)) continue;
                if (count == cap) { cap *= 2; squares = xrealloc(squares, cap * sizeof *squares); }
                squares[count].x = x; squares[count].y = y; squares[count].z = z;
                squares[count].block_light = toy_block_light(x, y, z);
                squares[count].sky_light = toy_sky_light(x, y, z);
                count++;
            }
    if (nscans == capscans) { capscans = capscans ? capscans * 2 : 8; scans = xrealloc(scans, capscans * sizeof *scans); }
    scans[nscans].squares = squares;
    scans[nscans].count = count;
    uint64_t o = new_obj(K_SQUARES);
    objs[o - 1].x = (double)nscans++;
    return o;
}
static const struct mod_option* find_option(const char* mod, const char* key) {
    for (size_t i = 0; i < nmod_options; i++)
        if (!strcmp(mod_options[i].mod, mod) && !strcmp(mod_options[i].key, key)) return &mod_options[i];
    return NULL;
}

// Java's (int) cast of a double: NaN is 0 and a value out of range saturates.
static int32_t java_int(double d) {
    if (d != d) return 0;
    if (d >= 2147483647.0) return 2147483647;
    if (d <= -2147483648.0) return (int32_t)-2147483647 - 1;
    return (int32_t)d;
}
// Java's (long) cast of a double, for the feet a toy scan floors: a coordinate like 1e300 is out
// of range, where the cast would be undefined behaviour in C, and Java saturates it (#1749's
// review). 2^63 is the first double above Long.MAX_VALUE, so the comparison is against it.
static int64_t java_long(double d) {
    if (d != d) return 0;
    if (d >= 9223372036854775808.0) return INT64_MAX;
    if (d <= -9223372036854775808.0) return INT64_MIN;
    return (int64_t)d;
}
static bool is_whole(double d) { return isfinite(d) && d == floor(d); }

// =============================================================================================
// SECTION: TestTickContext
// =============================================================================================
// TestTickContext
// The real host reports a false assertion as "Assertion failed: " and the test's message
// (HostFunctions.TestTickContext_assert), which expect_assert_failure-Test matches on.
static union grug_value TTC_assert(ARGS) {
    UNUSED;
    if (!a[1]._bool) return fail("Assertion failed: %s", a[2]._string);
    return none();
}
REGISTER("TestTickContext", "assert", TTC_assert);
static union grug_value TTC_not_done(ARGS) { UNUSED; not_done_called = true; return none(); }
REGISTER("TestTickContext", "not_done", TTC_not_done);
static union grug_value TTC_current_tick(ARGS) { UNUSED; return num((double)cur_tick); }
REGISTER("TestTickContext", "current_tick", TTC_current_tick);
static union grug_value TTC_expect_error(ARGS) {
    UNUSED;
    // The real runner matches with contains(), so an empty message would accept every error; here
    // it is a defect of the test instead of a silent pass.
    if (!a[1]._string[0]) return fail("TestTickContext.expect_error: the message is empty, which matches every error");
    free(expected_error);
    expected_error = xstrdup(a[1]._string);
    return none();
}
REGISTER("TestTickContext", "expect_error", TTC_expect_error);
static union grug_value TTC_get_client_level(ARGS) { UNUSED; return id(new_obj(K_LEVEL)); }
REGISTER("TestTickContext", "get_client_level", TTC_get_client_level);
static union grug_value TTC_get_origin(ARGS) {
    UNUSED;
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = origin_x; objs[v - 1].y = origin_y; objs[v - 1].z = origin_z;
    return id(v);
}
REGISTER("TestInitContext", "get_origin", TTC_get_origin);
REGISTER("TestTickContext", "get_origin", TTC_get_origin);
// The player's feet, which a world frame and a scan are centred on (#1255). A test moves them, and
// run_test puts them back around the room before the next test.
static const char* cur_test_mod;
static union grug_value TTC_set_player_position(ARGS) {
    UNUSED;
    double x = a[1]._number, y = a[2]._number, z = a[3]._number;
    if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return fail("TestTickContext.set_player_position: The position has to be finite, and was %g, %g, %g.", x, y, z);
    player_x = x; player_y = y; player_z = z;
    return none();
}
REGISTER("TestTickContext", "set_player_position", TTC_set_player_position);
// Whether a frame drew the mod's world overlay since the mark was last cleared, which the mark a
// press clears and a frame sets. The smoketest draws a frame after every test tick.
static union grug_value TTC_world_overlay_drawn(ARGS) { UNUSED; return boolean(world_overlay_drawn_flag); }
REGISTER("TestTickContext", "world_overlay_drawn", TTC_world_overlay_drawn);
// One press of a letter or digit key while no screen is open: world_key, then every declared binding
// whose default key it is. The key is the upper case character code, as OverlayWorldKeyContext.key
// answers it.
static union grug_value TTC_press_world_key(ARGS) {
    UNUSED;
    double key = a[1]._number;
    bool tracked = is_whole(key) && ((key >= 'A' && key <= 'Z') || (key >= '0' && key <= '9'));
    if (!tracked) return fail("TestTickContext.press_world_key: The key has to be the upper case character code of a letter or a digit, and was %g.", key);
    overlay_press_world_key(key, a[2]._bool);
    return none();
}
REGISTER("TestTickContext", "press_world_key", TTC_press_world_key);
// The running test's own mod configuration, which is the same one its overlay's world pass reads.
static union grug_value TTC_get_config(ARGS) {
    UNUSED;
    if (!cur_test_mod) return fail("TestTickContext.get_config: The running test is not one of a mod, so it has no config.");
    uint64_t o = new_obj(K_CONFIG);
    objs[o - 1].str = keep(xstrdup(cur_test_mod));
    return id(o);
}
REGISTER("TestTickContext", "get_config", TTC_get_config);
static bool scan_radius_ok(const char* who, double radius) {
    if (isfinite(radius) && radius == floor(radius) && radius >= 0 && radius <= 32) return true;
    fail("%s: The radius has to be a whole number from 0 to 32, and was %g.", who, radius);
    return false;
}
// The scan a world pass makes, around the test player's feet, which a test asserts directly because
// a pass has no assert of its own.
static union grug_value TTC_get_spawn_squares(ARGS) {
    UNUSED;
    if (!scan_radius_ok("TestTickContext.get_spawn_squares", a[1]._number)) return none();
    return id(toy_scan(a[1]._number));
}
REGISTER("TestTickContext", "get_spawn_squares", TTC_get_spawn_squares);

// =============================================================================================
// SECTION: Test (the entity me)
// =============================================================================================
// Test (the entity `me`)
static union grug_value Test_is_reference(ARGS) { UNUSED; return boolean(false); }
REGISTER("Test", "is_reference", Test_is_reference);
static union grug_value Test_loader_name(ARGS) { UNUSED; return str("smoketest"); }
REGISTER("Test", "loader_name", Test_loader_name);
// Every loader registers a mod's recipes (shapeless_recipe-Test says so), and the smoketest matches a grid
// against the mods' own recipe files.
static union grug_value Test_registers_mod_recipes(ARGS) { UNUSED; return boolean(true); }
REGISTER("Test", "registers_mod_recipes", Test_registers_mod_recipes);
// The toy world is one client-side process with one world, so a test's dedicated-server legs (which wait on
// a joined client's copy, or drive a server-held list) return early here: a pass that asked is STUBBED.
static union grug_value Test_on_dedicated_server(ARGS) { UNUSED; stubbed = true; return boolean(false); }
REGISTER("Test", "on_dedicated_server", Test_on_dedicated_server);
// The operator status of the player the test runs as (operator-Test): the owner of a singleplayer world is
// an operator until set_operator takes that away, and the status is put back to true when a test starts.
static bool operator_status = true;
static union grug_value hf_is_operator(ARGS) { UNUSED; return boolean(operator_status); }
REGISTER(NULL, "is_operator", hf_is_operator);
static union grug_value hf_set_operator(ARGS) { UNUSED; operator_status = a[0]._bool; return none(); }
REGISTER(NULL, "set_operator", hf_set_operator);

// =============================================================================================
// SECTION: Level
// =============================================================================================
// Level
static union grug_value Level_is_air(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.is_air")) return none();
    struct block* b = find_block(p[0], p[1], p[2]);
    return boolean(!b || strcmp(b->name, "minecraft:air") == 0);
}
REGISTER("Level", "is_air", Level_is_air);
static union grug_value Level_get_block(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.get_block")) return none();
    struct block* b = find_block(p[0], p[1], p[2]);
    return str(b ? b->name : "minecraft:air");
}
REGISTER("Level", "get_block", Level_get_block);
// The three light reads a scan's rule needs (#1255), from the toy model, and whether the game's
// spawner accepts a space: the block below is solid, and the space and the one above are not.
static union grug_value Level_get_block_light(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.get_block_light")) return none();
    return num(toy_block_light(p[0], p[1], p[2]));
}
REGISTER("Level", "get_block_light", Level_get_block_light);
static union grug_value Level_get_sky_light(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.get_sky_light")) return none();
    return num(toy_sky_light(p[0], p[1], p[2]));
}
REGISTER("Level", "get_sky_light", Level_get_sky_light);
// The limit every version before 1.20.6 has, which is the reference's own.
static union grug_value Level_get_hostile_block_light_limit(ARGS) { UNUSED; return num(7); }
REGISTER("Level", "get_hostile_block_light_limit", Level_get_hostile_block_light_limit);
static union grug_value Level_is_hostile_spawn_space(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.is_hostile_spawn_space")) return none();
    return boolean(toy_spawn_space(p[0], p[1], p[2]));
}
REGISTER("Level", "is_hostile_spawn_space", Level_is_hostile_spawn_space);
static void on_block_placed(long x, long y, long z, const char* name);
static long resolve_block_fwd(const char* name);
static union grug_value Level_place_block(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.place_block")) return none();
    double x = a[1]._number, y = a[2]._number, z = a[3]._number;
    // expect_place_block_out_of_range-Test asserts the "place_block: the game did not put" text; the
    // 256 limit is the Alpha and Beta height, the tightest of the loaders.
    if (y < 0 || y >= world_height)
        return fail("Level.place_block: the game did not put %s at (%g, %g, %g)", a[4]._string, x, y, z);
    if (resolve_block_fwd(a[4]._string) == -2) return fail("Level.place_block: Could not resolve block %s", a[4]._string);
    set_block(p[0], p[1], p[2], a[4]._string);
    on_block_placed(p[0], p[1], p[2], a[4]._string);
    return none();
}
REGISTER("Level", "place_block", Level_place_block);
// explode (explode-Test, expect_explode_*-Test): the argument checks are the host's, and the blast itself is the
// game's, which is random. The smoketest destroys every block whose centre is within the power of the
// blast centre, which is all explode-Test asserts (glass next to a power 3 blast goes, glass 12 away stays),
// so a pass is STUBBED.
static void double_text(double v, char* out, size_t cap);
static void number_text(double v, char* out, size_t cap);
static union grug_value Level_explode(ARGS) {
    UNUSED;
    static const char* const names[4] = {"x", "y", "z", "power"};
    char text[400];
    for (int i = 0; i < 4; i++)
        if (!isfinite(a[1 + i]._number)) {
            double_text(a[1 + i]._number, text, sizeof text);
            return fail("explode: %s must be finite, got %s", names[i], text);
        }
    double power = a[4]._number;
    if (power < 0 || power > 3.4028234663852886e38) {
        double_text(power, text, sizeof text);
        return fail("explode: power must be from 0 to 3.4028235E38, got %s", text);
    }
    stubbed = true;
    struct { long x, y, z; }* hit = xmalloc((nworld + 1) * sizeof *hit);  // set_block below must not disturb the walk
    size_t n = 0;
    for (size_t i = 0; i < nworld; i++) {
        double dx = (double)world[i].x + 0.5 - a[1]._number, dy = (double)world[i].y + 0.5 - a[2]._number, dz = (double)world[i].z + 0.5 - a[3]._number;
        if (strcmp(world[i].name, "minecraft:air") && sqrt(dx * dx + dy * dy + dz * dz) <= power) { hit[n].x = world[i].x; hit[n].y = world[i].y; hit[n].z = world[i].z; n++; }
    }
    for (size_t i = 0; i < n; i++) {
        set_block(hit[i].x, hit[i].y, hit[i].z, "minecraft:air");
        on_block_placed(hit[i].x, hit[i].y, hit[i].z, "minecraft:air");
    }
    free(hit);
    return none();
}
REGISTER("Level", "explode", Level_explode);
// has_neighbor_signal (redstone_signal-Test, redstone_engine-Test, wooden_pipe-Test): a redstone torch gives
// off a signal to the block above it. A torch at any other side, or another redstone part, has no test
// that settles what it does, so such a neighbour marks the test stubbed.
static union grug_value Level_has_neighbor_signal(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.has_neighbor_signal")) return none();
    static const long d[6][3] = {{0,-1,0},{0,1,0},{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};
    bool powered = false;
    for (int i = 0; i < 6; i++) {
        struct block* b = find_block(p[0] + d[i][0], p[1] + d[i][1], p[2] + d[i][2]);
        if (!b || !(strstr(b->name, "redstone") || strstr(b->name, "lever") || strstr(b->name, "button"))) continue;
        if (i == 0 && strcmp(b->name, "minecraft:redstone_torch") == 0) powered = true;
        else stubbed = true;  // SMOKETEST_EXCL: no committed test places another power source beside a queried block
    }
    return boolean(powered);
}
REGISTER("Level", "has_neighbor_signal", Level_has_neighbor_signal);
// The level's random is java.util.Random with a fixed seed (the algorithm random_int's description names).
static uint64_t rnd_seed;
static bool rnd_have_gaussian;
static double rnd_next_gaussian;
static void rnd_set_seed(int64_t seed) {  // new Random(seed)
    rnd_seed = ((uint64_t)seed ^ 0x5DEECE66Dull) & ((1ull << 48) - 1);
    rnd_have_gaussian = false;
}
static void rnd_reset(void) { rnd_set_seed(42); }  // every test starts from the same seed, so --all equals a single run
static int32_t rnd_next(int bits) {
    rnd_seed = (rnd_seed * 0x5DEECE66Dull + 0xB) & ((1ull << 48) - 1);
    return (int32_t)(uint32_t)(rnd_seed >> (48 - bits));
}
static double rnd_double(void) {  // Random.nextDouble: the two draws are sequenced, high bits first
    int64_t hi = rnd_next(26);
    int64_t lo = rnd_next(27);
    return (double)((hi << 27) + lo) * (1.0 / (double)(1ll << 53));
}
// Random.nextInt(bound) for a bound from 1 to 2147483647.
static int32_t rnd_next_int(uint32_t bound) {
    uint32_t r = (uint32_t)rnd_next(31), m = bound - 1;
    if ((bound & m) == 0) return (int32_t)(((int64_t)bound * (int64_t)r) >> 31);
    // Java's loop rejects a draw when u - r + m overflows int; the same test on unsigned values.
    for (uint32_t u = r; (int32_t)(u - (r = u % bound) + m) < 0; u = (uint32_t)rnd_next(31)) {}
    return (int32_t)r;
}
static union grug_value Level_random_int(ARGS) {
    UNUSED;
    double b = a[1]._number;
    if (!is_whole(b) || b < 1 || b > 2147483647.0) return fail("The bound has to be a whole number from 1 to 2147483647");
    return num((double)rnd_next_int((uint32_t)b));
}
REGISTER("Level", "random_int", Level_random_int);
static double rnd_float(void) { return rnd_next(24) / (double)(1 << 24); }  // Random.nextFloat
static union grug_value Level_random_float(ARGS) { UNUSED; return num(rnd_float()); }
REGISTER("Level", "random_float", Level_random_float);
// Random.nextGaussian, the polar method: each loop makes two values and the second is kept for the next call.
// StrictMath.log is fdlibm's __ieee754_log, which Random.nextGaussian uses; glibc's log differs from it
// in the last bit for some arguments (a selftest vector from seed 42 shows it), so it is written out here.
// SMOKETEST_EXCL_START: reached only by selftest.c, whose JDK known-answer vectors prove it
static double strict_log(double x) {
    static const double ln2_hi = 6.93147180369123816490e-01, ln2_lo = 1.90821492927058770002e-10,
        two54 = 1.80143985094819840000e+16, Lg1 = 6.666666666666735130e-01, Lg2 = 3.999999999940941908e-01,
        Lg3 = 2.857142874366239149e-01, Lg4 = 2.222219843214978396e-01, Lg5 = 1.818357216161805012e-01,
        Lg6 = 1.531383769920937332e-01, Lg7 = 1.479819860511658591e-01;
    union { double d; uint64_t u; } bits = {x};
    int32_t hx = (int32_t)(bits.u >> 32);
    uint32_t lx = (uint32_t)bits.u;
    int32_t k = 0;
    if (hx < 0x00100000) {  // x < 2**-1022
        if (((hx & 0x7fffffff) | (int32_t)lx) == 0) return -INFINITY;
        if (hx < 0) return NAN;
        k -= 54;
        x *= two54;
        bits.d = x;
        hx = (int32_t)(bits.u >> 32);
    }
    if (hx >= 0x7ff00000) return x + x;
    k += (hx >> 20) - 1023;
    hx &= 0x000fffff;
    int32_t i = (hx + 0x95f64) & 0x100000;
    bits.u = ((uint64_t)(uint32_t)(hx | (i ^ 0x3ff00000)) << 32) | (bits.u & 0xffffffffu);  // normalize x or x/2
    x = bits.d;
    k += i >> 20;
    double f = x - 1.0;
    double dk = (double)k;
    if ((0x000fffff & (2 + hx)) < 3) {  // |f| < 2**-20
        if (f == 0) return k == 0 ? 0.0 : dk * ln2_hi + dk * ln2_lo;
        double R = f * f * (0.5 - 0.33333333333333333 * f);
        return k == 0 ? f - R : dk * ln2_hi - ((R - dk * ln2_lo) - f);
    }
    double s = f / (2.0 + f);
    double z = s * s, w = z * z;
    i = hx - 0x6147a;
    int32_t j = 0x6b851 - hx;
    double t1 = w * (Lg2 + w * (Lg4 + w * Lg6)), t2 = z * (Lg1 + w * (Lg3 + w * (Lg5 + w * Lg7)));
    i |= j;
    double R = t2 + t1;
    if (i > 0) {
        double hfsq = 0.5 * f * f;
        return k == 0 ? f - (hfsq - s * (hfsq + R)) : dk * ln2_hi - ((hfsq - (s * (hfsq + R) + dk * ln2_lo)) - f);
    }
    return k == 0 ? f - s * (f - R) : dk * ln2_hi - ((s * (f - R) - dk * ln2_lo) - f);
}
// SMOKETEST_EXCL_STOP
static double rnd_gaussian(void) {
    if (rnd_have_gaussian) { rnd_have_gaussian = false; return rnd_next_gaussian; }
    double v1, v2, s;
    do {
        v1 = 2 * rnd_double() - 1;
        v2 = 2 * rnd_double() - 1;
        s = v1 * v1 + v2 * v2;
    } while (s >= 1 || s == 0);
    double multiplier = sqrt(-2 * strict_log(s) / s);
    rnd_next_gaussian = v2 * multiplier;
    rnd_have_gaussian = true;
    return v1 * multiplier;
}
static union grug_value Level_random_gaussian(ARGS) { UNUSED; return num(rnd_gaussian()); }
REGISTER("Level", "random_gaussian", Level_random_gaussian);
static double time_offset;
static union grug_value Level_get_time(ARGS) { UNUSED; return num((double)cur_tick + time_offset); }
REGISTER("Level", "get_time", Level_get_time);
static union grug_value Level_set_time(ARGS) {
    UNUSED;
    double t = a[1]._number;
    if (!is_whole(t) || t < 0 || t > 9007199254740991.0) return fail("Level.set_time: The time has to be a whole number of ticks from 0 to 9007199254740991");
    time_offset = t - (double)cur_tick;
    return none();
}
REGISTER("Level", "set_time", Level_set_time);

// =============================================================================================
// SECTION: Vec3 and BlockPos
// =============================================================================================
// Vec3
static union grug_value Vec3_x(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->x : 0); }
REGISTER("Vec3", "x", Vec3_x);
static union grug_value Vec3_y(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->y : 0); }
REGISTER("Vec3", "y", Vec3_y);
static union grug_value Vec3_z(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_VEC3); return num(o ? o->z : 0); }
REGISTER("Vec3", "z", Vec3_z);
// A Vec3's coordinates have to be finite, as HostFunctions.Vec3_new reports them, because a spawned
// entity with a NaN velocity has meaningless motion (expect_vec3_non_finite-Test).
static union grug_value Vec3_new(ARGS) {
    UNUSED;
    static const char* const names[3] = {"x", "y", "z"};
    char text[400];
    for (int i = 0; i < 3; i++)
        if (!isfinite(a[i]._number)) {
            double_text(a[i]._number, text, sizeof text);
            return fail("Vec3.new: %s must be finite, got %s", names[i], text);
        }
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = a[0]._number; objs[v - 1].y = a[1]._number; objs[v - 1].z = a[2]._number;
    return id(v);
}
REGISTER("Vec3", "new", Vec3_new);
static union grug_value Vec3_zero(ARGS) { UNUSED; return id(new_obj(K_VEC3)); }
REGISTER("Vec3", "zero", Vec3_zero);

// =============================================================================================
// SECTION: Option
// =============================================================================================
// Option: the value is stored as the raw grug value, which is the same size for every $Value.
static union grug_value Option_new(ARGS) { UNUSED; return id(new_obj(K_OPTION)); }
REGISTER("Option", "new", Option_new);
static union grug_value Option_has(ARGS) { UNUSED; struct obj* o = get_obj(a[0]._id, K_OPTION); return boolean(o && o->has); }
REGISTER("Option", "has", Option_has);
static union grug_value Option_set(ARGS) {
    UNUSED;
    struct obj* o = get_obj(a[0]._id, K_OPTION);
    if (o) { o->has = true; o->val = a[1]; }
    return none();
}
REGISTER("Option", "set", Option_set);
static union grug_value Option_unwrap(ARGS) {
    UNUSED;
    struct obj* o = get_obj(a[0]._id, K_OPTION);
    if (!o || !o->has) return fail("Tried to unwrap an empty Option!");
    return o->val;
}
REGISTER("Option", "unwrap", Option_unwrap);

// =============================================================================================
// SECTION: Math
// =============================================================================================
// Math
static union grug_value Math_remainder(ARGS) {
    UNUSED;
    if (a[1]._number == 0) return fail("Math.remainder: the divisor is 0");
    return num(fmod(a[0]._number, a[1]._number));
}
REGISTER("Math", "remainder", Math_remainder);
static union grug_value Math_floor(ARGS) { UNUSED; return num(floor(a[0]._number)); }
REGISTER("Math", "floor", Math_floor);
static union grug_value Math_sin(ARGS) { UNUSED; return num(sin(a[0]._number)); }
REGISTER("Math", "sin", Math_sin);
static union grug_value Math_cos(ARGS) { UNUSED; return num(cos(a[0]._number)); }
REGISTER("Math", "cos", Math_cos);
static union grug_value Math_pi(ARGS) { UNUSED; return num(3.14159265358979323846); }
REGISTER("Math", "pi", Math_pi);
static union grug_value Math_tau(ARGS) { UNUSED; return num(2 * 3.14159265358979323846); }
REGISTER("Math", "tau", Math_tau);
// The entries Build Guide's shape generators need (#1752). Each mirrors Java's Math: a NaN
// argument stays NaN, min and max prefer the zero that keeps their side's sign, and signum gives
// the number itself for both zeros and for NaN.
static union grug_value Math_abs(ARGS) { UNUSED; return num(fabs(a[0]._number)); }
REGISTER("Math", "abs", Math_abs);
static union grug_value Math_atan2(ARGS) { UNUSED; return num(atan2(a[0]._number, a[1]._number)); }
REGISTER("Math", "atan2", Math_atan2);
static union grug_value Math_ceil(ARGS) { UNUSED; return num(ceil(a[0]._number)); }
REGISTER("Math", "ceil", Math_ceil);
static union grug_value Math_cosh(ARGS) { UNUSED; return num(cosh(a[0]._number)); }
REGISTER("Math", "cosh", Math_cosh);
// fmax and fmin answer the non-NaN argument, where Java answers NaN, so the pair is written out.
static union grug_value Math_max(ARGS) {
    UNUSED;
    double x = a[0]._number, y = a[1]._number;
    if (isnan(x) || isnan(y)) return num(NAN);
    if (x > y) return num(x);
    if (y > x) return num(y);
    return num(signbit(x) ? y : x);
}
REGISTER("Math", "max", Math_max);
static union grug_value Math_min(ARGS) {
    UNUSED;
    double x = a[0]._number, y = a[1]._number;
    if (isnan(x) || isnan(y)) return num(NAN);
    if (x < y) return num(x);
    if (y < x) return num(y);
    return num(signbit(x) ? x : y);
}
REGISTER("Math", "min", Math_min);
static union grug_value Math_signum(ARGS) {
    UNUSED;
    double x = a[0]._number;
    if (x > 0) return num(1.0);
    if (x < 0) return num(-1.0);
    return num(x);  // 0, negative zero and NaN answer themselves, as in Java.
}
REGISTER("Math", "signum", Math_signum);
static union grug_value Math_sinh(ARGS) { UNUSED; return num(sinh(a[0]._number)); }
REGISTER("Math", "sinh", Math_sinh);
static union grug_value Math_sqrt(ARGS) { UNUSED; return num(sqrt(a[0]._number)); }
REGISTER("Math", "sqrt", Math_sqrt);
static union grug_value Math_toDegrees(ARGS) {
    UNUSED;
    // Java's toDegrees multiplies by 180/pi once; multiplying by 180 and then dividing by pi rounds
    // twice and gives a different last bit for about a quarter of all inputs.
    return num(a[0]._number * (180.0 / 3.14159265358979323846));
}
REGISTER("Math", "toDegrees", Math_toDegrees);

// =============================================================================================
// SECTION: String
// =============================================================================================
// String
// Text is counted in Unicode code points, as the adapter's codePointCount does (string_edit-Test counts
// U+1F600 once), so a UTF-8 continuation byte is not a character.
static size_t cp_count(const char* t) {
    size_t n = 0;
    for (; *t; t++) n += ((unsigned char)*t & 0xC0) != 0x80;
    return n;
}
static const char* cp_offset(const char* t, size_t n) {  // the byte after the first n code points
    while (n > 0 && *t) { t++; if (((unsigned char)*t & 0xC0) != 0x80) n--; }
    while (((unsigned char)*t & 0xC0) == 0x80) t++;
    return t;
}
static union grug_value String_length(ARGS) { UNUSED; return num((double)cp_count(a[0]._string)); }
REGISTER("String", "length", String_length);
// The refusal texts name the range the adapter accepts, as expect_string_from_character_* and
// expect_string_substring_range-Test match them.
static union grug_value String_from_character(ARGS) {
    UNUSED;
    double c = a[0]._number;
    if (!(c >= 1 && c <= 1114111) || c != floor(c) || (c >= 55296 && c <= 57343))
        return fail("String.from_character: The code has to be a whole number from 1 to 1114111 that is not a surrogate, and was %g.", c);
    unsigned cp = (unsigned)c;
    char b[5]; int n = 0;
    if (cp < 0x80) b[n++] = (char)cp;
    else if (cp < 0x800) { b[n++] = (char)(0xC0 | cp >> 6); b[n++] = (char)(0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) { b[n++] = (char)(0xE0 | cp >> 12); b[n++] = (char)(0x80 | ((cp >> 6) & 0x3F)); b[n++] = (char)(0x80 | (cp & 0x3F)); }
    else { b[n++] = (char)(0xF0 | cp >> 18); b[n++] = (char)(0x80 | ((cp >> 12) & 0x3F)); b[n++] = (char)(0x80 | ((cp >> 6) & 0x3F)); b[n++] = (char)(0x80 | (cp & 0x3F)); }
    b[n] = 0;
    return str(keep(xstrdup(b)));
}
REGISTER("String", "from_character", String_from_character);
static union grug_value String_substring(ARGS) {
    UNUSED;
    const char* t = a[0]._string;
    double st = a[1]._number, en = a[2]._number;
    size_t len = cp_count(t);
    if (!(st >= 0 && en >= st && en <= (double)len) || st != floor(st) || en != floor(en))
        return fail("String.substring: The range has to be whole numbers with 0 <= start <= end <= %zu, the length of the text, and was %g to %g.", len, st, en);
    const char* from = cp_offset(t, (size_t)st);
    const char* to = cp_offset(from, (size_t)(en - st));
    char* out = xmalloc((size_t)(to - from) + 1);
    memcpy(out, from, (size_t)(to - from)); out[to - from] = 0;
    return str(keep(out));
}
REGISTER("String", "substring", String_substring);
static union grug_value String_concat(ARGS) {
    UNUSED;
    size_t n = strlen(a[0]._string), m = strlen(a[1]._string);
    char* s = xmalloc(n + m + 1);
    memcpy(s, a[0]._string, n);
    memcpy(s + n, a[1]._string, m + 1);
    return str(keep(s));
}
REGISTER("String", "concat", String_concat);
// Java's Float.toString and Double.toString: the shortest digits that read back as the same value,
// plain from 1e-3 up to 1e7 and as d.dddE+x outside it. This is what a Java string concatenation of
// a float or a double writes, and what the adapter's String.from_float and String.from_double write.
static void java_text(double v, bool single, char* out, size_t cap) {
    float f = (float)v;
    double m = single ? (double)f : v;
    if (isnan(m)) { snprintf(out, cap, "NaN"); return; }
    if (isinf(m)) { snprintf(out, cap, m > 0 ? "Infinity" : "-Infinity"); return; }
    if (m == 0) { snprintf(out, cap, signbit(m) ? "-0.0" : "0.0"); return; }
    char e[40];
    // Java picks the closest of the two-digit texts when one digit would do (4.9E-324, not 5.0E-324),
    // so the search starts at two digits and a trailing zero is dropped afterwards.
    int maxp = single ? 9 : 17;
    int p = 2;
    for (; p < maxp; p++) {
        snprintf(e, sizeof e, "%.*e", p - 1, m);
        if (single ? strtof(e, NULL) == f : strtod(e, NULL) == m) break;
    }
    snprintf(e, sizeof e, "%.*e", p - 1, m);
    char digits[24]; size_t nd = 0;
    const char* c = e;
    bool neg = *c == '-';
    if (neg) c++;
    for (; *c && *c != 'e'; c++) if (*c != '.') digits[nd++] = *c;
    while (nd > 1 && digits[nd - 1] == '0') nd--;
    digits[nd] = 0;
    int lead = atoi(c + 1);  // the power of ten the leading digit stands for
    char* w = out;
    if (neg) *w++ = '-';
    if (lead >= -3 && lead < 7) {
        if (lead >= 0) {
            for (int k = 0; k <= lead; k++) *w++ = (size_t)k < nd ? digits[k] : '0';
            *w++ = '.';
            if ((size_t)lead + 1 < nd) { memcpy(w, digits + lead + 1, nd - (size_t)lead - 1); w += nd - (size_t)lead - 1; }
            else *w++ = '0';
        } else {
            *w++ = '0'; *w++ = '.';
            for (int k = 0; k < -lead - 1; k++) *w++ = '0';
            memcpy(w, digits, nd); w += nd;
        }
        *w = 0;
    } else {
        *w++ = digits[0]; *w++ = '.';
        if (nd > 1) { memcpy(w, digits + 1, nd - 1); w += nd - 1; } else *w++ = '0';
        snprintf(w, cap - (size_t)(w - out), "E%d", lead);
    }
}
static void double_text(double v, char* out, size_t cap) { java_text(v, false, out, cap); }
// The text of a number as the adapter writes it: a whole number as a plain integer (BigDecimal's exact
// expansion), anything else as Double.toString.
static void number_text(double v, char* out, size_t cap) {
    if (isfinite(v) && v == rint(v)) { snprintf(out, cap, v == 0 ? "0" : "%.0f", v); return; }
    java_text(v, false, out, cap);
}
static union grug_value String_from_number(ARGS) {
    UNUSED;
    char buf[400];
    number_text(a[0]._number, buf, sizeof buf);
    return str(keep(xstrdup(buf)));
}
REGISTER("String", "from_number", String_from_number);
static union grug_value String_from_float(ARGS) {
    UNUSED;
    char buf[40];
    java_text(a[0]._number, true, buf, sizeof buf);
    return str(keep(xstrdup(buf)));
}
REGISTER("String", "from_float", String_from_float);
static union grug_value String_from_double(ARGS) {
    UNUSED;
    char buf[40];
    java_text(a[0]._number, false, buf, sizeof buf);
    return str(keep(xstrdup(buf)));
}
REGISTER("String", "from_double", String_from_double);
// Java's String.trim: the characters at the ends that are U+0020 or below. Every byte of a character
// outside the basic plane is 0x80 or above, so a byte-wise trim removes exactly those characters.
static union grug_value String_trim(ARGS) {
    UNUSED;
    const char* t = a[0]._string;
    while (*t && (unsigned char)*t <= ' ') t++;
    const char* end = t + strlen(t);
    while (end > t && (unsigned char)end[-1] <= ' ') end--;
    char* out = xmalloc((size_t)(end - t) + 1);
    memcpy(out, t, (size_t)(end - t));
    out[end - t] = 0;
    return str(keep(out));
}
REGISTER("String", "trim", String_trim);
// Java's String.indexOf, answered in code points so it can be handed to String.substring.
static union grug_value String_index_of(ARGS) {
    UNUSED;
    const char* at = strstr(a[0]._string, a[1]._string);
    if (!at) return num(-1);
    size_t n = 0;
    for (const char* q = a[0]._string; q < at; q++) n += ((unsigned char)*q & 0xC0) != 0x80;
    return num((double)n);
}
REGISTER("String", "index_of", String_index_of);
// Java's Double.parseDouble for the forms a file holds: a sign, digits with a point and an exponent,
// a hexadecimal number, or NaN and Infinity, all or nothing. The trimmed text is answered, or NULL
// when it is not a number, and the caller reads it with strtod or strtof.
static char* java_number_text(const char* in) {
    const char* t = in;
    while (*t && (unsigned char)*t <= ' ') t++;
    size_t n = strlen(t);
    while (n > 0 && (unsigned char)t[n - 1] <= ' ') n--;
    char* text = xmalloc(n + 1);
    memcpy(text, t, n); text[n] = 0;
    // The f, F, d and D type suffixes belong to a decimal or hexadecimal literal, not to NaN or
    // Infinity: Java's parseDouble refuses "NaNf" and "Infinityd", so the special spellings are
    // matched on the text before any suffix is stripped, and a special spelling that carried one
    // falls through to the check below, which refuses it.
    bool special = strcmp(text, "NaN") == 0 || strcmp(text, "+NaN") == 0 || strcmp(text, "-NaN") == 0
            || strcmp(text, "Infinity") == 0 || strcmp(text, "+Infinity") == 0 || strcmp(text, "-Infinity") == 0;
    if (!special) {
        if (n > 0 && strchr("fFdD", text[n - 1])) text[--n] = 0;
        // Java's parseDouble accepts no infinity or NaN spelling except the exact ones above, while
        // strtod also takes "inf" and "nan" in any case, so the first significant character must be a
        // digit or a point, which every finite form starts with (a hexadecimal one with 0).
        const char* q = text;
        if (*q == '+' || *q == '-') q++;
        char* end = NULL;
        (void)strtod(text, &end);
        bool ok = n > 0 && end == text + n && (*q == '.' || (*q >= '0' && *q <= '9'));
        if (ok && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')
                && !strchr(text, 'p') && !strchr(text, 'P')) ok = false;
        if (!ok) { free(text); return NULL; }
    }
    return text;
}
static union grug_value String_to_number(ARGS) {
    UNUSED;
    char* text = java_number_text(a[0]._string);
    uint64_t o = new_obj(K_OPTION);
    if (text) {
        double value;
        if (strcmp(text, "NaN") == 0 || strcmp(text, "+NaN") == 0 || strcmp(text, "-NaN") == 0) value = NAN;
        else if (strcmp(text, "-Infinity") == 0) value = -INFINITY;
        else if (strcmp(text, "Infinity") == 0 || strcmp(text, "+Infinity") == 0) value = INFINITY;
        else value = strtod(text, NULL);
        objs[o - 1].has = true; objs[o - 1].val = num(value);
        free(text);
    }
    return id(o);
}
REGISTER("String", "to_number", String_to_number);
// Java's Float.parseFloat: the same grammar, rounded once to 32 bits, so this is strtof and not the
// double answer narrowed.
static union grug_value String_to_float(ARGS) {
    UNUSED;
    char* text = java_number_text(a[0]._string);
    uint64_t o = new_obj(K_OPTION);
    if (text) {
        float value;
        if (strcmp(text, "NaN") == 0 || strcmp(text, "+NaN") == 0 || strcmp(text, "-NaN") == 0) value = NAN;
        else if (strcmp(text, "-Infinity") == 0) value = -INFINITY;
        else if (strcmp(text, "Infinity") == 0 || strcmp(text, "+Infinity") == 0) value = INFINITY;
        else value = strtof(text, NULL);
        objs[o - 1].has = true; objs[o - 1].val = num((double)value);
        free(text);
    }
    return id(o);
}
REGISTER("String", "to_float", String_to_float);
// Java's String.equalsIgnoreCase over code points: equal, or equal upper case, or equal lower case of
// that upper case, in no locale. Java folds the whole of Unicode; this folds ASCII, which is what the
// committed tests compare, and the Java unit test covers a pair outside it.
static unsigned cp_upper_ascii(unsigned c) { return c >= 'a' && c <= 'z' ? c - 32 : c; }
static unsigned cp_lower_ascii(unsigned c) { return c >= 'A' && c <= 'Z' ? c + 32 : c; }
static unsigned cp_take(const char** p) {
    const unsigned char* s = (const unsigned char*)*p;
    unsigned c = *s++;
    if (c >= 0xF0) { c &= 0x07; for (int k = 0; k < 3; k++) c = (c << 6) | (*s++ & 0x3F); }
    else if (c >= 0xE0) { c &= 0x0F; for (int k = 0; k < 2; k++) c = (c << 6) | (*s++ & 0x3F); }
    else if (c >= 0xC0) { c &= 0x1F; c = (c << 6) | (*s++ & 0x3F); }
    *p = (const char*)s;
    return c;
}
static union grug_value String_equals_ignore_case(ARGS) {
    UNUSED;
    const char* x = a[0]._string;
    const char* y = a[1]._string;
    while (*x && *y) {
        unsigned c = cp_take(&x), d = cp_take(&y);
        if (c != d && cp_upper_ascii(c) != cp_upper_ascii(d)
                && cp_lower_ascii(cp_upper_ascii(c)) != cp_lower_ascii(cp_upper_ascii(d)))
            return boolean(false);
    }
    return boolean(*x == 0 && *y == 0);
}
REGISTER("String", "equals_ignore_case", String_equals_ignore_case);
// Java's Integer.parseInt: an optional sign and at least one digit, inside the 32-bit range.
static union grug_value String_to_integer(ARGS) {
    UNUSED;
    const char* t = a[0]._string;
    int i = 0;
    bool negative = false, ok = true;
    if (t[0] == '+' || t[0] == '-') { negative = t[0] == '-'; i = 1; }
    if (!t[i]) ok = false;
    long long v = 0;
    for (; ok && t[i]; i++) {
        if (t[i] < '0' || t[i] > '9') { ok = false; break; }
        v = v * 10 + (t[i] - '0');
        if (v > 2147483648LL) { ok = false; break; }
    }
    if (ok && negative) v = -v;
    if (ok && (v > 2147483647LL || v < -2147483648LL)) ok = false;
    uint64_t o = new_obj(K_OPTION);
    if (ok) { objs[o - 1].has = true; objs[o - 1].val = num((double)v); }
    return id(o);
}
REGISTER("String", "to_integer", String_to_integer);

// =============================================================================================
// SECTION: Generic host functions and print
// =============================================================================================
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
REGISTER(NULL, "equals", equals);
static void print_value(const union grug_value* v, const struct grug_type* t) {
    switch (t->type) {
    case TYPE_NUMBER: printf("%g", v->_number); break;
    case TYPE_BOOL: printf("%s", v->_bool ? "true" : "false"); break;
    case TYPE_STRING: printf("%s", v->_string); break;
    default: printf("<object %llu>", (unsigned long long)v->_id);  // SMOKETEST_EXCL: no committed test prints a class value, and what the adapter's prettyFormat shows for one is not asserted
    }
}
static union grug_value print1(ARGS) { (void)gst; if (quiet) return none(); print_value(&a[0], &g[0]); putchar('\n'); return none(); }
REGISTER(NULL, "print", print1);
static union grug_value print2(ARGS) {
    (void)gst;
    if (quiet) return none();
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar('\n');
    return none();
}
REGISTER(NULL, "print2", print2);
static union grug_value print3(ARGS) {
    (void)gst;
    if (quiet) return none();
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar(' ');
    print_value(&a[2], &g[2]); putchar('\n');
    return none();
}
REGISTER(NULL, "print3", print3);

// =============================================================================================
// SECTION: Items and resource locations
// =============================================================================================
// Resource locations and items. An Item is interned by its name, so grug's `==` on an Item (which
// compares ids) and equals() agree, as they do for the game's one item per name. No item table is
// kept: a name is taken as given, with "minecraft:" in front of a bare one.
static union grug_value hf_resource_location(ARGS) {
    UNUSED;
    uint64_t r = new_obj(K_RL);
    const char* s = a[0]._string;
    size_t n = strlen(s) + 11;
    char* full = xmalloc(n);
    snprintf(full, n, "%s%s", strchr(s, ':') ? "" : "minecraft:", s);
    objs[r - 1].str = keep(full);
    return id(r);
}
REGISTER(NULL, "resource_location", hf_resource_location);
static uint64_t item_named(const char* name) {
    for (size_t i = 0; i < nobjs; i++)
        if (objs[i].kind == K_ITEM && !strcmp(objs[i].str, name)) return i + 1;
    uint64_t it = new_obj(K_ITEM);
    objs[it - 1].str = name;
    return it;
}
static union grug_value hf_item(ARGS) {
    UNUSED;
    struct obj* rl = get_obj(a[0]._id, K_RL);
    if (!rl) return fail("item: not a ResourceLocation");
    return id(item_named(rl->str));
}
REGISTER(NULL, "item", hf_item);

// item_stack (expect_item_stack_count*-Test, item_stack_save-Test): a stack of 1 to 127, the largest count the
// byte-saving loaders keep, which is the limit the smoketest takes because its loader name is not 1.20.6-forge.
static union grug_value hf_item_stack(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_ITEM)) return fail("item_stack: not an Item");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an Item
    double c = a[2]._number;
    if (!(c >= 1 && c <= 127) || c != floor(c)) return fail("item_stack: The count has to be a whole number from 1 to 127, and was %g.", c);
    uint64_t o = new_obj(K_ITEM_STACK);
    objs[o - 1].val = a[0];
    objs[o - 1].x = a[1]._number;
    objs[o - 1].y = c;
    return id(o);
}
REGISTER(NULL, "item_stack", hf_item_stack);

// BlockPos: the two methods the committed block entity scripts and tests call.
static union grug_value BlockPos_above_n(ARGS) {
    UNUSED;
    struct obj* p = get_obj(a[0]._id, K_BLOCKPOS);
    if (!p) return fail("BlockPos.above_n: not a BlockPos");
    double px = p->x, py = p->y, pz = p->z;  // before new_obj, which may move the table
    uint64_t q = new_obj(K_BLOCKPOS);
    objs[q - 1].x = px; objs[q - 1].y = py + a[1]._number; objs[q - 1].z = pz;
    return id(q);
}
REGISTER("BlockPos", "above_n", BlockPos_above_n);
static union grug_value BlockPos_center(ARGS) {
    UNUSED;
    struct obj* p = get_obj(a[0]._id, K_BLOCKPOS);
    if (!p) return fail("BlockPos.center: not a BlockPos");
    double px = p->x, py = p->y, pz = p->z;  // before new_obj, which may move the table
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = px + 0.5; objs[v - 1].y = py + 0.5; objs[v - 1].z = pz + 0.5;
    return id(v);
}
REGISTER("BlockPos", "center", BlockPos_center);

// The player and their inventory, as held_item-Test, hotbar_reset_a-Test and overlay-Test assert them:
// one local player with 36 slots, the first of them the hotbar slot the game has selected, which is
// what Player.get_held_item and TestTickContext.get_held_count read as the hand. The runner empties
// the inventory before every test, set_held_item and clear_held_item write the hand, and a give fills
// the first free slot, so a give lands in the hand only because the test starts with an empty
// inventory, as it does in the game. The hotbar selection never moves and nothing else about the
// player is modelled (position, the hotbar keys and the use of items each wait for a committed test
// that needs them).
#define INV_SLOTS 36
static struct inv_slot { bool has; uint64_t item; double count; } inv[INV_SLOTS];
static union grug_value TTC_get_player(ARGS) { UNUSED; return id(new_obj(K_PLAYER)); }
REGISTER("TestTickContext", "get_player", TTC_get_player);
static union grug_value TTC_set_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.set_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    if (!get_obj(a[2]._id, K_ITEM)) return fail("TestTickContext.set_held_item: not an Item");  // SMOKETEST_EXCL: no committed test
    inv[0].has = true; inv[0].item = a[2]._id; inv[0].count = 1;
    return none();
}
REGISTER("TestTickContext", "set_held_item", TTC_set_held_item);
static union grug_value TTC_clear_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.clear_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    inv[0].has = false; inv[0].count = 0;
    return none();
}
REGISTER("TestTickContext", "clear_held_item", TTC_clear_held_item);
// hotbar_reset_a-Test and overlay-Test: one item in the hand after set_held_item, none after clear_held_item.
static union grug_value TTC_get_held_count(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.get_held_count: not a Player");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but the player
    return num(inv[0].has ? inv[0].count : 0);
}
REGISTER("TestTickContext", "get_held_count", TTC_get_held_count);
static union grug_value Player_get_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_PLAYER)) return fail("Player.get_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    uint64_t o = new_obj(K_OPTION);
    if (inv[0].has) { objs[o - 1].has = true; objs[o - 1].val = id(inv[0].item); }
    return id(o);
}
REGISTER("Player", "get_held_item", Player_get_held_item);
// The player's feet, which a scan is centred on and a test waits for after it moves them (#1255).
// The toy world has one copy and applies the move at once, so this is the answer a real loader's
// player gives on the tick the move is made.
static union grug_value Player_position(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_PLAYER)) return fail("Player.position: not a Player");  // SMOKETEST_EXCL: no committed test
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = player_x; objs[v - 1].y = player_y; objs[v - 1].z = player_z;
    return id(v);
}
REGISTER("Player", "position", Player_position);

// =============================================================================================
// SECTION: Item list
// =============================================================================================
// The host's item list (#865): every Block and Item entity a compiled mod declares, in the grug
// namespace, then a fixed set of the game's own items the committed tests name. A name is the
// registry key, its display name the key without its namespace and with spaces for underscores, so
// the reference's search finds "iron" and "ingot" in "iron ingot". The order is stable: the game's
// items first, then the mods' in compile order, and the first item is not an iron one.
#define TOY_ITEMS 512
#define SEARCH_ALTERNATIVES 8
#define SEARCH_TOKENS 8
struct toy_item { char name[128], display[128], mod[32]; };
static struct toy_item toy_items[TOY_ITEMS];
static size_t ntoy_items;
static struct grug_files_slice* toy_files;

static void toy_item_add(const char* name, const char* display, const char* mod) {
    if (ntoy_items == TOY_ITEMS) return;  // SMOKETEST_EXCL: the mods and the fixed list stay under the cap
    for (size_t i = 0; i < ntoy_items; i++) if (!strcmp(toy_items[i].name, name)) return;  // SMOKETEST_EXCL_BRANCH: no compiled mod declares two items under one name, so the duplicate guard never fires
    snprintf(toy_items[ntoy_items].name, sizeof toy_items[ntoy_items].name, "%s", name);
    snprintf(toy_items[ntoy_items].display, sizeof toy_items[ntoy_items].display, "%s", display);
    snprintf(toy_items[ntoy_items].mod, sizeof toy_items[ntoy_items].mod, "%s", mod);
    ntoy_items++;
}
// A display name is a key's path with spaces for underscores: "minecraft:iron_ingot" is "iron ingot".
static void toy_display(const char* name, char* out, size_t cap) {
    const char* path = strchr(name, ':');
    path = path ? path + 1 : name;  // SMOKETEST_EXCL_BRANCH: every item in the list is named with its namespace
    size_t n = 0;
    for (; *path && n + 1 < cap; path++) out[n++] = *path == '_' ? ' ' : *path;  // SMOKETEST_EXCL_BRANCH: no item name fills the 128-byte display buffer
    out[n] = 0;
}
static void build_toy_items(void) {
    static const char* const vanilla[] = {
        "minecraft:bucket", "minecraft:stone", "minecraft:dirt", "minecraft:apple",
        "minecraft:iron_ingot", "minecraft:iron_ore", "minecraft:iron_block",
        "minecraft:gold_ingot", "minecraft:copper_ingot", "minecraft:coal",
        "minecraft:oak_log", "minecraft:cobblestone", "minecraft:diamond", "minecraft:stick",
        "minecraft:glass", "minecraft:sand", "minecraft:gravel", "minecraft:clay_ball",
    };
    for (size_t i = 0; i < sizeof vanilla / sizeof vanilla[0]; i++) {
        char display[128];
        toy_display(vanilla[i], display, sizeof display);
        toy_item_add(vanilla[i], display, "minecraft");
    }
    for (size_t i = 0; i < toy_files->len; i++) {
        struct grug_file_info* fi = &toy_files->ptr[i];
        if (fi->file_id == UINT64_MAX || !fi->entity_type) continue;  // SMOKETEST_EXCL_BRANCH: a compiled file always has an id and a type
        if (strcmp(fi->entity_type, "Block") && strcmp(fi->entity_type, "Item")) continue;
        char name[128], display[128];
        snprintf(name, sizeof name, "grug:%s_%s", fi->mod_name, fi->entity_name);
        toy_display(name, display, sizeof display);
        toy_item_add(name, display, "grug");
    }
}

// The largest stack a vanilla item comes in, as the game's own items answer it: a bucket and a
// diamond sword arrive on their own and every block and ingredient item in the fixture list stacks
// to 64, which are the values the committed tests ask about (overlay-Test asks item_at(0), a bucket,
// give_item_stack-Test asks item_at(1), a block, and item_max_stack-Test asks a diamond sword). A
// name outside the table stacks to 64, and adding a 16-stack item later is a row here rather than
// another branch at each caller (#865).
static double item_max_stack(const char* name) {
    static const struct { const char* name; double size; } sizes[] = {
        {"minecraft:bucket", 1},
        {"minecraft:diamond_sword", 1},
    };
    for (size_t i = 0; i < sizeof sizes / sizeof sizes[0]; i++)
        if (!strcmp(name, sizes[i].name)) return sizes[i].size;
    return 64;
}

static union grug_value hf_item_max_stack(ARGS) {
    UNUSED;
    struct obj* item = get_obj(a[0]._id, K_ITEM);
    if (!item) return fail("item_max_stack: not an Item");  // SMOKETEST_EXCL_BRANCH: no committed test asks the stack size of anything but an item
    return num(item_max_stack(item->str));
}
REGISTER(NULL, "item_max_stack", hf_item_max_stack);

// GrugItemSearch's tokenizer: the text is lower cased and split on `|`, and an alternative is a run
// of tokens of which the ones with a leading minus remove. A token is a quoted run or a run of
// non-space characters, a lone prefix or a lone minus is no token, and an alternative with nothing
// to keep is dropped. An item is listed when every keep token of an alternative matches and none of
// its remove tokens does; a text with no alternative left lists every item.
#define QUOTE '"'  // a token's quote, which the search drops
struct search_token { char text[128]; bool mod_id; };
struct search_alternative { size_t nkeep, nremove; struct search_token keep[SEARCH_TOKENS], remove[SEARCH_TOKENS]; };

static void search_token_add(struct search_alternative* alt, bool negated, const char* raw) {
    char text[128];
    size_t n = 0;
    for (const char* p = raw; *p && n + 1 < sizeof text; p++) if (*p != QUOTE) text[n++] = *p;  // SMOKETEST_EXCL_BRANCH: no committed search text has a 128-byte word
    text[n] = 0;
    if (!text[0]) return;
    struct search_token token;
    if (text[0] == '@') {
        if (!text[1]) return;
        snprintf(token.text, sizeof token.text, "%s", text + 1);
        token.mod_id = true;
    } else {
        snprintf(token.text, sizeof token.text, "%s", text);
        token.mod_id = false;
    }
    struct search_token* list = negated ? alt->remove : alt->keep;
    size_t* count = negated ? &alt->nremove : &alt->nkeep;
    if (*count == SEARCH_TOKENS) return;  // SMOKETEST_EXCL: no committed search text has this many tokens
    list[(*count)++] = token;
}

static size_t search_parse(const char* text, struct search_alternative* alts) {
    size_t n = strlen(text);
    char* lower = xmalloc(n + 1);
    for (size_t i = 0; i <= n; i++) lower[i] = text[i] >= 'A' && text[i] <= 'Z' ? text[i] + 32 : text[i];
    size_t nalts = 0;
    char* segment = lower;
    for (;;) {
        char* end = strchr(segment, '|');
        char* stop = end ? end : segment + strlen(segment);
        struct search_alternative alt;
        memset(&alt, 0, sizeof alt);
        char* p = segment;
        while (p < stop) {
            while (p < stop && isspace((unsigned char)*p)) p++;
            if (p >= stop) break;
            bool negated = false;
            char raw[256];
            size_t rn = 0;
            if ((p[0] == '-' && p + 1 < stop && p[1] == QUOTE) || p[0] == QUOTE) {
                // Only a minus before the quote negates a quoted token, as GrugItemSearch's pattern
                // matches the token: a minus inside the quotes is part of the word to keep.
                if (p[0] == '-') { negated = true; p++; }
                p++;  // the opening quote
                while (p < stop && *p != QUOTE) { if (rn + 1 < sizeof raw) raw[rn++] = *p; p++; }  // SMOKETEST_EXCL_BRANCH: no committed search text has a 255-byte quoted word
                if (p < stop) p++;  // the closing quote
            } else {
                // An unquoted token is negated by its own first character, before any quote is dropped.
                if (p[0] == '-') { negated = true; p++; }
                while (p < stop && !isspace((unsigned char)*p)) { if (rn + 1 < sizeof raw) raw[rn++] = *p; p++; }  // SMOKETEST_EXCL_BRANCH: no committed search text has a 255-byte word
            }
            raw[rn] = 0;
            search_token_add(&alt, negated, raw);
        }
        // An alternative with nothing to keep is no alternative: its minus words describe nothing.
        if (alt.nkeep && nalts < SEARCH_ALTERNATIVES) alts[nalts++] = alt;  // SMOKETEST_EXCL_BRANCH: no committed search text has more than eight alternatives
        if (!end) break;
        segment = end + 1;
    }
    free(lower);
    return nalts;
}

static bool search_token_matches(size_t item, const struct search_token* token) {
    const char* haystack = token->mod_id ? toy_items[item].mod : toy_items[item].display;
    return strstr(haystack, token->text) != NULL;
}

static bool search_lists(size_t item, const struct search_alternative* alts, size_t nalts) {
    if (!nalts) return true;
    for (size_t a = 0; a < nalts; a++) {
        bool all = true, any = false;
        for (size_t t = 0; t < alts[a].nkeep && all; t++) all = search_token_matches(item, &alts[a].keep[t]);
        for (size_t t = 0; t < alts[a].nremove && !any; t++) any = search_token_matches(item, &alts[a].remove[t]);
        if (all && !any) return true;
    }
    return false;
}

// The indexes of the items the text lists, in list order, written to `out`; the answer is their
// number, which is the whole list when the text holds no word to search.
static size_t search_items(const char* text, size_t* out) {
    struct search_alternative alts[SEARCH_ALTERNATIVES];
    size_t nalts = search_parse(text, alts);
    size_t n = 0;
    for (size_t i = 0; i < ntoy_items; i++) if (search_lists(i, alts, nalts)) out[n++] = i;
    return n;
}

// Why an index is not a whole number from 0 to Integer.MAX_VALUE, or false.
static bool toy_index_problem(double index, char* out, size_t cap) {
    if (index >= 0 && index <= 2147483647.0 && index == floor(index)) return false;
    char t[400];
    double_text(index, t, sizeof t);
    snprintf(out, cap, "The index has to be a whole number from 0 to 2147483647, and was %s.", t);
    return true;
}

static union grug_value hf_item_count(ARGS) {
    UNUSED;
    return num((double)ntoy_items);
}
REGISTER(NULL, "item_count", hf_item_count);
static union grug_value hf_item_at(ARGS) {
    UNUSED;
    char problem[512];
    if (toy_index_problem(a[0]._number, problem, sizeof problem)) return fail("item_at: %s", problem);
    int index = (int)a[0]._number;
    if ((size_t)index >= ntoy_items) return fail("item_at: There is no item at index %d, since item_count is %zu.", index, ntoy_items);
    return id(item_named(toy_items[index].name));
}
REGISTER(NULL, "item_at", hf_item_at);
static union grug_value hf_search_item_count(ARGS) {
    UNUSED;
    size_t* indexes = xmalloc(ntoy_items * sizeof *indexes);
    size_t n = search_items(a[0]._string, indexes);
    free(indexes);
    return num((double)n);
}
REGISTER(NULL, "search_item_count", hf_search_item_count);
static union grug_value hf_search_item_at(ARGS) {
    UNUSED;
    char problem[512];
    if (toy_index_problem(a[1]._number, problem, sizeof problem)) return fail("search_item_at: %s", problem);
    size_t* indexes = xmalloc(ntoy_items * sizeof *indexes);
    size_t n = search_items(a[0]._string, indexes);
    int index = (int)a[1]._number;
    if ((size_t)index >= n) {
        free(indexes);
        return fail("search_item_at: There is no item at index %d, since search_item_count is %zu.", index, n);
    }
    uint64_t item = item_named(toy_items[indexes[index]].name);
    free(indexes);
    return id(item);
}
REGISTER(NULL, "search_item_at", hf_search_item_at);

// A give fills the player's inventory the way the game does: the stack goes into the first free slot,
// and a give that finds every slot full drops it at the player's feet. The committed tests read it
// back with Player.get_held_item and TestTickContext.get_held_count because the runner empties the
// inventory before every test and the game has the first hotbar slot selected, so the first free slot
// is the hand. give_item gives one item and give_item_stack the item's own maximum stack, the
// reference's cheat bindings. The drop lives with the entity table, below.
static void give_to_player(uint64_t item_id, double count);
static union grug_value hf_give_item(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_ITEM)) return fail("give_item: not an Item");  // SMOKETEST_EXCL_BRANCH: grug type-checks the argument, so a test cannot give anything but an Item
    give_to_player(a[0]._id, 1);
    return none();
}
REGISTER(NULL, "give_item", hf_give_item);
static union grug_value hf_give_item_stack(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_ITEM)) return fail("give_item_stack: not an Item");  // SMOKETEST_EXCL_BRANCH: grug type-checks the argument, so a test cannot give anything but an Item
    struct obj* item = get_obj(a[0]._id, K_ITEM);
    give_to_player(a[0]._id, item_max_stack(item->str));
    return none();
}
REGISTER(NULL, "give_item_stack", hf_give_item_stack);

// =============================================================================================
// SECTION: BlockEntity
// =============================================================================================
// Block entities. A block with a BlockEntity script gets a grug entity when it is placed, whose
// init() runs at once (block_entity_init_moment-Test) and whose tick() runs once per runner tick
// (the toy world has no game-tick/frame split). A block entity's handle is its grug entity's id,
// which is what `me` holds, set with grug_set_next_entity_id before the entity is created because
// the member scope already calls me.block_pos(). The ids start far above the object table's.
#define BE_BASE (1ull << 40)
struct kv { char* k; double v; };
struct record { struct kv* n; size_t nn; uint64_t item; bool has_item; char* text; };
struct text { char* k; char* v; };
struct list { char* name; struct record* r; size_t nr; };
struct slot { bool has; uint64_t item; double count, damage; };
struct be {
    bool live;
    bool doomed;  // replaced or removed while one of its calls was running; freed when the call returns
    long x, y, z;
    void* entity;
    struct kv* nums; size_t nnums;
    struct list* lists; size_t nlists;
    struct text* strs; size_t nstrs;
    struct slot* slots; size_t nslots;  // the inventory, sized by Block.init's set_inventory_size (a chest has 27)
    bool power; double energy, max_energy;  // set_power_receiver
    bool item_inserted;   // the script declares an item_inserted export
    bool item_extracted;  // the script declares an item_extracted export, which no host call here runs yet
    bool output_taken;    // the script declares an output_taken export, which a take of the cached result runs
    bool has_result; const char* res_name; double res_count, res_damage;  // the cached crafting result
    bool has_insertable, has_extractable; double ins_first, ins_last, ext_first, ext_last;  // declared slot ranges
};
static struct be* bes;
static size_t nbes, capbes;

static struct files_ref {
    struct grug_files_slice f;
    uint64_t block_init;
    uint64_t be_init;
    uint64_t be_tick;
    uint64_t be_extract_item;
    uint64_t be_add_item;
    uint64_t be_offer_liquid;
    uint64_t be_take_liquid;
    uint64_t be_get_liquid;
    uint64_t be_get_liquid_amount;
    uint64_t be_get_liquid_capacity;
} G;
static char bg_missing[4096];   // a "does not implement" raised inside a block entity call
static char last_error[4096];  // the last runtime error the handler saw, with its source line and call stack
static char last_msg[4096];    // the same error's message alone, which is what expect_error matches

static struct be* be_of(uint64_t h) { return h >= BE_BASE && h - BE_BASE < nbes && bes[h - BE_BASE].live ? &bes[h - BE_BASE] : NULL; }

// Runs a block entity or block export. A grug mod err is contained, as in the game; the one error
// that is not is a missing smoketest entry, which would make every later assertion meaningless.
static int call_depth;
static void reap_bes(void);
// Returns whether the export ran (false for an entity without it) and, in `err` when not NULL, the
// message of an error it raised, which the caller may re-raise.
static bool call_export(void* entity, uint64_t fn, const union grug_value* args, size_t n, char* err) {
    char saved[sizeof last_error], saved_msg[sizeof last_msg];
    memcpy(saved, last_error, sizeof saved);
    memcpy(saved_msg, last_msg, sizeof saved_msg);
    last_error[0] = 0;
    call_depth++;
    bool ok = grug_call_export_fn(g_state, entity, fn, args, n);
    if (!ok && strstr(last_error, "does not implement") && !bg_missing[0])
        snprintf(bg_missing, sizeof bg_missing, "%s", last_error);  // SMOKETEST_EXCL: reached only when a block entity export calls an entry the smoketest lacks, which a passing test never does
    bool ran = ok || last_error[0];
    // The message is read only when the export raised an error: a missing export fails without one, and
    // last_msg then still holds an earlier error's text.
    if (err) snprintf(err, 4096, "%s", !ok && last_error[0] ? last_msg : "");
    // A block entity script can place or break blocks, so `bes` may have moved and a block entity
    // may have been replaced while it ran: nothing holds a `struct be*` across this call, and a
    // replaced one is only marked until the outermost call returns (see free_be).
    if (--call_depth == 0) reap_bes();
    memcpy(last_error, saved, sizeof saved);
    memcpy(last_msg, saved_msg, sizeof saved_msg);
    return ran;
}
static void call_contained(void* entity, uint64_t fn, const union grug_value* args, size_t n) { (void)call_export(entity, fn, args, n, NULL); }

// =============================================================================================
// SECTION: World overlay entities and frames
// =============================================================================================
// Every Overlay file of the running test's mod is an entity for the test, as the game has one for
// the whole run. A frame asks each one's render_world after the test tick, with the camera and the
// player's feet that frame draws from, and a key press asks world_key and then every binding whose
// declared default key is the pressed one, which is how a loader without binding registration
// delivers it. A pass is open only around its export call, so a context saved for a later call is
// refused, as the game refuses it.
static double world_pass_serial;  // 0 while no pass is open
static double world_pass_count;   // the serial the last opened pass took, which never repeats
static double overlay_key_code;
static bool overlay_ctrl;
static const char* overlay_binding_id;
static double camera_x, camera_y, camera_z;

static void clear_world_overlays(void) {
    world_overlay_drawn_flag = false;
    world_pass_mod = NULL;
}
// Asks one export of every overlay of the running test's mod, with a fresh context of the given
// kind. A file without the export answers false and no error; an error the export raised is
// reported as the test's own, because a contained err would leave an overlay that silently draws
// nothing. The serial advances on every call and is never reused, so a context a script saved from
// an earlier call is refused rather than taken for the current one (#1749's review).
static void ask_world_overlays(uint64_t fn, enum kind kind, const char* name) {
    for (size_t i = 0; i < noverlays; i++) {
        if (!cur_test_mod || strcmp(overlays[i].mod, cur_test_mod)) continue;
        if (overlay_disabled(overlays[i].path)) continue;
        char err[sizeof last_msg];
        world_pass_serial = ++world_pass_count;
        uint64_t ctx = new_obj(kind);
        objs[ctx - 1].x = world_pass_serial;
        union grug_value arg = id(ctx);
        world_pass_mod = overlays[i].mod;
        bool ran = call_export(overlays[i].entity, fn, &arg, 1, err);
        world_pass_mod = NULL;
        world_pass_serial = 0;
        if (!ran) continue;
        if (err[0] && !bg_missing[0]) snprintf(bg_missing, sizeof bg_missing, "an overlay's %.100s stopped: %.3900s", name, err);
    }
}
static void overlay_render_world(void) {
    camera_x = player_x;
    camera_y = player_y + 1.62;  // the eye, as TestTickContext.camera answers it
    camera_z = player_z;
    if (world_render_fn != UINT64_MAX) ask_world_overlays(world_render_fn, K_WORLD_CTX, "render_world");
}
static void overlay_press_world_key(double code, bool ctrl) {
    world_overlay_drawn_flag = false;
    overlay_key_code = code;
    overlay_ctrl = ctrl;
    if (world_key_fn != UINT64_MAX) ask_world_overlays(world_key_fn, K_WORLD_KEY_CTX, "world_key");
    for (size_t i = 0; i < noverlays; i++) {
        const char* mod = overlays[i].mod;
        if (!cur_test_mod || strcmp(mod, cur_test_mod)) continue;
        if (overlay_disabled(overlays[i].path)) continue;
        for (size_t k = 0; k < nmod_keys; k++) {
            if (strcmp(mod_keys[k].mod, mod) || mod_keys[k].def[0] != (char)code) continue;
            char err[sizeof last_msg];
            overlay_binding_id = mod_keys[k].id;
            world_pass_serial = ++world_pass_count;
            uint64_t ctx = new_obj(K_BINDING_CTX);
            objs[ctx - 1].x = world_pass_serial;
            union grug_value arg = id(ctx);
            world_pass_mod = mod;
            bool ran = call_export(overlays[i].entity, world_binding_fn, &arg, 1, err);
            world_pass_mod = NULL;
            world_pass_serial = 0;
            overlay_binding_id = NULL;
            if (ran && err[0] && !bg_missing[0]) snprintf(bg_missing, sizeof bg_missing, "an overlay's world_binding stopped: %.3900s", err);
        }
    }
    overlay_key_code = 0;
    overlay_ctrl = false;
}

static void free_list(struct list* l) {
    for (size_t i = 0; i < l->nr; i++) {
        for (size_t j = 0; j < l->r[i].nn; j++) free(l->r[i].n[j].k);
        free(l->r[i].n);
        free(l->r[i].text);
    }
    free(l->r); free(l->name);
}
static void release_be(struct be* b) {
    if (b->entity) grug_deinit_entity(g_state, b->entity);
    for (size_t i = 0; i < b->nnums; i++) free(b->nums[i].k);
    free(b->nums);
    for (size_t i = 0; i < b->nlists; i++) free_list(&b->lists[i]);
    free(b->lists);
    for (size_t i = 0; i < b->nstrs; i++) { free(b->strs[i].k); free(b->strs[i].v); }
    free(b->strs);
    free(b->slots);
    memset(b, 0, sizeof *b);
}
// Dropping a block entity whose script is running (a tick that replaces its own block) must not
// deinit the entity under the VM, so inside any call it is only marked and freed by reap_bes.
static void free_be(struct be* b) {
    if (!b->live) return;
    if (call_depth > 0) { b->live = false; b->doomed = true; return; }
    release_be(b);
}
static void reap_bes(void) {
    for (size_t i = 0; i < nbes; i++) if (bes[i].doomed) release_be(&bes[i]);
}
static void clear_bes(void) {
    for (size_t i = 0; i < nbes; i++) if (bes[i].live || bes[i].doomed) release_be(&bes[i]);
    nbes = 0;
}

// Per-block registry: Block.init runs once per test on first placement, with set_block_entity,
// set_inventory_size, set_shape and set_drops_nothing the only BlockInitContext entries the toy
// world records. Any other setter a block calls leaves the test STUBBED, since nothing here reads
// what it set.
struct blockdef { const char* name; long be_file; long slots; bool shape; bool drops_nothing; };
static struct blockdef* blockdefs;
static size_t nblockdefs;
// Block.init's own results. Block.init can place another undefined block, whose init nests inside
// this one, so find_blockdef saves and restores it and keeps no pointer into `blockdefs`.
struct binit { long be_file; long slots; const char* mod; bool shape; bool drops_nothing; };
static struct binit cur_binit;

// An entity string without a mod prefix names an entity of the mod the script is in (the grug
// language's rule; coverage_fixture-Block.grug names "coverage_fixture_entity" that way).
static union grug_value BIC_set_block_entity(ARGS) {
    UNUSED;
    const char* e = a[1]._string;
    const char* colon = strchr(e, ':');
    size_t modlen = colon ? (size_t)(colon - e) : strlen(cur_binit.mod);
    const char* mod = colon ? e : cur_binit.mod;
    e = colon ? colon + 1 : e;
    for (size_t i = 0; i < G.f.len; i++) {
        struct grug_file_info* fi = &G.f.ptr[i];
        if (fi->entity_type && !strcmp(fi->entity_type, "BlockEntity") && !strcmp(fi->entity_name, e) && strlen(fi->mod_name) == modlen && !strncmp(fi->mod_name, mod, modlen))
            cur_binit.be_file = (long)i;
    }
    return none();
}
REGISTER("BlockInitContext", "set_block_entity", BIC_set_block_entity);
// The remaining setters record nothing, and no committed test reads what they set, so a test that
// calls one is STUBBED: it passed without the property having any effect. (The toy world has no
// mining, materials, shapes or drawing; a test that needs one fails on that entry by name.)
static union grug_value BIC_noop(ARGS) { UNUSED; stubbed = true; return none(); }
// inventory_size-Test, set_item_out_of_range-Test and block_entity_me-Test read the size back. The
// 1024 cap is this host's, not the game's, so a bigger size fails by name instead of allocating.
static union grug_value BIC_set_inventory_size(ARGS) {
    UNUSED;
    double n = a[1]._number;
    if (!is_whole(n) || n < 0 || n > 1024) return fail("BlockInitContext.set_inventory_size: the smoketest models whole sizes from 0 to 1024");  // SMOKETEST_EXCL: no committed test (the 1024 cap is this host's own)
    cur_binit.slots = (long)n;
    return none();
}
REGISTER("BlockInitContext", "set_inventory_size", BIC_set_inventory_size);
REGISTER("BlockInitContext", "set_hardness", BIC_noop);
REGISTER("BlockInitContext", "set_material", BIC_noop);
// set_shape declares that the game asks the block's shape() export for its boxes (block_collision reads it).
static union grug_value BIC_set_shape(ARGS) { UNUSED; cur_binit.shape = true; return none(); }
REGISTER("BlockInitContext", "set_shape", BIC_set_shape);
// set_drops_nothing declares that breaking the block leaves no item (drops_nothing-Test).
static union grug_value BIC_set_drops_nothing(ARGS) { UNUSED; cur_binit.drops_nothing = true; return none(); }
REGISTER("BlockInitContext", "set_drops_nothing", BIC_set_drops_nothing);
REGISTER("BlockInitContext", "set_display_tick", BIC_noop);
REGISTER("BlockInitContext", "set_flat_lighting", BIC_noop);
REGISTER("BlockInitContext", "set_frame_render", BIC_noop);
REGISTER("BlockInitContext", "set_custom_render", BIC_noop);

enum { BLOCK_UNRESOLVED = -2, BLOCK_NO_ENTITY = -1 };  // anything else is the BlockEntity file's index
// Runs Block.init once per name per test and answers the block's BlockEntity file. A "grug:" name
// that no Block script declares is unresolved (expect_place_block_unknown-Test); a name of another
// namespace has no table here and is taken as given.
static long resolved_slots;  // the inventory size of the block resolve_block last answered
static long resolve_block(const char* name) {
    resolved_slots = 0;
    if (strncmp(name, "grug:", 5)) return BLOCK_NO_ENTITY;
    for (size_t i = 0; i < nblockdefs; i++) if (!strcmp(blockdefs[i].name, name)) { resolved_slots = blockdefs[i].slots; return blockdefs[i].be_file; }
    for (size_t i = 0; i < G.f.len; i++) {
        struct grug_file_info* fi = &G.f.ptr[i];
        if (fi->file_id == UINT64_MAX || !fi->entity_type || strcmp(fi->entity_type, "Block") || strcmp(fi->entity_name, name + 5)) continue;
        blockdefs = xrealloc(blockdefs, (nblockdefs + 1) * sizeof *blockdefs);
        size_t idx = nblockdefs++;
        blockdefs[idx].name = keep(xstrdup(name));
        blockdefs[idx].be_file = BLOCK_NO_ENTITY;
        blockdefs[idx].shape = false;
        // Block.init has no entity to hold: create, run, drop.
        void* ent = grug_create_entity(g_state, fi->file_id);
        struct binit outer = cur_binit;
        cur_binit.be_file = BLOCK_NO_ENTITY;
        cur_binit.slots = 0;
        cur_binit.shape = false;
        cur_binit.drops_nothing = false;
        cur_binit.mod = fi->mod_name;
        union grug_value arg = id(new_obj(K_CTX));
        call_contained(ent, G.block_init, &arg, 1);
        long result = cur_binit.be_file, slots = cur_binit.slots;
        bool shape = cur_binit.shape;
        bool drops_nothing = cur_binit.drops_nothing;
        cur_binit = outer;
        blockdefs[idx].be_file = result;
        blockdefs[idx].slots = slots;
        blockdefs[idx].shape = shape;
        blockdefs[idx].drops_nothing = drops_nothing;
        resolved_slots = slots;
        grug_deinit_entity(g_state, ent);
        return result;
    }
    return BLOCK_UNRESOLVED;
}

static struct be* be_at(long x, long y, long z) {
    for (size_t i = 0; i < nbes; i++) if (bes[i].live && bes[i].x == x && bes[i].y == y && bes[i].z == z) return &bes[i];
    return NULL;
}
static size_t cur_init_be;  // 1 + the index of the block entity whose init() is running (0: none); an index, since `bes` can move
static void give_slots(struct be* b, long n) {
    b->slots = n ? xmalloc((size_t)n * sizeof *b->slots) : NULL;
    if (n) memset(b->slots, 0, (size_t)n * sizeof *b->slots);
    b->nslots = (size_t)n;
}
static const char* g_mods_dir;
// Whether a block entity's script text declares an export, found by reading the file: grug-rs
// answers a call on an entity without the export with false and no error, which cannot be told from
// an export that ran, so this is how the host learns what to leave stubbed.
static bool file_has_export(long file, const char* name) {
    char path[4096], needle[128], buf[65536];
    snprintf(path, sizeof path, "%s/%s", g_mods_dir, G.f.ptr[file].path);
    snprintf(needle, sizeof needle, "export %s(", name);
    FILE* f = fopen(path, "rb");
    if (!f) return false;  // SMOKETEST_EXCL: a compiled file is always readable
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[n] = 0;
    return strstr(buf, needle) != NULL;
}
static void make_be(long x, long y, long z, long file, long slots) {
    if (nbes == capbes) { capbes = capbes ? capbes * 2 : 32; bes = xrealloc(bes, capbes * sizeof *bes); }
    size_t idx = nbes++;  // an index, not a pointer: the member scope below can place blocks
    memset(&bes[idx], 0, sizeof bes[idx]);
    bes[idx].live = true; bes[idx].x = x; bes[idx].y = y; bes[idx].z = z;
    give_slots(&bes[idx], slots);
    bes[idx].item_extracted = file_has_export(file, "item_extracted");
    bes[idx].item_inserted = file_has_export(file, "item_inserted");
    bes[idx].output_taken = file_has_export(file, "output_taken");
    grug_set_next_entity_id(g_state, BE_BASE + idx);
    call_depth++;
    void* entity = grug_create_entity(g_state, G.f.ptr[file].file_id);
    call_depth--;
    bes[idx].entity = entity;
    union grug_value arg = id(new_obj(K_CTX));
    // init() can place a block that makes its own block entity, whose init runs inside this one.
    size_t outer = cur_init_be;
    cur_init_be = idx + 1;
    call_contained(entity, G.be_init, &arg, 1);
    cur_init_be = outer;
}
static long resolve_block_fwd(const char* name) { return resolve_block(name); }
static void on_block_placed(long x, long y, long z, const char* name) {
    struct be* old = be_at(x, y, z);
    if (old) free_be(old);
    long file = resolve_block(name);
    long slots = resolved_slots;
    if (file >= 0) make_be(x, y, z, file, slots);
    else if (!strcmp(name, "minecraft:chest") || (file == BLOCK_NO_ENTITY && !strncmp(name, "grug:", 5))) {
        // The vanilla chest (chest_inventory-Test and inventory_size-Test assert its 27 slots,
        // power-Test and expect_receive_energy_not_a_receiver-Test that it is no receiver), and a grug
        // block with no BlockEntity script, which still gets a block entity whose init finds no script
        // (block_entity_no_script-Test). Neither has a script, so neither ticks.
        if (nbes == capbes) { capbes = capbes ? capbes * 2 : 32; bes = xrealloc(bes, capbes * sizeof *bes); }
        struct be* b = &bes[nbes++];
        memset(b, 0, sizeof *b);
        b->live = true; b->x = x; b->y = y; b->z = z;
        give_slots(b, !strcmp(name, "minecraft:chest") ? 27 : slots);
    }
}
static void tick_bes(void) {
    // Whether a block entity ticks before or after the test's own tick is not fixed by any committed
    // test, nor is the order of block entities among themselves; this runs them after the test, in
    // the order they were created.
    for (size_t i = 0; i < nbes; i++)
        if (bes[i].live && bes[i].entity) call_contained(bes[i].entity, G.be_tick, NULL, 0);
}

static union grug_value Level_get_block_entity(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a, p, "Level.get_block_entity")) return none();
    struct be* b = be_at(p[0], p[1], p[2]);
    uint64_t o = new_obj(K_OPTION);
    if (b) { objs[o - 1].has = true; objs[o - 1].val = id(BE_BASE + (uint64_t)(b - bes)); }
    return id(o);
}
REGISTER("Level", "get_block_entity", Level_get_block_entity);
static union grug_value BE_block_pos(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.block_pos: not a live block entity");
    uint64_t p = new_obj(K_BLOCKPOS);
    objs[p - 1].x = (double)b->x; objs[p - 1].y = (double)b->y; objs[p - 1].z = (double)b->z;
    return id(p);
}
REGISTER("BlockEntity", "block_pos", BE_block_pos);
static union grug_value BE_level(ARGS) { UNUSED; return id(new_obj(K_LEVEL)); }
REGISTER("BlockEntity", "level", BE_level);

// Energy (power-Test, power_nbt_round_trip-Test's first half, expect_*energy* tests). The store is a
// whole number from 0 to 2147483647, which the tests assert only as the text "has to be a whole
// number from 0 to".
static union grug_value BIC_set_power_receiver(ARGS) {
    UNUSED;
    double m = a[1]._number;
    if (!cur_init_be) return fail("BlockEntityInitContext.set_power_receiver: only legal while init() is running");  // SMOKETEST_EXCL: no committed test
    if (!is_whole(m) || m < 0 || m > 2147483647.0) return fail("max_stored has to be a whole number from 0 to 2147483647");  // SMOKETEST_EXCL: no committed test
    struct be* b = &bes[cur_init_be - 1];
    b->power = true;
    b->max_energy = m;
    if (b->energy > m) b->energy = m;  // power_declare_again-Test: cut down, never raised
    return none();
}
REGISTER("BlockEntityInitContext", "set_power_receiver", BIC_set_power_receiver);
static union grug_value BE_is_power_receiver(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.is_power_receiver: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return boolean(b->power);
}
REGISTER("BlockEntity", "is_power_receiver", BE_is_power_receiver);
static struct be* receiver_of(uint64_t h, const char* who) {
    struct be* b = be_of(h);
    if (!b) fail("%s: not a live block entity", who);  // SMOKETEST_EXCL: no committed test
    else if (!b->power) { fail("%s: this block entity is not an energy receiver", who); b = NULL; }
    return b;
}
static union grug_value BE_get_energy_stored(ARGS) {
    UNUSED;
    struct be* b = receiver_of(a[0]._id, "BlockEntity.get_energy_stored");
    return b ? num(b->energy) : none();
}
REGISTER("BlockEntity", "get_energy_stored", BE_get_energy_stored);
static union grug_value BE_receive_energy(ARGS) {
    UNUSED;
    struct be* b = receiver_of(a[0]._id, "BlockEntity.receive_energy");
    if (!b) return none();
    double n = a[1]._number;
    if (!is_whole(n) || n < 0 || n > 2147483647.0) return fail("BlockEntity.receive_energy: the amount has to be a whole number from 0 to 2147483647");  // expect_receive_energy_fractional-Test cites the fractional case; a negative or huge amount has no test
    double room = b->max_energy - b->energy, took = n < room ? n : room;
    b->energy += took;
    return num(took);
}
REGISTER("BlockEntity", "receive_energy", BE_receive_energy);
static union grug_value BE_extract_energy(ARGS) {
    UNUSED;
    struct be* b = receiver_of(a[0]._id, "BlockEntity.extract_energy");
    if (!b) return none();
    double n = a[1]._number;
    if (!is_whole(n) || n < 0 || n > 2147483647.0) return fail("BlockEntity.extract_energy: the most to take has to be a whole number from 0 to 2147483647");  // expect_extract_energy_negative-Test cites the negative case; a fractional or huge one has no test
    double took = n < b->energy ? n : b->energy;
    b->energy -= took;
    return num(took);
}
REGISTER("BlockEntity", "extract_energy", BE_extract_energy);

// Inventory slots. A write to a slot that does not exist is an error naming "slot" (set_item_out_of_range-Test);
// a read of one answers empty (inventory_ops-Test).
static union grug_value BE_get_inventory_size(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_inventory_size: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return num((double)b->nslots);
}
REGISTER("BlockEntity", "get_inventory_size", BE_get_inventory_size);
// Slot 999 reading as empty is inventory_ops-Test's; a negative or fractional slot has no test, so it
// is answered as out of range and the call is marked stubbed.
static bool slot_in_range(const struct be* b, double s) {
    if (s >= 0 && s < (double)b->nslots && is_whole(s)) return true;
    if (!is_whole(s) || s < 0) stubbed = true;  // SMOKETEST_EXCL: no committed test
    return false;
}
static struct slot* slot_of(struct be* b, double s) { return slot_in_range(b, s) ? &b->slots[(size_t)s] : NULL; }
static union grug_value BE_get_item_count_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_item_count_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    return num(sl ? sl->count : 0);
}
REGISTER("BlockEntity", "get_item_count_in_slot", BE_get_item_count_in_slot);
static uint64_t be_inserted_fn, be_neighbor_fn, be_output_taken_fn;
static union grug_value BE_set_item_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_item_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!slot_in_range(b, a[1]._number)) return fail("BlockEntity.set_item_in_slot: there is no slot %g, the inventory has %zu", a[1]._number, b->nslots);
    struct slot* sl = &b->slots[(size_t)a[1]._number];
    // The tests write whole positive counts; any other count (negative, fractional, NaN) is not
    // asserted by a test, so it is stored as given and the test is marked stubbed.
    // A count above the item's stack limit would be clamped by some loaders, which the smoketest has no table for.
    if (!(is_whole(a[3]._number) && a[3]._number > 0) || a[3]._number > 64) stubbed = true;  // SMOKETEST_EXCL: no committed test
    // The 1.2.5 adapter writes `new ItemStack(item, count)`, so the stack starts undamaged.
    sl->has = true; sl->item = a[2]._id; sl->count = a[3]._number; sl->damage = 0;
    // block_entity_me-Test: the block entity's item_inserted runs at once, once per write. Its amount is
    // the count written, which no committed test reads.
    if (b->entity) {
        union grug_value args[2] = {num(a[1]._number), num(a[3]._number)};
        call_contained(b->entity, be_inserted_fn, args, 2);
    }
    return none();
}
REGISTER("BlockEntity", "set_item_in_slot", BE_set_item_in_slot);
// The other slot calls inventory_ops-Test asserts: an empty slot reads as no item and no damage, a
// write to a slot that does not exist or to an empty one is ignored, a count of 0 empties the slot,
// and taking more than is there removes what is there.
static void slot_clear(struct slot* sl) { memset(sl, 0, sizeof *sl); }
static union grug_value BE_get_item_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_item_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    uint64_t o = new_obj(K_OPTION);
    if (sl && sl->has && sl->count > 0) { objs[o - 1].has = true; objs[o - 1].val = id(sl->item); }
    return id(o);
}
REGISTER("BlockEntity", "get_item_in_slot", BE_get_item_in_slot);
static union grug_value BE_get_item_damage_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_item_damage_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    return num(sl && sl->has ? sl->damage : 0);
}
REGISTER("BlockEntity", "get_item_damage_in_slot", BE_get_item_damage_in_slot);
static union grug_value BE_set_item_damage_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_item_damage_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    // Damage is a whole number in the game (the adapter truncates it to an int); inventory_ops-Test sets whole ones.
    if (!(is_whole(a[2]._number) && a[2]._number >= 0)) stubbed = true;  // SMOKETEST_EXCL: no committed test
    if (sl && sl->has) sl->damage = a[2]._number;
    return none();
}
REGISTER("BlockEntity", "set_item_damage_in_slot", BE_set_item_damage_in_slot);
static union grug_value BE_set_item_count_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_item_count_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    if (!sl || !sl->has) return none();
    if (a[2]._number == 0) slot_clear(sl);
    else sl->count = a[2]._number;
    if (!(is_whole(a[2]._number) && a[2]._number >= 0) || a[2]._number > 64) stubbed = true;  // SMOKETEST_EXCL: no committed test
    return none();
}
REGISTER("BlockEntity", "set_item_count_in_slot", BE_set_item_count_in_slot);
// How many of a slot's stack leave when `amount` are asked for, and the slot emptied when none is left.
static double slot_take(struct slot* sl, double amount) {
    // inventory_ops-Test takes whole positive amounts. The adapter truncates one to an int and a
    // negative one takes nothing, which no test asserts, so any other amount marks the test stubbed.
    if (!(is_whole(amount) && amount >= 0)) stubbed = true;  // SMOKETEST_EXCL: no committed test
    double took = amount < sl->count ? amount : sl->count;
    if (!(took > 0)) took = 0;  // SMOKETEST_EXCL: no committed test
    sl->count -= took;
    if (sl->count <= 0) slot_clear(sl);
    return took;
}
static union grug_value BE_take_item_from_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.take_item_from_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct slot* sl = slot_of(b, a[1]._number);
    if (!sl || !sl->has) return num(0);
    // item_extracted ("immediately when an item is removed") is not run: no committed test reads it,
    // so a block entity whose script declares it marks the test stubbed.
    if (b->item_extracted) stubbed = true;
    return num(slot_take(sl, a[2]._number));
}
REGISTER("BlockEntity", "take_item_from_slot", BE_take_item_from_slot);
// GrugSlotAccess truncates the slot to an int before it tests the range, so 0.5 asks about slot 0.
static double jint(double d) {
    // A NaN or an out-of-int slot is clamped as the Java cast does, but no committed test asks about one.
    if (isnan(d) || d >= 2147483647.0 || d <= -2147483648.0) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test passes a NaN or out-of-int slot to is_slot_insertable or is_slot_extractable
    return isnan(d) ? 0 : d >= 2147483647.0 ? 2147483647.0 : d <= -2147483648.0 ? -2147483648.0 : trunc(d); }
static bool slot_extractable(const struct be* b, double slot) { return !b->has_extractable || (jint(slot) >= b->ext_first && jint(slot) <= b->ext_last); }
static union grug_value BE_count_item_in_inventory(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.count_item_in_inventory: not a live block entity");  // SMOKETEST_EXCL: no committed test
    double total = 0;
    for (size_t i = 0; i < b->nslots; i++)
        if (b->slots[i].has && b->slots[i].item == a[1]._id && b->slots[i].damage == a[2]._number) total += b->slots[i].count;
    return num(total);
}
REGISTER("BlockEntity", "count_item_in_inventory", BE_count_item_in_inventory);
static union grug_value BE_extract_item_from_inventory(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.extract_item_from_inventory: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (b->item_extracted) stubbed = true;  // item_extracted is not run, as in take_item_from_slot
    if (!is_whole(a[2]._number) || !is_whole(a[3]._number)) stubbed = true;  // SMOKETEST_EXCL: no committed test
    double left = a[3]._number, total = 0;
    for (size_t i = 0; i < b->nslots && left > 0; i++)
        if (slot_extractable(b, (double)i) && b->slots[i].has && b->slots[i].item == a[1]._id && b->slots[i].damage == a[2]._number) {
            double took = slot_take(&b->slots[i], left);
            total += took; left -= took;
        }
    return num(total);
}
REGISTER("BlockEntity", "extract_item_from_inventory", BE_extract_item_from_inventory);

// Slot rules (slot_access-Test, power_declare_again-Test): a block entity that declares nothing is
// open everywhere, a declared range is inclusive, a last below the first declares no slot, and
// declaring again replaces the range. Only legal during init(), like set_power_receiver.
static union grug_value BIC_set_slots(const union grug_value a[], bool insertable) {
    if (!cur_init_be) return fail("BlockEntityInitContext: slot ranges are only legal while init() is running");  // SMOKETEST_EXCL: no committed test
    struct be* b = &bes[cur_init_be - 1];
    if (insertable) { b->has_insertable = true; b->ins_first = a[1]._number; b->ins_last = a[2]._number; }
    else { b->has_extractable = true; b->ext_first = a[1]._number; b->ext_last = a[2]._number; }
    return none();
}
static union grug_value BIC_set_insertable_slots(ARGS) { UNUSED; return BIC_set_slots(a, true); }
static union grug_value BIC_set_extractable_slots(ARGS) { UNUSED; return BIC_set_slots(a, false); }
REGISTER("BlockEntityInitContext", "set_insertable_slots", BIC_set_insertable_slots);
REGISTER("BlockEntityInitContext", "set_extractable_slots", BIC_set_extractable_slots);
static union grug_value BE_is_slot_insertable(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.is_slot_insertable: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return boolean(!b->has_insertable || (jint(a[1]._number) >= b->ins_first && jint(a[1]._number) <= b->ins_last));
}
REGISTER("BlockEntity", "is_slot_insertable", BE_is_slot_insertable);
static union grug_value BE_is_slot_extractable(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.is_slot_extractable: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return boolean(slot_extractable(b, a[1]._number));
}
REGISTER("BlockEntity", "is_slot_extractable", BE_is_slot_extractable);

// Responses (expect_response_extract_*-Test, expect_response_add_*-Test): the three rules of
// GrugResponse, which each answering method claims through begin_answer. The host function that
// asked finishes the response when the export returns. A response's error inside the export is
// re-raised by the asking host function, which is what aborts the test's tick as expect_error needs.
static bool begin_answer(struct obj* r, const char* method, const char* export_name) {
    if (!r->open) { fail("%s: the response is only valid during the %s call it was passed to, and that call has returned.", method, export_name); return false; }  // SMOKETEST_EXCL: reached once TestTickContext.notify_neighbors exists (expect_response_*_stale-Test)
    if (r->has) { fail("%s: %s has already been answered, and a response is answered once.", method, export_name); return false; }
    r->has = true;
    return true;
}
enum { ANS_REFUSE = 1, ANS_SLOT, ANS_CRAFT, ANS_CRAFT_CONSUMED };  // r->z; 0 is unanswered
static union grug_value Resp_extract_answer(const union grug_value a[], const char* method, int kind) {
    struct obj* r = get_obj(a[0]._id, K_EXTRACT_RESPONSE);
    if (!r) return fail("%s: not an ExtractResponse", method);  // SMOKETEST_EXCL: no committed test
    if (!begin_answer(r, method, "extract_item")) return none();
    r->z = kind;
    if (kind == ANS_SLOT) { r->x = a[1]._number; r->y = a[2]._number; }
    return none();
}
static union grug_value ER_refuse(ARGS) { UNUSED; return Resp_extract_answer(a, "ExtractResponse.refuse", ANS_REFUSE); }
static union grug_value ER_give_from_slot(ARGS) { UNUSED; return Resp_extract_answer(a, "ExtractResponse.give_from_slot", ANS_SLOT); }
static union grug_value ER_give_crafting_result(ARGS) { UNUSED; return Resp_extract_answer(a, "ExtractResponse.give_crafting_result", ANS_CRAFT); }
static union grug_value ER_give_crafting_result_consumed(ARGS) { UNUSED; return Resp_extract_answer(a, "ExtractResponse.give_crafting_result_consumed", ANS_CRAFT_CONSUMED); }  // SMOKETEST_EXCL: reached once update_recipe_output exists (expect_response_extract_twice_crafting_result_consumed-Test)
REGISTER("ExtractResponse", "refuse", ER_refuse);
REGISTER("ExtractResponse", "give_from_slot", ER_give_from_slot);
REGISTER("ExtractResponse", "give_crafting_result", ER_give_crafting_result);
REGISTER("ExtractResponse", "give_crafting_result_consumed", ER_give_crafting_result_consumed);
static union grug_value AR_answer(const union grug_value a[], const char* method, int kind) {
    struct obj* r = get_obj(a[0]._id, K_ADD_RESPONSE);
    if (!r) return fail("%s: not an AddResponse", method);  // SMOKETEST_EXCL: no committed test
    if (!begin_answer(r, method, "add_item")) return none();
    r->z = kind;
    if (kind == ANS_SLOT) r->x = a[1]._number;
    return none();
}
static union grug_value AR_refuse(ARGS) { UNUSED; return AR_answer(a, "AddResponse.refuse", ANS_REFUSE); }
static union grug_value AR_accept_into_slot(ARGS) { UNUSED; return AR_answer(a, "AddResponse.accept_into_slot", ANS_SLOT); }
REGISTER("AddResponse", "refuse", AR_refuse);
REGISTER("AddResponse", "accept_into_slot", AR_accept_into_slot);
// LiquidResponse's three answers (the liquidity rules in mods/coverage/tests). r->x holds an
// amount and r->str a liquid name; r->z is the answer's kind.
enum { LIQ_AMOUNT = 1, LIQ_LIQUID, LIQ_NONE };
static union grug_value Resp_liquid_answer(const union grug_value a[], const char* method, int kind) {
    struct obj* r = get_obj(a[0]._id, K_LIQUID_RESPONSE);
    if (!r) return fail("%s: not a LiquidResponse", method);  // SMOKETEST_EXCL: grug type-checks the argument
    if (!begin_answer(r, method, "liquid")) return none();
    r->z = kind;
    if (kind == LIQ_AMOUNT) r->x = a[1]._number;
    if (kind == LIQ_LIQUID) r->str = keep(xstrdup(a[1]._string));
    return none();
}
static union grug_value LR_answer_amount(ARGS) { UNUSED; return Resp_liquid_answer(a, "LiquidResponse.answer_amount", LIQ_AMOUNT); }
static union grug_value LR_answer_liquid(ARGS) { UNUSED; return Resp_liquid_answer(a, "LiquidResponse.answer_liquid", LIQ_LIQUID); }
static union grug_value LR_answer_none(ARGS) { UNUSED; return Resp_liquid_answer(a, "LiquidResponse.answer_none", LIQ_NONE); }
REGISTER("LiquidResponse", "answer_amount", LR_answer_amount);
REGISTER("LiquidResponse", "answer_liquid", LR_answer_liquid);
REGISTER("LiquidResponse", "answer_none", LR_answer_none);

// Calls an export that takes a response. Returns false when the entity has no such export (the
// caller answers -1), else true with the response's answer in *r (copied: the table may move) after
// the unanswered rule. An error the export raised is raised again here.
static bool ask_response(struct be* b, uint64_t fn, enum kind kind, const union grug_value* args, size_t nargs, struct obj* out, bool* errored, const char* export_name) {
    char err[4096];
    if (!b || !b->entity) return false;  // a vanilla block entity has no export
    uint64_t rid = new_obj(kind);
    objs[rid - 1].open = true;
    union grug_value all[8];
    memcpy(all, args, nargs * sizeof *args);
    all[nargs] = id(rid);
    bool ran = call_export(b->entity, fn, all, nargs + 1, err);
    objs[rid - 1].open = false;
    if (!ran) return false;  // SMOKETEST_EXCL: no committed test asks a block entity without the export
    // The real host closes the response and answers -1 when the export errors, the error already
    // contained; here the error is raised again so the asking tick aborts, the only way a test can see
    // it. That tick ends there, so the value the call would have answered is never read.
    *errored = false;
    if (err[0]) { *errored = true; fail("%s", err); }
    else if (!objs[rid - 1].has) { *errored = true; fail("%s returned without answering its response, and every response has to be answered before the call returns.", export_name); }
    *out = objs[rid - 1];
    return true;
}
static bool slot_named_ok(const struct be* b, double slot, const char* export_name) {
    if (slot_in_range(b, slot)) return true;
    fail("%s named slot %g, but the inventory has slots 0 to %ld.", export_name, slot, (long)b->nslots - 1);
    return false;
}
// insert_item_into_slot and insert_item_into_inventory (inventory_insert-Test, response_add-Test): room is
// what a stack of the same item and damage has left, or a whole stack in an empty slot, and nothing in a
// slot holding something else. Every item here stacks to 64, as set_item_in_slot assumes. An empty slot
// written through setInventorySlotContents runs item_inserted, a top-up of a held stack does not.
static double insert_into_slot(struct be* b, size_t i, uint64_t item, double damage, double amount, bool commit) {
    // The adapter reads the item's own stack limit and truncates amount and damage to an int. The smoketest
    // has no item table, so only the items the committed tests insert are known to stack to 64, and an
    // amount or damage that is not a whole int is not asserted by any test: such a call marks the test stubbed.
    if (!is_whole(amount) || amount < 0 || amount > 2147483647.0 || !is_whole(damage)) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test inserts a fractional, negative or NaN amount or damage
    const char* nm = objs[item - 1].str;
    if (strcmp(nm, "minecraft:diamond") && strcmp(nm, "minecraft:stick") && strcmp(nm, "grug:coverage_item")) stubbed = true;  // SMOKETEST_EXCL_BRANCH: the committed insert tests use only these three items, whose stack limit is 64
    struct slot* sl = &b->slots[i];
    double room = !sl->has ? 64 : (sl->item == item && sl->damage == damage) ? 64 - sl->count : 0;
    double add = amount < room ? amount : room;
    if (!(add > 0)) return 0;
    if (!commit) return add;
    if (sl->has) { sl->count += add; return add; }
    sl->has = true; sl->item = item; sl->count = add; sl->damage = damage;
    // The adapter writes an empty slot through setInventorySlotContents, which runs item_inserted. No
    // committed test asserts that after an insert (block_entity_me-Test asserts it for set_item_in_slot
    // only), so a block entity declaring item_inserted marks the test stubbed.
    if (b->item_inserted) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test inserts into a block entity that declares item_inserted
    if (b->entity) {
        union grug_value args[2] = {num((double)i), num(add)};
        void* e = b->entity;
        call_contained(e, be_inserted_fn, args, 2);
    }
    return add;
}
static union grug_value BE_insert_item_into_inventory(ARGS) {
    UNUSED;
    uint64_t self = a[0]._id;
    if (!be_of(self)) return fail("BlockEntity.insert_item_into_inventory: not a live block entity");  // SMOKETEST_EXCL: no committed test
    double remaining = a[3]._number;
    // Top up matching stacks first, then fill empty slots. The block entity is looked up again for every
    // slot because item_inserted may replace it.
    for (int pass = 0; pass < 2; pass++)
        for (size_t i = 0; be_of(self) && i < be_of(self)->nslots && remaining > 0; i++) {
            struct be* b = be_of(self);
            if (b->has_insertable && !(i >= b->ins_first && i <= b->ins_last)) continue;
            if (b->slots[i].has != (pass == 0)) continue;
            remaining -= insert_into_slot(b, i, a[1]._id, a[2]._number, remaining, a[4]._bool);
        }
    return num(a[3]._number - remaining);
}
REGISTER("BlockEntity", "insert_item_into_inventory", BE_insert_item_into_inventory);
// The crafting result. A grug block entity holds one cached result, which update_recipe_output sets from the
// nine slots at a start slot and take_crafting_result removes from, as the loaders' GrugBlockEntity does
// (a take pays nothing: the ingredients stay until the script consumes them). A recipe is a mod's
// (recipes.inc, generated from mods/*/data/*/recipes) or one of the few vanilla recipes a committed test
// asserts: the game's own table is not modelled, so a grid that matches nothing marks the test stubbed.
struct recipe { int shaped, w, h; const char* result; int count; const char* cells[9]; };
#include "recipes.inc"
static const struct recipe vanilla_recipes[] = {
    {1, 3, 1, "minecraft:bread", 1, {"minecraft:wheat", "minecraft:wheat", "minecraft:wheat"}},  // slot_access-Test, response_extract_consumed-Test
};
// grid holds the item name of each of the 9 cells, NULL for an empty one. A shaped recipe fits anywhere
// in the grid, as written or mirrored, with every other cell empty; a shapeless one takes exactly its ingredients in any order.
static const struct recipe* match_recipe(const struct recipe* rs, size_t n, const char* const grid[9]) {
    for (size_t r = 0; r < n; r++) {
        const struct recipe* rc = &rs[r];
        if (rc->shaped) {
            for (int mirror = 0; mirror < 2; mirror++)
                for (int oy = 0; oy + rc->h <= 3; oy++)
                    for (int ox = 0; ox + rc->w <= 3; ox++) {
                        bool ok = true;
                        for (int y = 0; y < 3 && ok; y++)
                            for (int x = 0; x < 3 && ok; x++) {
                                bool inside = x >= ox && x < ox + rc->w && y >= oy && y < oy + rc->h;
                                int col = mirror ? rc->w - 1 - (x - ox) : x - ox;
                                const char* want = inside ? rc->cells[(y - oy) * rc->w + col] : "";
                                const char* have = grid[y * 3 + x];
                                ok = want[0] ? have && !strcmp(have, want) : !have;
                            }
                        if (ok) return rc;
                    }
        } else {
            int n_in = rc->w, filled = 0;
            bool used[9] = {false};
            bool ok = true;
            for (int c = 0; c < 9; c++) if (grid[c]) filled++;
            for (int i = 0; i < n_in && ok; i++) {
                ok = false;
                for (int c = 0; c < 9 && !ok; c++)
                    if (grid[c] && !used[c] && !strcmp(grid[c], rc->cells[i])) { used[c] = true; ok = true; }
            }
            if (ok && filled == n_in) return rc;
        }
    }
    return NULL;
}
// A take of the cached result tells the block entity, as the loaders' notifyOutputTaken does.
static void notify_output_taken(struct be* b, double amount) {
    if (!b->output_taken) return;
    union grug_value arg = num(amount);
    call_contained(b->entity, be_output_taken_fn, &arg, 1);
}
static union grug_value BE_update_recipe_output(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.update_recipe_output: not a live block entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a live block entity
    if (!b->entity) return fail("update_recipe_output: the block entity has no crafting result container.");
    if (!is_whole(a[1]._number) || a[1]._number < 0) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test starts the grid at a fraction or below slot 0
    const char* grid[9];
    bool any = false;
    for (int c = 0; c < 9; c++) {
        double slot = a[1]._number + c;
        struct slot* sl = slot >= 0 && slot < (double)b->nslots ? &b->slots[(size_t)slot] : NULL;
        grid[c] = sl && sl->has && sl->count > 0 ? objs[sl->item - 1].str : NULL;
        any = any || grid[c];
    }
    const struct recipe* rc = match_recipe(mod_recipes, sizeof mod_recipes / sizeof *mod_recipes, grid);
    if (!rc) rc = match_recipe(vanilla_recipes, sizeof vanilla_recipes / sizeof *vanilla_recipes, grid);
    b->has_result = rc != NULL;
    if (rc) { b->res_name = rc->result; b->res_count = rc->count; b->res_damage = 0; }
    else if (any) stubbed = true;  // the game's own recipes are not modelled, so a filled grid with no match proves less
    return none();
}
REGISTER("BlockEntity", "update_recipe_output", BE_update_recipe_output);
// slot_access-Test: a block entity with no result answers an empty Option and damage 0.
static union grug_value BE_get_crafting_result_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_crafting_result_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    uint64_t o = new_obj(K_OPTION);
    if (b->has_result) { objs[o - 1].has = true; objs[o - 1].val = id(item_named(b->res_name)); }
    return id(o);
}
REGISTER("BlockEntity", "get_crafting_result_item", BE_get_crafting_result_item);
static union grug_value BE_get_crafting_result_damage(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_crafting_result_damage: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return num(b->has_result ? b->res_damage : 0);
}
REGISTER("BlockEntity", "get_crafting_result_damage", BE_get_crafting_result_damage);
static union grug_value BE_take_crafting_result(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.take_crafting_result: not a live block entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a live block entity
    if (!b->entity) return fail("BlockEntity.take_crafting_result: the block entity has no crafting result container.");  // SMOKETEST_EXCL_BRANCH: no committed test takes from a block entity that is not a grug one
    if (!b->has_result) return num(0);
    double want = a[1]._number;
    if (!(want >= 1) || !is_whole(want)) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test takes a fraction, NaN or less than one
    double taken = want < b->res_count ? want : b->res_count;
    b->res_count -= taken;
    if (b->res_count <= 0) b->has_result = false;
    notify_output_taken(b, taken);
    return num(taken);
}
REGISTER("BlockEntity", "take_crafting_result", BE_take_crafting_result);
// consume_crafting_ingredients (adapter_branches-Test): one item off each filled slot of the nine from the start.
// The loaders then put an item's container (a bucket) back; no item the committed tests use has one.
static union grug_value BE_consume_crafting_ingredients(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.consume_crafting_ingredients: not a live block entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a live block entity
    for (int c = 0; c < 9; c++) {
        double slot = a[1]._number + c;
        if (!(slot >= 0 && slot < (double)b->nslots)) { stubbed = true; continue; }  // SMOKETEST_EXCL_BRANCH: no committed test starts the grid past the inventory
        struct slot* sl = &b->slots[(size_t)slot];
        if (sl->has && sl->count > 0) {
            sl->count -= 1;
            if (sl->count <= 0) slot_clear(sl);
        }
    }
    return none();
}
REGISTER("BlockEntity", "consume_crafting_ingredients", BE_consume_crafting_ingredients);
static union grug_value BE_ask_extract_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.ask_extract_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct obj r; bool errored;
    union grug_value args[2] = {num(a[1]._number), boolean(false)};
    uint64_t self = a[0]._id;
    double offered = b->has_result ? b->res_count : 0;  // read before the export, which may craft the next result
    bool ran = ask_response(b, G.be_extract_item, K_EXTRACT_RESPONSE, args, 2, &r, &errored, "extract_item");
    if (!ran) return num(-1);  // SMOKETEST_EXCL: no committed test asks a block entity without the export
    if (errored) return none();
    b = be_of(self);
    if (!b) return num(0);  // SMOKETEST_EXCL: no committed test replaces a block entity from inside extract_item
    if (r.z == ANS_SLOT) {
        if (!slot_named_ok(b, r.x, "extract_item")) return none();
        // SMOKETEST_EXCL_START: reached once extract_item_into exists (response_extract-Test)
        struct slot* sl = &b->slots[(size_t)r.x];
        return num(r.y < sl->count ? r.y : sl->count);
        // SMOKETEST_EXCL_STOP
    }
    // A crafting result answer offers the cached result's count, or, for a consumed answer, the count the
    // result had when the pipe asked, because the export may have crafted the next one into the holder.
    if (r.z == ANS_CRAFT) return num(b->has_result ? b->res_count : 0);
    if (r.z == ANS_CRAFT_CONSUMED) return num(offered);
    return num(0);
}
REGISTER("BlockEntity", "ask_extract_item", BE_ask_extract_item);
// extract_item_into (response_extract-Test, response_extract_consumed-Test, the expect_extract_item_into_* tests):
// the destination slot is checked first, then the source is asked with remove true and the host moves what
// it named: a slot's items, the cached result, or, for a consumed answer, the result as it was when asked.
static union grug_value BE_extract_item_into(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    struct be* d = be_of(a[1]._id);
    if (!b || !d) return fail("BlockEntity.extract_item_into: not a live block entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but live block entities
    double dslot = a[2]._number;
    char num_buf[400];
    if (!is_whole(dslot)) {
        number_text(dslot, num_buf, sizeof num_buf);
        return fail("BlockEntity.extract_item_into: slot %s of the destination is not a whole slot number of its inventory of %zu slots.", num_buf, d->nslots);
    }
    if (dslot < 0 || dslot >= (double)d->nslots) return fail("BlockEntity.extract_item_into: slot %.0f of the destination is outside its inventory of %zu slots.", dslot, d->nslots);
    if (d->slots[(size_t)dslot].has && d->slots[(size_t)dslot].count != 0) return fail("BlockEntity.extract_item_into: slot %.0f of the destination is not empty.", dslot);
    struct obj r; bool errored;
    union grug_value args[2] = {num(a[3]._number), boolean(true)};
    uint64_t self = a[0]._id, dest = a[1]._id;
    double offered = b->has_result ? b->res_count : 0;
    const char* offered_name = b->res_name;
    double offered_damage = b->res_damage;
    bool ran = ask_response(b, G.be_extract_item, K_EXTRACT_RESPONSE, args, 2, &r, &errored, "extract_item");
    if (!ran) return num(-1);  // SMOKETEST_EXCL_BRANCH: no committed test moves from a block entity without the export
    if (errored) return none();
    b = be_of(self); d = be_of(dest);
    if (!b || !d) return num(0);  // SMOKETEST_EXCL_BRANCH: no committed test replaces a block entity from inside extract_item
    uint64_t item = 0; double damage = 0, taken = 0;
    if (r.z == ANS_SLOT) {
        if (!slot_named_ok(b, r.x, "extract_item")) return none();
        struct slot* sl = &b->slots[(size_t)r.x];
        double count = r.y < sl->count ? r.y : sl->count;
        if (count == 0) return num(0);
        item = sl->item; damage = sl->damage;
        if (b->item_extracted) stubbed = true;  // SMOKETEST_EXCL_BRANCH: item_extracted is not run, as in take_item_from_slot
        taken = slot_take(sl, count);
    } else if (r.z == ANS_CRAFT) {
        if (!b->has_result) return num(0);  // SMOKETEST_EXCL_BRANCH: no committed test answers a result that is not there
        item = item_named(b->res_name); damage = b->res_damage;
        taken = b->res_count;  // the host asks for the whole count it was offered
        b->has_result = false; b->res_count = 0;
        notify_output_taken(b, taken);
        d = be_of(dest);
        if (!d) return num(0);  // SMOKETEST_EXCL_BRANCH: no committed test replaces the destination from inside output_taken
    } else if (r.z == ANS_CRAFT_CONSUMED) {
        if (offered == 0) return num(0);  // SMOKETEST_EXCL_BRANCH: no committed test answers a consumed result that is not there
        item = item_named(offered_name); damage = offered_damage; taken = offered;
    } else {
        return num(0);
    }
    struct slot* ds = &d->slots[(size_t)dslot];
    if (taken > 64) stubbed = true;  // SMOKETEST_EXCL_BRANCH: the destination's stack limit is not modelled
    ds->has = true; ds->item = item; ds->count = taken; ds->damage = damage;
    return num(ds->count);
}
REGISTER("BlockEntity", "extract_item_into", BE_extract_item_into);
static union grug_value BE_add_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.add_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    uint64_t self = a[0]._id;
    double count = a[3]._number, remaining = count;
    while (remaining > 0) {
        struct obj r; bool errored;
        union grug_value args[5] = {a[1], num(a[2]._number), num(remaining), num(a[4]._number), boolean(a[5]._bool)};
        if (!ask_response(be_of(self), G.be_add_item, K_ADD_RESPONSE, args, 5, &r, &errored, "add_item")) return remaining == count ? num(-1) : num(count - remaining);  // SMOKETEST_EXCL: no committed test asks twice
        if (errored) return none();
        b = be_of(self);
        if (r.z == ANS_REFUSE || !b || !slot_named_ok(b, r.x, "add_item")) break;
        double moved = insert_into_slot(b, (size_t)r.x, a[1]._id, a[2]._number, remaining, a[5]._bool);
        remaining -= moved;
        if (!a[5]._bool || moved == 0) break;
    }
    return num(count - remaining);
}
REGISTER("BlockEntity", "add_item", BE_add_item);

// Liquid (tank-*-Test in mods/buildcraft, and the LiquidResponse rules in mods/coverage). A liquid is
// the still liquid's canonical name, and the smoketest keeps none of its own: every question is
// dispatched to the block entity's export, as the host does. -1 is the host's answer to an offer or
// a take that has no export, and 0 the answer to an amount it cannot ask for.
static const char* canonical_liquid(const char* name) {
    if (!strcmp(name, "minecraft:water") || !strcmp(name, "minecraft:flowing_water")) return "minecraft:water";
    if (!strcmp(name, "minecraft:lava") || !strcmp(name, "minecraft:flowing_lava")) return "minecraft:lava";
    return NULL;
}
static union grug_value BE_offer_liquid(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.offer_liquid: not a live block entity");  // SMOKETEST_EXCL: no committed test
    const char* liquid = canonical_liquid(a[1]._string);
    if (!liquid) return fail("BlockEntity.offer_liquid: %s is not a liquid. A liquid is named by its still block, such as minecraft:water or minecraft:lava.", a[1]._string);
    if (!isfinite(a[2]._number)) {
        char amount_text[40];
        number_text(a[2]._number, amount_text, sizeof amount_text);
        return fail("BlockEntity.offer_liquid: The amount has to be a finite number, and was %s.", amount_text);
    }
    union grug_value args[4] = {str(liquid), a[2], a[3], a[4]};
    struct obj r; bool errored;
    if (!ask_response(b, G.be_offer_liquid, K_LIQUID_RESPONSE, args, 4, &r, &errored, "offer_liquid")) return num(-1);
    if (errored) return none();
    if (r.z != LIQ_AMOUNT || !isfinite(r.x) || r.x < 0) return fail("offer_liquid: The export answered with the wrong kind of answer, and this question wants a finite amount of 0 or more.");
    return num(r.x);
}
REGISTER("BlockEntity", "offer_liquid", BE_offer_liquid);
static union grug_value BE_take_liquid(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.take_liquid: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!isfinite(a[1]._number)) {
        char amount_text[40];
        number_text(a[1]._number, amount_text, sizeof amount_text);
        return fail("BlockEntity.take_liquid: The amount has to be a finite number, and was %s.", amount_text);
    }
    union grug_value args[2] = {a[1], a[2]};
    struct obj r; bool errored;
    if (!ask_response(b, G.be_take_liquid, K_LIQUID_RESPONSE, args, 2, &r, &errored, "take_liquid")) return num(-1);
    if (errored) return none();
    if (r.z != LIQ_AMOUNT || !isfinite(r.x) || r.x < 0) return fail("take_liquid: The export answered with the wrong kind of answer, and this question wants a finite amount of 0 or more.");
    return num(r.x);
}
REGISTER("BlockEntity", "take_liquid", BE_take_liquid);
static union grug_value BE_get_liquid(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_liquid: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct obj r; bool errored;
    uint64_t o = new_obj(K_OPTION);
    if (!ask_response(b, G.be_get_liquid, K_LIQUID_RESPONSE, a, 0, &r, &errored, "get_liquid")) return id(o);
    if (errored || r.z == LIQ_NONE) return id(o);
    const char* liquid = r.z == LIQ_LIQUID ? canonical_liquid(r.str) : NULL;
    if (!liquid) return fail("get_liquid: The export answered with the wrong kind of answer, and this question wants a liquid name or none.");
    objs[o - 1].has = true;
    objs[o - 1].val = str(keep(xstrdup(liquid)));
    return id(o);
}
REGISTER("BlockEntity", "get_liquid", BE_get_liquid);
static union grug_value BE_get_liquid_amount(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_liquid_amount: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct obj r; bool errored;
    if (!ask_response(b, G.be_get_liquid_amount, K_LIQUID_RESPONSE, a, 0, &r, &errored, "get_liquid_amount")) return num(0);
    if (errored) return none();
    if (r.z != LIQ_AMOUNT) return fail("get_liquid_amount: The export answered with the wrong kind of answer, and this question wants an amount.");
    return num(r.x);
}
REGISTER("BlockEntity", "get_liquid_amount", BE_get_liquid_amount);
static union grug_value BE_get_liquid_capacity(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_liquid_capacity: not a live block entity");  // SMOKETEST_EXCL: no committed test
    struct obj r; bool errored;
    if (!ask_response(b, G.be_get_liquid_capacity, K_LIQUID_RESPONSE, a, 0, &r, &errored, "get_liquid_capacity")) return num(0);
    if (errored) return none();
    if (r.z != LIQ_AMOUNT) return fail("get_liquid_capacity: The export answered with the wrong kind of answer, and this question wants an amount.");
    return num(r.x);
}
REGISTER("BlockEntity", "get_liquid_capacity", BE_get_liquid_capacity);

// The frame pass is not modelled (RenderFrameContext.draw_item has no entry here either), so no
// committed test can call this; the entry exists so the contract counts it and a script that calls
// it by mistake is told what is missing rather than silently drawing nothing.
// SMOKETEST_EXCL_START: only a render_frame can call this, and the smoketest runs no frame pass
static union grug_value RFC_draw_liquid(ARGS) {
    UNUSED;
    return fail("RenderFrameContext.draw_liquid: the smoketest models no frame pass");
}
// SMOKETEST_EXCL_STOP
REGISTER("RenderFrameContext", "draw_liquid", RFC_draw_liquid);

static union grug_value TTC_tick_block_entity(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    if (!b) return fail("TestTickContext.tick_block_entity: not a live block entity");
    if (!b->entity) return fail("Only a grug block entity has a tick to run");
    double n = a[2]._number;
    if (!is_whole(n) || n < 0 || n > 2147483647.0) return fail("The ticks have to be a whole number from 0 to 2147483647");
    size_t idx = (size_t)(a[1]._id - BE_BASE);
    // A tick may replace this block entity or move `bes`, so each round looks it up again by index.
    for (long i = 0; i < (long)n && bes[idx].live; i++) call_contained(bes[idx].entity, G.be_tick, NULL, 0);
    return none();
}
REGISTER("TestTickContext", "tick_block_entity", TTC_tick_block_entity);
// notify_neighbors (neighbor_change-Test, expect_response_*_stale-Test): the grug block entity at each of the
// six neighbours of the position gets on_neighbor_change. Its error is raised again so the tick aborts,
// as the stale-response tests expect.
static union grug_value TTC_notify_neighbors(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a + 1, p, "TestTickContext.notify_neighbors")) return none();  // SMOKETEST_EXCL: no committed test
    static const long d[6][3] = {{0,-1,0},{0,1,0},{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};
    for (int i = 0; i < 6; i++) {
        struct be* b = be_at(p[0] + d[i][0], p[1] + d[i][1], p[2] + d[i][2]);
        if (!b || !b->entity) continue;
        char err[sizeof last_msg];
        call_export(b->entity, be_neighbor_fn, NULL, 0, err);
        if (err[0]) return fail("%s", err);
    }
    return none();
}
REGISTER("TestTickContext", "notify_neighbors", TTC_notify_neighbors);

// Saved numbers and saved lists. Index and key errors use the texts the expect_*saved* tests wait for.
static union grug_value BE_set_saved_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_number: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves numbers");  // expect_*_saved_*_on_a_chest-Test
    for (size_t i = 0; i < b->nnums; i++) if (!strcmp(b->nums[i].k, a[1]._string)) { b->nums[i].v = a[2]._number; return none(); }
    b->nums = xrealloc(b->nums, (b->nnums + 1) * sizeof *b->nums);
    b->nums[b->nnums].k = xstrdup(a[1]._string);
    b->nums[b->nnums++].v = a[2]._number;
    return none();
}
REGISTER("BlockEntity", "set_saved_number", BE_set_saved_number);
static union grug_value BE_get_saved_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_number: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves numbers");  // expect_*_saved_*_on_a_chest-Test
    for (size_t i = 0; i < b->nnums; i++) if (!strcmp(b->nums[i].k, a[1]._string)) return num(b->nums[i].v);
    return num(0);
}
REGISTER("BlockEntity", "get_saved_number", BE_get_saved_number);
// redraw builds the block's geometry again, which nothing here draws, so a pass is STUBBED; only a grug block
// entity has a render function (expect_redraw_on_a_chest-Test).
static union grug_value BE_redraw(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.redraw: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("redraw: Only a grug block entity has a render function to draw again.");
    stubbed = true;
    return none();
}
REGISTER("BlockEntity", "redraw", BE_redraw);
// The number the joined client's own copy of a block entity holds. The toy world has no second copy, so the
// copy is taken to have every change at once, which a pass that waited for the send did not prove: STUBBED.
// A block entity with no script saves no numbers, and a name never saved is 0, as the real call answers.
static union grug_value TTC_client_saved_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    stubbed = true;
    double v = 0;
    for (size_t i = 0; b && i < b->nnums; i++) if (!strcmp(b->nums[i].k, a[2]._string)) v = b->nums[i].v;  // SMOKETEST_EXCL_BRANCH: no committed test asks about a block entity that is gone or a number never saved
    return num(v);
}
REGISTER("TestTickContext", "client_saved_number", TTC_client_saved_number);
static struct list* find_list(struct be* b, const char* name) {
    for (size_t i = 0; i < b->nlists; i++) if (!strcmp(b->lists[i].name, name)) return &b->lists[i];
    return NULL;
}
// The record a call names, or NULL after raising the error.
// What a record index asks of a list of `n` records (`exists`: the list was ever created).
enum idx_check { IDX_OK, IDX_NOT_WHOLE, IDX_NO_RECORD };
static enum idx_check check_index(double index, size_t n, bool exists) {
    if (!is_whole(index) || index < 0 || index > 2147483647.0) return IDX_NOT_WHOLE;
    if (!exists || index >= (double)n) return IDX_NO_RECORD;
    return IDX_OK;
}
static struct record* record_at(struct be* b, const char* name, double index, size_t* out_n) {
    struct list* l = find_list(b, name);
    size_t n = l ? l->nr : 0;
    if (out_n) *out_n = n;
    switch (check_index(index, n, l != NULL)) {
    case IDX_NOT_WHOLE: fail("The index has to be a whole number from 0 to %zu", n ? n - 1 : 0); return NULL;
    case IDX_NO_RECORD: fail("There is no record at index %.0f", index); return NULL;
    default: return &l->r[(size_t)index];
    }
}
static union grug_value BE_get_saved_list_size(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_size: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct list* l = find_list(b, a[1]._string);
    return num(l ? (double)l->nr : 0);
}
REGISTER("BlockEntity", "get_saved_list_size", BE_get_saved_list_size);
static union grug_value BE_append_saved_list_record(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.append_saved_list_record: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct list* l = find_list(b, a[1]._string);
    if (!l) {
        b->lists = xrealloc(b->lists, (b->nlists + 1) * sizeof *b->lists);
        l = &b->lists[b->nlists++];
        memset(l, 0, sizeof *l);
        l->name = xstrdup(a[1]._string);
    }
    l->r = xrealloc(l->r, (l->nr + 1) * sizeof *l->r);
    memset(&l->r[l->nr], 0, sizeof *l->r);
    return num((double)l->nr++);
}
REGISTER("BlockEntity", "append_saved_list_record", BE_append_saved_list_record);
static union grug_value BE_remove_saved_list_record(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.remove_saved_list_record: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    // SMOKETEST_EXCL_START: reached once a test that removes a record can run, the committed ones needing entries the smoketest lacks
    struct list* l = find_list(b, a[1]._string);
    for (size_t j = 0; j < r->nn; j++) free(r->n[j].k);
    free(r->n);
    memmove(r, r + 1, (size_t)(l->r + l->nr - (r + 1)) * sizeof *r);
    l->nr--;
    return none();
    // SMOKETEST_EXCL_STOP
}
REGISTER("BlockEntity", "remove_saved_list_record", BE_remove_saved_list_record);
static union grug_value BE_get_saved_list_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_number: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    for (size_t j = 0; j < r->nn; j++) if (!strcmp(r->n[j].k, a[3]._string)) return num(r->n[j].v);
    return num(0);
}
REGISTER("BlockEntity", "get_saved_list_number", BE_get_saved_list_number);
static union grug_value BE_set_saved_list_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_list_number: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    for (size_t j = 0; j < r->nn; j++) if (!strcmp(r->n[j].k, a[3]._string)) { r->n[j].v = a[4]._number; return none(); }
    r->n = xrealloc(r->n, (r->nn + 1) * sizeof *r->n);
    r->n[r->nn].k = xstrdup(a[3]._string);
    r->n[r->nn++].v = a[4]._number;
    return none();
}
REGISTER("BlockEntity", "set_saved_list_number", BE_set_saved_list_number);
static union grug_value BE_set_saved_list_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_list_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (r) { r->has_item = true; r->item = a[3]._id; }
    return none();
}
REGISTER("BlockEntity", "set_saved_list_item", BE_set_saved_list_item);
static union grug_value BE_get_saved_list_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // expect_*_saved_*_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    uint64_t o = new_obj(K_OPTION);
    if (r && r->has_item) { objs[o - 1].has = true; objs[o - 1].val = id(r->item); }
    return id(o);
}
REGISTER("BlockEntity", "get_saved_list_item", BE_get_saved_list_item);

// Saved text (saved_strings-Test, tank persistence). An empty string is stored and read as itself,
// so get_saved_string tells it from a name that was never saved, and a list record's text is its
// own field beside its numbers and its item.
static union grug_value BE_set_saved_string(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_string: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves text");  // saved_strings_on_a_chest-Test
    for (size_t i = 0; i < b->nstrs; i++) if (!strcmp(b->strs[i].k, a[1]._string)) {
        free(b->strs[i].v);
        b->strs[i].v = xstrdup(a[2]._string);
        return none();
    }
    b->strs = xrealloc(b->strs, (b->nstrs + 1) * sizeof *b->strs);
    b->strs[b->nstrs].k = xstrdup(a[1]._string);
    b->strs[b->nstrs++].v = xstrdup(a[2]._string);
    return none();
}
REGISTER("BlockEntity", "set_saved_string", BE_set_saved_string);
static union grug_value BE_get_saved_string(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_string: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves text");  // saved_strings_on_a_chest-Test
    uint64_t o = new_obj(K_OPTION);
    for (size_t i = 0; i < b->nstrs; i++) if (!strcmp(b->strs[i].k, a[1]._string)) {
        objs[o - 1].has = true;
        objs[o - 1].val = str(keep(xstrdup(b->strs[i].v)));
        return id(o);
    }
    return id(o);
}
REGISTER("BlockEntity", "get_saved_string", BE_get_saved_string);
static union grug_value BE_set_saved_list_string(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_list_string: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // saved_strings_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (r) { free(r->text); r->text = xstrdup(a[3]._string); }
    return none();
}
REGISTER("BlockEntity", "set_saved_list_string", BE_set_saved_list_string);
static union grug_value BE_get_saved_list_string(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_string: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return fail("Only a grug block entity saves lists");  // saved_strings_on_a_chest-Test
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    uint64_t o = new_obj(K_OPTION);
    if (r && r->text) {
        objs[o - 1].has = true;
        objs[o - 1].val = str(keep(xstrdup(r->text)));
    }
    return id(o);
}
REGISTER("BlockEntity", "get_saved_list_string", BE_get_saved_list_string);

// round_trip_nbt (nbt_round_trip-Test, saved_lists-Test, item_stack_save-Test, power_nbt_round_trip-Test, the
// pipe reload tests): write the block entity out and read it back. Every committed test asserts that what
// it saved survives, and the smoketest keeps that state in the model itself, so the round trip changes
// nothing. What the real save drops (a record's item keeps its identity alone, a stack count is a byte on the
// four older loaders) is a limit of the save, which the model's numbers do not reproduce.
static union grug_value TTC_round_trip_nbt(ARGS) {
    UNUSED;
    if (!be_of(a[1]._id)) return fail("TestTickContext.round_trip_nbt: not a live block entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a live block entity
    stubbed = true;
    return none();
}
REGISTER("TestTickContext", "round_trip_nbt", TTC_round_trip_nbt);

// =============================================================================================
// SECTION: Block use
// =============================================================================================
// use_block (use_block_effects-Test, use_block_faces-Test, decline_use-Test, decline_use_places-Test): a
// click on a block runs the block's use export; a block that declines (decline_use) or has no use export
// leaves the click to the held item, which is placed against the clicked face. The face is the one the test
// player's eye sees: the eye is three blocks below the origin, and the face is the one the line from the eye
// to the block's centre enters, which is the axis of the largest distance. Nothing opens a screen, so a test
// that then waits for one still fails on screen_open or a GUI call it needs; this models the no-screen click.
static uint64_t block_use_fn;
static struct grug_file_info* block_file_of(const char* name) {
    if (strncmp(name, "grug:", 5)) return NULL;
    for (size_t i = 0; i < G.f.len; i++) {
        struct grug_file_info* fi = &G.f.ptr[i];
        if (fi->file_id != UINT64_MAX && fi->entity_type && !strcmp(fi->entity_type, "Block") && !strcmp(fi->entity_name, name + 5)) return fi;
    }
    return NULL;  // SMOKETEST_EXCL: every committed click is on a grug block, so no test clicks a block with no script
}
static union grug_value BUC_decline_use(ARGS) {
    UNUSED;
    struct obj* c = get_obj(a[0]._id, K_USE_CTX);
    if (!c) return fail("BlockUseContext.decline_use: not a BlockUseContext");  // SMOKETEST_EXCL: no committed test
    c->has = true;
    return none();
}
REGISTER("BlockUseContext", "decline_use", BUC_decline_use);
// The face a ray from the eye to the block's centre enters, as GrugUseBlockFace.face numbers it: 0 down, 1 up,
// 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x). The axis of the largest distance decides, and a tie
// goes to y, then x, then z. selftest.c compares it with the six faces and the ties of the core's rule.
static int use_face_of(double ex, double ey, double ez, long x, long y, long z) {
    double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
    double ax = fabs(dx), ay = fabs(dy), az = fabs(dz);
    if (ay >= ax && ay >= az) return dy > 0 ? 0 : 1;
    if (ax >= az) return dx > 0 ? 4 : 5;
    return dz > 0 ? 2 : 3;  // SMOKETEST_EXCL: no committed test clicks along z, so only selftest.c reaches it, against GrugUseBlockFace
}
// Where a click on the block at (x, y, z) places the held block: the neighbour across the clicked face. The eye
// is the test origin three blocks down, and ignores set_player_position, which no committed use test calls.
static void use_face(long x, long y, long z, long out[3]) {
    static const int step[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    int f = use_face_of(origin_x, origin_y - 3, origin_z, x, y, z);
    out[0] = x + step[f][0]; out[1] = y + step[f][1]; out[2] = z + step[f][2];
}
static union grug_value TTC_use_block(ARGS) {
    UNUSED;
    long p[3];
    if (!get_obj(a[1]._id, K_LEVEL)) return fail("TestTickContext.use_block: not a Level");  // SMOKETEST_EXCL: no committed test
    // pos_arg reads the coordinates from a[1..3], so shift by one.
    union grug_value shifted[4] = {a[1], a[2], a[3], a[4]};
    if (!pos_arg(shifted, p, "TestTickContext.use_block")) return none();  // SMOKETEST_EXCL: no committed test clicks a non-finite position
    struct block* b = find_block(p[0], p[1], p[2]);
    const char* name = b ? b->name : "minecraft:air";
    bool handled = false;
    struct grug_file_info* fi = block_file_of(name);
    if (fi) {
        // A fresh entity per click loses member state, so a future block whose use export keeps members is not trusted here.
        void* ent = grug_create_entity(g_state, fi->file_id);
        uint64_t c = new_obj(K_USE_CTX);
        union grug_value args[6] = {id(c), a[1], num(a[2]._number), num(a[3]._number), num(a[4]._number), id(new_obj(K_PLAYER))};
        bool ran = call_export(ent, block_use_fn, args, 6, NULL);
        handled = ran && !objs[c - 1].has;
        grug_deinit_entity(g_state, ent);
    }
    if (handled || !inv[0].has) return none();
    long at[3];
    use_face(p[0], p[1], p[2], at);
    if (!strcmp(objs[inv[0].item - 1].str, "minecraft:cobblestone")) {
        struct block* t = find_block(at[0], at[1], at[2]);
        if (!t || !strcmp(t->name, "minecraft:air")) {
            set_block(at[0], at[1], at[2], "minecraft:cobblestone");
            on_block_placed(at[0], at[1], at[2], "minecraft:cobblestone");
            inv[0].count--;  // the block item takes one from the stack
            if (inv[0].count <= 0) inv[0].has = false;
        } else stubbed = true;  // SMOKETEST_EXCL: no committed test clicks toward an occupied position; whether the game replaces it depends on the block
    } else stubbed = true;  // SMOKETEST_EXCL: no committed test holds another vanilla item for a declined click; whether it places is the game's
    return none();
}
REGISTER("TestTickContext", "use_block", TTC_use_block);

// Display ticks (display_tick-Test, expect_display_tick_*-Test, redstone_engine_particles-Test). The game's
// random display tick is the block's display_tick export with a generator seeded by the test, so the
// smoketest seeds the level's java.util.Random (what the loaders' generators are) for the call and puts the
// level's own seed state back. Every particle is recorded and none is drawn. The export is looked up on
// the block's file, so a block that never exports display_tick spawns nothing, where the game also needs
// set_display_tick (BIC_noop makes such a pass STUBBED, so a block that exports without declaring is not caught).
#define PARTICLE_MAX 64
static struct { double x, y, z; } particles[PARTICLE_MAX];
static size_t nparticles;
static uint64_t block_display_tick_fn;
// Every loader hands the generator to the script as random::nextFloat, so this is Random.nextFloat.
static union grug_value DTC_random(ARGS) { UNUSED; return num(rnd_float()); }
REGISTER("DisplayTickContext", "random", DTC_random);
// GrugDisplayTicks.particleProblem: only reddust exists, and every number must be finite.
static bool particle_problem(const char* name, const double v[6], char* out, size_t cap) {
    if (strcmp(name, "reddust")) { snprintf(out, cap, "There is no particle called \"%s\". The particles are reddust.", name); return true; }
    for (int i = 0; i < 6; i++)
        if (!isfinite(v[i])) { snprintf(out, cap, "The position and the motion must be finite numbers."); return true; }  // SMOKETEST_EXCL: no committed test passes a non-finite particle; selftest.c checks it
    return false;
}
static union grug_value DTC_add_particle(ARGS) {
    UNUSED;
    double v[6] = {a[2]._number, a[3]._number, a[4]._number, a[5]._number, a[6]._number, a[7]._number};
    char msg[512];
    if (particle_problem(a[1]._string, v, msg, sizeof msg)) return fail("DisplayTickContext.add_particle: %s", msg);
    if (nparticles < PARTICLE_MAX) {  // SMOKETEST_EXCL_BRANCH: no committed block spawns 64 particles in one tick
        particles[nparticles].x = v[0]; particles[nparticles].y = v[1]; particles[nparticles].z = v[2];
        nparticles++;
    }
    return none();
}
REGISTER("DisplayTickContext", "add_particle", DTC_add_particle);
static union grug_value TTC_display_tick(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_LEVEL)) return fail("TestTickContext.display_tick: not a Level");  // SMOKETEST_EXCL: no committed test
    nparticles = 0;
    long p[3] = {(long)floor(a[2]._number), (long)floor(a[3]._number), (long)floor(a[4]._number)};
    struct block* b = find_block(p[0], p[1], p[2]);
    struct grug_file_info* fi = block_file_of(b ? b->name : "minecraft:air");
    if (!fi) return num(0);
    uint64_t saved_seed = rnd_seed;
    bool saved_have = rnd_have_gaussian;
    rnd_set_seed((int64_t)a[5]._number);
    void* ent = grug_create_entity(g_state, fi->file_id);
    union grug_value args[5] = {id(new_obj(K_DISPLAY_CTX)), a[1], num((double)p[0]), num((double)p[1]), num((double)p[2])};
    char err[4096];
    call_export(ent, block_display_tick_fn, args, 5, err);
    grug_deinit_entity(g_state, ent);
    rnd_seed = saved_seed;
    rnd_have_gaussian = saved_have;
    stubbed = true;  // no particle is drawn, and the block's set_display_tick declaration is not checked
    if (err[0]) return fail("%s", err);
    return num((double)nparticles);
}
REGISTER("TestTickContext", "display_tick", TTC_display_tick);
static union grug_value TTC_particle_position(ARGS) {
    UNUSED;
    double i = a[1]._number;
    uint64_t v = new_obj(K_VEC3);
    if (!(i >= 0 && i < (double)nparticles)) return fail("TestTickContext.particle_position: There is no particle number %ld in the last TestTickContext.display_tick().", (long)i);
    objs[v - 1].x = particles[(size_t)i].x; objs[v - 1].y = particles[(size_t)i].y; objs[v - 1].z = particles[(size_t)i].z;
    return id(v);
}
REGISTER("TestTickContext", "particle_position", TTC_particle_position);

// The render camera (camera-Test, camera_pose-Test): the eye of the room, which is the origin less the
// runner's 3 block lift. No player moves, so the pose never changes, and no committed camera test needs more.
static union grug_value TTC_camera(ARGS) {
    UNUSED;
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = origin_x; objs[v - 1].y = origin_y - 3; objs[v - 1].z = origin_z;
    stubbed = true;
    return id(v);
}
REGISTER("TestTickContext", "camera", TTC_camera);

// Block shapes (expect_shape_box_unordered-Test, block_shape-Test's collision reads). A block that called
// set_shape is asked for its boxes by running its shape() export on a fresh entity, as GrugShapes.call does; any
// other grug block is the full cube. Only collision is modelled: a selection box is recorded and never read, and
// block_selection and block_ray_trace have no implementation, so a test that asks them fails by name.
#define SHAPE_BOXES_MAX 16
static struct { double v[6]; bool collision; } shape_boxes[SHAPE_BOXES_MAX];
static size_t nshape_boxes;
static uint64_t block_shape_fn;
// GrugRenderPass.isOrdered: no corner of a pair is past the other, and a NaN is not ordered.
static bool box_ordered(const double v[6]) { return v[0] <= v[3] && v[1] <= v[4] && v[2] <= v[5]; }
static union grug_value shape_add_box(const union grug_value a[], bool collision) {
    double v[6] = {a[1]._number, a[2]._number, a[3]._number, a[4]._number, a[5]._number, a[6]._number};
    if (!box_ordered(v))
        return fail("%s: The box was not recorded. Its corners have to be the right way round: the first of each pair is the near corner and must be no greater than the second, so add it as (0, 0, 0, 1, 1, 1) and not as (1, 0, 0, 0, 1, 1).",
            collision ? "ShapeContext.add_collision_box" : "ShapeContext.add_selection_box");
    if (nshape_boxes >= SHAPE_BOXES_MAX) { stubbed = true; return none(); }  // SMOKETEST_EXCL: no committed block adds more than 16 boxes in one call; the box is dropped, so a pass is STUBBED
    memcpy(shape_boxes[nshape_boxes].v, v, sizeof v);
    shape_boxes[nshape_boxes++].collision = collision;
    return none();
}
static union grug_value SC_add_collision_box(ARGS) { UNUSED; return shape_add_box(a, true); }
REGISTER("ShapeContext", "add_collision_box", SC_add_collision_box);
static union grug_value SC_add_selection_box(ARGS) { UNUSED; return shape_add_box(a, false); }  // SMOKETEST_EXCL: the only committed test that reaches a selection box (block_shape-Test) fails first on block_selection, which has no implementation yet
REGISTER("ShapeContext", "add_selection_box", SC_add_selection_box);
// Whether the box overlaps the other with a positive volume, which is what AxisAlignedBB.intersectsWith and
// Shapes.joinIsNotEmpty both ask of two boxes.
static bool boxes_overlap(const double lo[6], const double area[6]) {
    return lo[0] < area[3] && lo[3] > area[0] && lo[1] < area[4] && lo[4] > area[1] && lo[2] < area[5] && lo[5] > area[2];
}
static bool block_declares_shape(const char* name) {
    resolve_block(name);
    for (size_t i = 0; i < nblockdefs; i++) if (!strcmp(blockdefs[i].name, name)) return blockdefs[i].shape;
    return false;  // SMOKETEST_EXCL: resolve_block just made the entry of every grug block that has a script
}
static union grug_value TTC_block_collision(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_LEVEL)) return fail("TestTickContext.block_collision: not a Level");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a Level
    long p[3];
    if (!pos_arg(a + 1, p, "TestTickContext.block_collision")) return none();  // SMOKETEST_EXCL_BRANCH: no committed test asks at a non-finite position
    const double area[6] = {a[5]._number, a[6]._number, a[7]._number, a[8]._number, a[9]._number, a[10]._number};
    static const double cube[6] = {0, 0, 0, 1, 1, 1};
    struct block* b = find_block(p[0], p[1], p[2]);
    if (!b) return boolean(false);  // SMOKETEST_EXCL_BRANCH: no committed test asks about air
    struct grug_file_info* fi = block_file_of(b->name);
    if (!fi) { stubbed = true; return boolean(boxes_overlap(cube, area)); }  // SMOKETEST_EXCL_BRANCH: no committed test asks about a vanilla block, whose real shape (a torch has none) is the game's
    if (!block_declares_shape(b->name)) return boolean(boxes_overlap(cube, area));
    void* ent = grug_create_entity(g_state, fi->file_id);
    nshape_boxes = 0;
    union grug_value args[5] = {id(new_obj(K_SHAPE_CTX)), a[1], num((double)p[0]), num((double)p[1]), num((double)p[2])};
    char err[4096];
    call_export(ent, block_shape_fn, args, 5, err);
    grug_deinit_entity(g_state, ent);
    if (err[0]) return fail("%s", err);
    bool any = false, hit = false;
    for (size_t i = 0; i < nshape_boxes; i++) {
        any = true;
        if (shape_boxes[i].collision && boxes_overlap(shape_boxes[i].v, area)) hit = true;
    }
    for (size_t i = 0; i < nshape_boxes; i++)
        if (!shape_boxes[i].collision) stubbed = true;  // SMOKETEST_EXCL_BRANCH: a selection box is recorded and never read here, so a selection-only shape answering no collision is the model's guess, which no committed test asserts
    if (!any) { stubbed = true; return boolean(boxes_overlap(cube, area)); }  // SMOKETEST_EXCL_BRANCH: no committed shape() adds nothing; the game then keeps the full cube and reports the block
    return boolean(hit);
}
REGISTER("TestTickContext", "block_collision", TTC_block_collision);

// =============================================================================================
// SECTION: Item entities
// =============================================================================================
// Item entities (item_entity-Test, count_item_entities-Test): one record per item_entity call, whose
// ItemEntity and Entity handles both carry its index in x. It is in the world once Entity.spawn ran,
// and a world query counts only those. The test runner does not tick entities, so none moves, ages or
// is picked up. A query takes the entities within the radius of the point (a sphere); a loader may
// query a box instead, and no committed test tells the two apart, because count_item_entities-Test
// asserts the corner case in a form both pass.
struct ent { double x, y, z; uint64_t item; double damage, count, delay, vx, vy, vz; bool spawned; };
static struct ent* ents;
static size_t nents, capents;
static void clear_entities(void) { nents = 0; }
static struct ent* ent_of(uint64_t id, enum kind kind) {
    struct obj* o = get_obj(id, kind);
    return o ? &ents[(size_t)o->x] : NULL;
}
// A new zeroed record at the end of the table, which may move the table.
static struct ent* ent_new(void) {
    if (nents == capents) {
        capents = capents ? capents * 2 : 64;
        ents = xrealloc(ents, capents * sizeof *ents);
    }
    struct ent* e = &ents[nents++];
    memset(e, 0, sizeof *e);
    return e;
}
// The give itself: the stack goes into the first free inventory slot, and a give that finds every
// slot full drops it at the player's feet, where the runner snapped them, which is the test origin.
static void give_to_player(uint64_t item_id, double count) {
    for (size_t i = 0; i < INV_SLOTS; i++)  // SMOKETEST_EXCL_BRANCH: every committed give finds the first slot free, because the test clears the hand before giving
        if (!inv[i].has) {  // SMOKETEST_EXCL_BRANCH: as the loop says, the give returns at slot 0
            inv[i].has = true; inv[i].item = item_id; inv[i].count = count;
            return;
        }
    // SMOKETEST_EXCL_START: no committed test fills all 36 slots, so the drop is never read, and how many stacks a loader's own give needs to fill its inventory differs between loaders
    struct ent* e = ent_new();
    e->x = origin_x; e->y = origin_y; e->z = origin_z;
    e->item = item_id; e->count = count; e->spawned = true;
    // SMOKETEST_EXCL_STOP
}
static union grug_value hf_item_entity(ARGS) {
    UNUSED;
    struct obj* st = get_obj(a[4]._id, K_ITEM_STACK);
    if (!st) return fail("item_entity: not an ItemStack");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemStack
    struct ent* e = ent_new();
    e->x = a[1]._number; e->y = a[2]._number; e->z = a[3]._number;
    e->item = st->val._id; e->damage = st->x; e->count = st->y;
    uint64_t o = new_obj(K_ITEM_ENTITY);
    objs[o - 1].x = (double)(nents - 1);
    return id(o);
}
REGISTER(NULL, "item_entity", hf_item_entity);
static union grug_value IE_count(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ITEM_ENTITY);
    if (!e) return fail("ItemEntity.count: not an ItemEntity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemEntity
    return num(e->count);
}
REGISTER("ItemEntity", "count", IE_count);
static union grug_value IE_damage(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ITEM_ENTITY);
    if (!e) return fail("ItemEntity.damage: not an ItemEntity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemEntity
    return num(e->damage);
}
REGISTER("ItemEntity", "damage", IE_damage);
static union grug_value IE_pickup_delay(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ITEM_ENTITY);
    if (!e) return fail("ItemEntity.pickup_delay: not an ItemEntity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemEntity
    return num(e->delay);
}
REGISTER("ItemEntity", "pickup_delay", IE_pickup_delay);
static union grug_value IE_set_pickup_delay(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ITEM_ENTITY);
    if (!e) return fail("ItemEntity.set_pickup_delay: not an ItemEntity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemEntity
    e->delay = a[1]._number;
    return none();
}
REGISTER("ItemEntity", "set_pickup_delay", IE_set_pickup_delay);
static union grug_value IE_entity(ARGS) {
    UNUSED;
    struct obj* o = get_obj(a[0]._id, K_ITEM_ENTITY);
    if (!o) return fail("ItemEntity.entity: not an ItemEntity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemEntity
    double idx = o->x;  // before new_obj, which may move the table
    uint64_t n = new_obj(K_ENTITY);
    objs[n - 1].x = idx;
    return id(n);
}
REGISTER("ItemEntity", "entity", IE_entity);
static union grug_value Entity_set_delta_movement(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ENTITY);
    struct obj* v = get_obj(a[1]._id, K_VEC3);
    if (!e) return fail("Entity.set_delta_movement: not an Entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an Entity
    if (!v) return fail("Entity.set_delta_movement: not a Vec3");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a Vec3
    e->vx = v->x; e->vy = v->y; e->vz = v->z;
    return none();
}
REGISTER("Entity", "set_delta_movement", Entity_set_delta_movement);
static union grug_value Entity_get_delta_movement(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ENTITY);
    if (!e) return fail("Entity.get_delta_movement: not an Entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an Entity
    double vx = e->vx, vy = e->vy, vz = e->vz;  // before new_obj, which may move the table
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = vx; objs[v - 1].y = vy; objs[v - 1].z = vz;
    return id(v);
}
REGISTER("Entity", "get_delta_movement", Entity_get_delta_movement);
static union grug_value Entity_spawn(ARGS) {
    UNUSED;
    struct ent* e = ent_of(a[0]._id, K_ENTITY);
    if (!e) return fail("Entity.spawn: not an Entity");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an Entity
    if (e->spawned) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed test spawns an entity twice, so what the game does then is unknown
    e->spawned = true;
    return none();
}
REGISTER("Entity", "spawn", Entity_spawn);

// The spawned item entities within the radius of a point, the first one's index in *first. When
// item_id is non-zero only the entities carrying that item count, whatever the item's damage, which
// is Level.count_item_entities_of (count_item_entities_of-Test, autocrafting_table_pipe-Test).
static double items_near(const union grug_value a[], size_t* first, uint64_t item_id) {
    double n = 0;
    for (size_t i = 0; i < nents; i++) {
        double dx = ents[i].x - a[1]._number, dy = ents[i].y - a[2]._number, dz = ents[i].z - a[3]._number;
        if (!ents[i].spawned || !(sqrt(dx * dx + dy * dy + dz * dz) <= a[4]._number)) continue;
        if (item_id && ents[i].item != item_id) continue;
        if (n == 0) *first = i;
        n++;
    }
    return n;
}
static union grug_value Level_count_item_entities(ARGS) {
    UNUSED;
    size_t first = 0;
    return num(items_near(a, &first, 0));
}
REGISTER("Level", "count_item_entities", Level_count_item_entities);
// The item is at a[5], after the point and the radius the shared sweep reads.
static union grug_value Level_count_item_entities_of(ARGS) {
    UNUSED;
    if (!get_obj(a[5]._id, K_ITEM)) return fail("Level.count_item_entities_of: not an Item");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an Item
    size_t first = 0;
    return num(items_near(a, &first, a[5]._id));
}
REGISTER("Level", "count_item_entities_of", Level_count_item_entities_of);
static union grug_value Level_find_item_entity(ARGS) {
    UNUSED;
    size_t first = 0;
    double n = items_near(a, &first, 0);
    uint64_t o = new_obj(K_OPTION);
    if (n > 0) {
        objs[o - 1].has = true;
        uint64_t ie = new_obj(K_ITEM_ENTITY);
        objs[ie - 1].x = (double)first;
        objs[o - 1].val = id(ie);
    }
    return id(o);
}
REGISTER("Level", "find_item_entity", Level_find_item_entity);
// drop_inventory empties the block entity's slots into item entities (inventory-Test, adapter_branches-Test,
// test_box_clears_dropped_item-Test). 1.2.5 and the Ornithe and StationAPI adapters spawn one entity per slot at the
// given position, which is what this does; 1.20.6's Containers.dropContents splits a stack into random pieces and
// scatters them over the block, so the count and place of the entities are the game's there and a pass is STUBBED.
static union grug_value hf_drop_inventory(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_LEVEL)) return fail("drop_inventory: not a Level");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but a Level
    long p[3];
    if (!pos_arg(a, p, "drop_inventory")) return none();  // SMOKETEST_EXCL_BRANCH: no committed test drops at a non-finite position
    struct be* b = be_at(p[0], p[1], p[2]);
    if (!b) return fail("drop_inventory: there is no block entity with an inventory at that position");  // SMOKETEST_EXCL_BRANCH: no committed test drops where there is none
    stubbed = true;
    for (size_t i = 0; i < b->nslots; i++) {
        if (!b->slots[i].has) continue;
        struct ent* e = ent_new();
        e->x = a[1]._number; e->y = a[2]._number; e->z = a[3]._number;
        e->item = b->slots[i].item; e->damage = b->slots[i].damage; e->count = b->slots[i].count;
        e->spawned = true;
        b->slots[i].has = false; b->slots[i].count = 0; b->slots[i].damage = 0;
    }
    return none();
}
REGISTER(NULL, "drop_inventory", hf_drop_inventory);

// break_block breaks the block's own block and leaves its item unless its init() called
// set_drops_nothing (drops_nothing-Test, frame_break-Test). The toy world has no player, no tool and no
// drop chance, so the drop is always the block's own item, which is what the game's own break yields
// for a grug block. The drop lands at the block's centre; the game scatters it a little within the
// block, which no committed test reads. Air does nothing, as it does in the game.
static bool block_drops_nothing(const char* name) {
    for (size_t i = 0; i < nblockdefs; i++)
        if (!strcmp(blockdefs[i].name, name)) return blockdefs[i].drops_nothing;
    return false;  // SMOKETEST_EXCL: every block a committed test breaks is a declared grug: block, so the lookup always finds one
}
static union grug_value TTC_break_block(ARGS) {
    UNUSED;
    long p[3];
    if (!pos_arg(a + 1, p, "TestTickContext.break_block")) return none();
    struct block* b = find_block(p[0], p[1], p[2]);
    // A cell no test wrote reads as air, which is how Level.get_block and Level.is_air answer it.
    const char* name = b ? b->name : "minecraft:air";
    if (!strcmp(name, "minecraft:air")) return none();
    bool drop = !block_drops_nothing(name);
    // SMOKETEST_EXCL_START: no committed test breaks a vanilla block, and this host models no
    // vanilla loot table, so what the game leaves there is not known here.
    if (strncmp(name, "grug:", 5)) stubbed = true;
    // SMOKETEST_EXCL_STOP
    if (drop) {
        // The item id must outlive the write below, which frees the name it is looked up by, so the
        // name goes into the test's arena first.
        uint64_t item = item_named(keep(xstrdup(name)));
        struct ent* e = ent_new();
        e->x = (double)p[0] + 0.5; e->y = (double)p[1] + 0.5; e->z = (double)p[2] + 0.5;
        e->item = item; e->count = 1; e->spawned = true;
    }
    set_block(p[0], p[1], p[2], "minecraft:air");
    on_block_placed(p[0], p[1], p[2], "minecraft:air");
    return none();
}
REGISTER("TestTickContext", "break_block", TTC_break_block);

// Mod file states (the hot_reload*-Test files, expect_set_mod_file_state_*-Test). The real call rewrites the
// file on disk and the engine's watcher reports the change; the smoketest rewrites nothing (a test must
// never disturb the repository's mods) and counts a change per call that would have disturbed a file, which is
// all the tests' wait loops read. The reload itself is not run, so every test that calls it is STUBBED.
struct modfile { const char* path; const char* state; double changes; bool disturbed; };
static struct modfile* modfiles;
static size_t nmodfiles, capmodfiles;
static void clear_mod_files(void) { nmodfiles = 0; }
static struct modfile* modfile_of(const char* path, bool create) {
    for (size_t i = 0; i < nmodfiles; i++) if (!strcmp(modfiles[i].path, path)) return &modfiles[i];
    if (!create) return NULL;
    if (nmodfiles == capmodfiles) { capmodfiles = capmodfiles ? capmodfiles * 2 : 8; modfiles = xrealloc(modfiles, capmodfiles * sizeof *modfiles); }
    modfiles[nmodfiles] = (struct modfile){keep(xstrdup(path)), "normal", 0, false};
    return &modfiles[nmodfiles++];
}
static union grug_value TTC_set_mod_file_state(ARGS) {
    UNUSED;
    const char* path = a[1]._string; const char* state = a[2]._string;
    bool normal = !strcmp(state, "normal"), deleted = !strcmp(state, "deleted");
    if (!normal && !deleted && strcmp(state, "invalid") && strcmp(state, "no_trailing_newline") && strcmp(state, "swapped"))
        return fail("TestTickContext.set_mod_file_state: java.lang.IllegalArgumentException: Unknown mod file state '%s'. Expected \"normal\", \"invalid\", \"no_trailing_newline\", \"deleted\" or \"swapped\".", state);
    char full[4096];
    snprintf(full, sizeof full, "%s/%s", g_mods_dir, path);
    if (!normal && !deleted && access(full, R_OK) != 0) return fail("TestTickContext.set_mod_file_state: java.nio.file.NoSuchFileException: %s", full);
    stubbed = true;
    struct modfile* m = modfile_of(path, true);
    if (normal && !m->disturbed) return none();  // nothing disturbed the file, so nothing is put back
    m->state = keep(xstrdup(state));
    m->disturbed = !normal;
    m->changes++;
    return none();
}
REGISTER("TestTickContext", "set_mod_file_state", TTC_set_mod_file_state);
static union grug_value TTC_hot_reload_count(ARGS) {
    UNUSED;
    struct modfile* m = modfile_of(a[1]._string, false);
    return num(m ? m->changes : 0);
}
REGISTER("TestTickContext", "hot_reload_count", TTC_hot_reload_count);

// Colors and GUIs (gui_*-Test, expect_gui_*-Test, use_block_same_tick-Test). A GUI is a layout builder: the
// texture path, the crafting grids (each names nine inventory slots from its start), the crafting results,
// the texts and the player inventory. GUI.open checks the grids against the block entity's inventory and
// opens a screen: a panel with the grids' slots on the block entity, and a mouse cursor. No capture exists
// behind it, so a test that screenshots the screen still fails, and a pass that opened a screen is STUBBED.
#define GUI_MAX_ENTRIES 64  // GrugGuiLayoutCodec.MAX_ENTRIES
struct gui { size_t ngrids; int32_t grid_start[GUI_MAX_ENTRIES], grid_x[GUI_MAX_ENTRIES], grid_y[GUI_MAX_ENTRIES]; size_t nresults, ntexts; };
static struct gui* guis;
static size_t nguis, capguis;
// The open screen: the GUI it was built from (an index into guis) and the block entity handle it opened on.
// carried is the mouse cursor's stack, which only exists while a screen is open.
static bool screen_is_open;
static size_t screen_gui;
static uint64_t screen_be;
static bool carried_has;
static uint64_t carried_item;
static double carried_count, carried_damage;
static void clear_guis(void) { nguis = 0; screen_is_open = false; carried_has = false; }
// Why Color.rgba refuses these values (the Java colorProblem message), or false when each is a whole number from 0 to 255.
// SMOKETEST_EXCL_START: no committed test that reaches Color.rgba passes the smoketest yet; selftest.c checks the helper
static bool color_problem(double r, double g, double b, double a, char* out, size_t cap) {
    double v[4] = {r, g, b, a};
    bool ok = true;
    for (int i = 0; i < 4; i++) if (!(v[i] >= 0 && v[i] <= 255) || v[i] != floor(v[i])) ok = false;
    if (ok) return false;
    char t[4][400];
    for (int i = 0; i < 4; i++) number_text(v[i], t[i], sizeof t[i]);
    snprintf(out, cap, "Each value has to be a whole number from 0 to 255, and the values were %s, %s, %s and %s.", t[0], t[1], t[2], t[3]);
    return true;
}
// SMOKETEST_EXCL_STOP
static union grug_value Color_rgb(ARGS) { UNUSED; return id(new_obj(K_COLOR)); }
REGISTER("Color", "rgb", Color_rgb);
// SMOKETEST_EXCL_START: the only committed caller, overlay_triangles-Test, stops earlier at an overlay call the smoketest lacks
static union grug_value Color_rgba(ARGS) {
    UNUSED;
    char msg[2048];
    if (color_problem(a[0]._number, a[1]._number, a[2]._number, a[3]._number, msg, sizeof msg)) return fail("Color.rgba: %s", msg);
    return id(new_obj(K_COLOR));
}
// SMOKETEST_EXCL_STOP
REGISTER("Color", "rgba", Color_rgba);

// =============================================================================================
// SECTION: World overlay contexts, configuration and scans
// =============================================================================================
// The passes that run with no screen open, and the configuration and batch scan they hand out
// (#1255). A context is legal only while the pass it was made for is running, so a script that
// saves one and uses it later is refused, as the game refuses it.
static struct obj* pass_ctx(const union grug_value a[], enum kind kind, const char* method, const char* pass) {
    struct obj* o = get_obj(a[0]._id, kind);
    if (!o) { fail("%s: The context is not one this host made.", method); return NULL; }
    if (world_pass_serial == 0 || o->x != world_pass_serial) {
        fail("%s: Only legal on the context %s() was passed, while that call is running: a context saved for later is refused.", method, pass);
        return NULL;
    }
    return o;
}
static union grug_value OverlayWorldContext_camera_x(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.camera_x", "render_world") ? num(camera_x) : none(); }
REGISTER("OverlayWorldContext", "camera_x", OverlayWorldContext_camera_x);
static union grug_value OverlayWorldContext_camera_y(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.camera_y", "render_world") ? num(camera_y) : none(); }
REGISTER("OverlayWorldContext", "camera_y", OverlayWorldContext_camera_y);
static union grug_value OverlayWorldContext_camera_z(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.camera_z", "render_world") ? num(camera_z) : none(); }
REGISTER("OverlayWorldContext", "camera_z", OverlayWorldContext_camera_z);
static union grug_value OverlayWorldContext_player_x(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.player_x", "render_world") ? num(player_x) : none(); }
REGISTER("OverlayWorldContext", "player_x", OverlayWorldContext_player_x);
static union grug_value OverlayWorldContext_player_y(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.player_y", "render_world") ? num(player_y) : none(); }
REGISTER("OverlayWorldContext", "player_y", OverlayWorldContext_player_y);
static union grug_value OverlayWorldContext_player_z(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.player_z", "render_world") ? num(player_z) : none(); }
REGISTER("OverlayWorldContext", "player_z", OverlayWorldContext_player_z);
static union grug_value OverlayWorldContext_partial_tick(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.partial_tick", "render_world") ? num(0) : none(); }
REGISTER("OverlayWorldContext", "partial_tick", OverlayWorldContext_partial_tick);
// The one client level the toy world has.
static union grug_value OverlayWorldContext_get_level(ARGS) {
    UNUSED;
    return pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.get_level", "render_world") ? id(new_obj(K_LEVEL)) : none();
}
REGISTER("OverlayWorldContext", "get_level", OverlayWorldContext_get_level);
// The configuration of the mod whose export is running, which a test's own overlay reads.
static union grug_value OverlayWorldContext_get_config(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.get_config", "render_world")) return none();
    if (!world_pass_mod) return fail("OverlayWorldContext.get_config: The overlay is not one of a mod, so it has no config.");
    uint64_t o = new_obj(K_CONFIG);
    objs[o - 1].str = keep(xstrdup(world_pass_mod));
    return id(o);
}
REGISTER("OverlayWorldContext", "get_config", OverlayWorldContext_get_config);
static union grug_value OverlayWorldContext_get_spawn_squares(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.get_spawn_squares", "render_world")) return none();
    if (!scan_radius_ok("OverlayWorldContext.get_spawn_squares", a[1]._number)) return none();
    return id(toy_scan(a[1]._number));
}
REGISTER("OverlayWorldContext", "get_spawn_squares", OverlayWorldContext_get_spawn_squares);
// A line or a triangle is a mark: the frame drew the mod's overlay, which world_overlay_drawn
// answers. The color is a Color, and the positions have to be finite.
static union grug_value OverlayWorldContext_draw_line(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.draw_line", "render_world")) return none();
    if (!get_obj(a[7]._id, K_COLOR)) return fail("OverlayWorldContext.draw_line: The color is not one the host made.");
    for (int i = 1; i <= 6; i++)
        if (!isfinite(a[i]._number)) return fail("OverlayWorldContext.draw_line: The endpoints have to be finite, and one was not.");
    world_overlay_drawn_flag = true;
    return none();
}
REGISTER("OverlayWorldContext", "draw_line", OverlayWorldContext_draw_line);
static union grug_value OverlayWorldContext_draw_triangle(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_WORLD_CTX, "OverlayWorldContext.draw_triangle", "render_world")) return none();
    if (!get_obj(a[10]._id, K_COLOR)) return fail("OverlayWorldContext.draw_triangle: The color is not one the host made.");
    for (int i = 1; i <= 9; i++)
        if (!isfinite(a[i]._number)) return fail("OverlayWorldContext.draw_triangle: The corners have to be finite, and one was not.");
    world_overlay_drawn_flag = true;
    return none();
}
REGISTER("OverlayWorldContext", "draw_triangle", OverlayWorldContext_draw_triangle);
static union grug_value OverlayWorldKeyContext_key(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_KEY_CTX, "OverlayWorldKeyContext.key", "world_key") ? num(overlay_key_code) : none(); }
REGISTER("OverlayWorldKeyContext", "key", OverlayWorldKeyContext_key);
static union grug_value OverlayWorldKeyContext_ctrl(ARGS) { UNUSED; return pass_ctx(a, K_WORLD_KEY_CTX, "OverlayWorldKeyContext.ctrl", "world_key") ? boolean(overlay_ctrl) : none(); }
REGISTER("OverlayWorldKeyContext", "ctrl", OverlayWorldKeyContext_ctrl);
static union grug_value OverlayWorldBindingContext_id(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_BINDING_CTX, "OverlayWorldBindingContext.id", "world_binding")) return none();
    return str(overlay_binding_id ? overlay_binding_id : "");
}
REGISTER("OverlayWorldBindingContext", "id", OverlayWorldBindingContext_id);
static union grug_value OverlayWorldBindingContext_get_config(ARGS) {
    UNUSED;
    if (!pass_ctx(a, K_BINDING_CTX, "OverlayWorldBindingContext.get_config", "world_binding")) return none();
    if (!world_pass_mod) return fail("OverlayWorldBindingContext.get_config: The overlay is not one of a mod, so it has no config.");
    uint64_t o = new_obj(K_CONFIG);
    objs[o - 1].str = keep(xstrdup(world_pass_mod));
    return id(o);
}
REGISTER("OverlayWorldBindingContext", "get_config", OverlayWorldBindingContext_get_config);
// A mod's configuration: the option its about.json declares, read from the declared default. The
// smoketest has no config file, so every read is the default, and a key the mod does not declare is
// reported and answers its type's empty value, as the host does.
static const struct mod_option* config_option(const union grug_value a[], const char* method) {
    struct obj* o = get_obj(a[0]._id, K_CONFIG);
    if (!o) { fail("%s: not a Config", method); return NULL; }
    const struct mod_option* option = find_option(o->str, a[1]._string);
    if (!option) fail("%s: The mod %s declares no option named %s.", method, o->str, a[1]._string);
    return option;
}
static union grug_value Config_bool(ARGS) {
    UNUSED;
    const struct mod_option* option = config_option(a, "Config.bool");
    if (!option) return boolean(false);
    return boolean(!strcmp(option->type, "boolean") && !strcmp(option->def, "true"));
}
REGISTER("Config", "bool", Config_bool);
static union grug_value Config_number(ARGS) {
    UNUSED;
    const struct mod_option* option = config_option(a, "Config.number");
    if (!option || strcmp(option->type, "number")) return num(0);
    return num(strtod(option->def, NULL));
}
REGISTER("Config", "number", Config_number);
// The smoketest draws nothing, so the color a read answers is a token; a colour option the mod does
// not declare answers opaque magenta, which is still a Color.
static union grug_value Config_color(ARGS) { UNUSED; (void)config_option(a, "Config.color"); return id(new_obj(K_COLOR)); }
REGISTER("Config", "color", Config_color);
static struct square_scan* scan_of(uint64_t id) {
    struct obj* o = get_obj(id, K_SQUARES);
    if (!o) { fail("SpawnSquares: not a scan"); return NULL; }
    return &scans[(size_t)o->x];
}
static union grug_value SpawnSquares_count(ARGS) { UNUSED; struct square_scan* s = scan_of(a[0]._id); return num(s ? (double)s->count : 0); }
REGISTER("SpawnSquares", "count", SpawnSquares_count);
// An index into a scan, reported when it is not one, as every read of the batch is.
static const struct square* square_at(const union grug_value a[], const char* method) {
    struct square_scan* s = scan_of(a[0]._id);
    if (!s) return NULL;
    double index = a[1]._number;
    if (!is_whole(index) || index < 0 || index >= (double)s->count) {
        fail("%s: The index has to be a whole number from 0 to %g, and was %g.", method, (double)s->count - 1, index);
        return NULL;
    }
    return &s->squares[(size_t)index];
}
static union grug_value SpawnSquares_x(ARGS) { UNUSED; const struct square* q = square_at(a, "SpawnSquares.x"); return num(q ? (double)q->x : 0); }
REGISTER("SpawnSquares", "x", SpawnSquares_x);
static union grug_value SpawnSquares_y(ARGS) { UNUSED; const struct square* q = square_at(a, "SpawnSquares.y"); return num(q ? (double)q->y : 0); }
REGISTER("SpawnSquares", "y", SpawnSquares_y);
static union grug_value SpawnSquares_z(ARGS) { UNUSED; const struct square* q = square_at(a, "SpawnSquares.z"); return num(q ? (double)q->z : 0); }
REGISTER("SpawnSquares", "z", SpawnSquares_z);
static union grug_value SpawnSquares_block_light(ARGS) { UNUSED; const struct square* q = square_at(a, "SpawnSquares.block_light"); return num(q ? (double)q->block_light : 0); }
REGISTER("SpawnSquares", "block_light", SpawnSquares_block_light);
static union grug_value SpawnSquares_sky_light(ARGS) { UNUSED; const struct square* q = square_at(a, "SpawnSquares.sky_light"); return num(q ? (double)q->sky_light : 0); }
REGISTER("SpawnSquares", "sky_light", SpawnSquares_sky_light);

static union grug_value hf_gui(ARGS) {
    UNUSED;
    if (nguis == capguis) { capguis = capguis ? capguis * 2 : 8; guis = xrealloc(guis, capguis * sizeof *guis); }
    memset(&guis[nguis], 0, sizeof guis[nguis]);
    uint64_t o = new_obj(K_GUI);
    objs[o - 1].x = (double)nguis++;
    return id(o);
}
REGISTER(NULL, "gui", hf_gui);
static struct gui* gui_of(uint64_t h, const char* who) {
    struct obj* o = get_obj(h, K_GUI);
    if (!o) { fail("%s: not a GUI", who); return NULL; }  // SMOKETEST_EXCL: no committed test passes anything but a GUI
    return &guis[(size_t)o->x];
}
static bool gui_full(size_t n, const char* who, const char* what) {
    if (n < GUI_MAX_ENTRIES) return false;
    fail("%s: A GUI holds at most %d %s, so this one is not added.", who, GUI_MAX_ENTRIES, what);
    return true;
}
static union grug_value GUI_add_crafting_grid(ARGS) {
    UNUSED;
    struct gui* gu = gui_of(a[0]._id, "GUI.add_crafting_grid");
    if (!gu || gui_full(gu->ngrids, "GUI.add_crafting_grid", "crafting grids")) return none();
    gu->grid_start[gu->ngrids] = java_int(a[1]._number);
    gu->grid_x[gu->ngrids] = java_int(a[2]._number);
    gu->grid_y[gu->ngrids] = java_int(a[3]._number);
    gu->ngrids++;
    return none();
}
REGISTER("GUI", "add_crafting_grid", GUI_add_crafting_grid);
static union grug_value GUI_add_crafting_result(ARGS) {
    UNUSED;
    struct gui* gu = gui_of(a[0]._id, "GUI.add_crafting_result");
    if (!gu || gui_full(gu->nresults, "GUI.add_crafting_result", "crafting results")) return none();
    gu->nresults++;
    return none();
}
REGISTER("GUI", "add_crafting_result", GUI_add_crafting_result);
static union grug_value GUI_add_player_inventory(ARGS) {
    UNUSED;
    gui_of(a[0]._id, "GUI.add_player_inventory");
    return none();
}
REGISTER("GUI", "add_player_inventory", GUI_add_player_inventory);
static union grug_value GUI_add_text(ARGS) {
    UNUSED;
    struct gui* gu = gui_of(a[0]._id, "GUI.add_text");
    if (gu && !get_obj(a[4]._id, K_COLOR)) return fail("GUI.add_text: not a color");  // SMOKETEST_EXCL: no committed test passes anything but a Color; Java checks the handle before the cap and the text
    if (!gu || gui_full(gu->ntexts, "GUI.add_text", "texts")) return none();
    if (strlen(a[1]._string) > (1u << 20)) return fail("GUI.add_text: A GUI's text is at most 1048576 bytes long, so this one of %zu bytes is not added.", strlen(a[1]._string));  // SMOKETEST_EXCL: no committed test adds a megabyte of text
    gu->ntexts++;
    return none();
}
REGISTER("GUI", "add_text", GUI_add_text);
static union grug_value GUI_open(ARGS) {
    UNUSED;
    struct gui* gu = gui_of(a[0]._id, "GUI.open");
    if (!gu) return none();  // SMOKETEST_EXCL: gui_of raised already
    struct be* b = be_of(a[2]._id);
    if (!b) return fail("GUI.open: not a live block entity");  // SMOKETEST_EXCL: no committed test
    for (size_t i = 0; i < gu->ngrids; i++)
        for (long k = 0; k < 9; k++) {
            long slot = (int32_t)((uint32_t)gu->grid_start[i] + (uint32_t)k);  // int addition wraps in Java
            if (slot < 0 || slot >= (long)b->nslots)
                return fail("GUI.open: The GUI uses slot %ld, but the block entity only has %zu inventory slots.", slot, b->nslots);
        }
    screen_is_open = true;
    screen_gui = (size_t)(gu - guis);
    screen_be = a[2]._id;
    carried_has = false;
    stubbed = true;  // no capture exists behind the screen, and its window is an assumed one (see the screen geometry)
    return none();
}
REGISTER("GUI", "open", GUI_open);
// A screen is open after GUI.open until close_screen, and screen_open-Test asserts it closed after close_screen.
static union grug_value TTC_screen_open(ARGS) { UNUSED; return boolean(screen_is_open); }
REGISTER("TestTickContext", "screen_open", TTC_screen_open);

// Screen geometry and clicks (gui_click-Test, gui_click_empty-Test, forwarded_gui_*-Test, forwarded_screen_*-Test,
// forwarded_screen_geometry-Test). AbstractContainerScreen's panel is imageWidth by imageHeight, 176 by 166,
// centered in the window with integer division, and a GrugScreen adds nothing to that. The CI runner's window is
// 1280 by 720 at GUI scale 3 (gui_builder-Test's own measurement), so the window is 427 by 240 scaled pixels; no
// test pins the window, only that the panel is centered in it, so the number is an assumption the pass inherits
// (GUI.open already marks the run stubbed). With no screen open every read answers 0, as a client with no container
// screen does (1.2.5 leaves an empty screen up there, which loader_name == "1.2.5-forge" tests branch on).
#define PANEL_W 176
#define PANEL_H 166
#define WINDOW_W 427
#define WINDOW_H 240
static double gui_geometry(int which) {  // 0 left, 1 top, 2 width, 3 height, 4 window width
    if (!screen_is_open) return 0;
    switch (which) {
    case 0: return (WINDOW_W - PANEL_W) / 2;
    case 1: return (WINDOW_H - PANEL_H) / 2;
    case 2: return PANEL_W;
    case 3: return PANEL_H;
    default: return WINDOW_W;
    }
}
static union grug_value TTC_gui_left(ARGS) { UNUSED; return num(gui_geometry(0)); }
REGISTER("TestTickContext", "gui_left", TTC_gui_left);
static union grug_value TTC_gui_top(ARGS) { UNUSED; return num(gui_geometry(1)); }
REGISTER("TestTickContext", "gui_top", TTC_gui_top);
static union grug_value TTC_gui_width(ARGS) { UNUSED; return num(gui_geometry(2)); }
REGISTER("TestTickContext", "gui_width", TTC_gui_width);
static union grug_value TTC_gui_height(ARGS) { UNUSED; return num(gui_geometry(3)); }
REGISTER("TestTickContext", "gui_height", TTC_gui_height);
static union grug_value TTC_screen_width(ARGS) { UNUSED; return num(gui_geometry(4)); }
REGISTER("TestTickContext", "screen_width", TTC_screen_width);

// The mouse cursor's stack. set_carried_item puts one of the item on it and does nothing with no screen open,
// clear_carried_item empties it, and get_carried_item answers it or an empty Option.
static union grug_value TTC_set_carried_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_ITEM)) return fail("TestTickContext.set_carried_item: not an Item");  // SMOKETEST_EXCL: no committed test
    if (!screen_is_open) return none();  // SMOKETEST_EXCL: every committed caller has a screen open
    carried_has = true; carried_item = a[1]._id; carried_count = 1; carried_damage = 0;
    return none();
}
REGISTER("TestTickContext", "set_carried_item", TTC_set_carried_item);
static union grug_value TTC_clear_carried_item(ARGS) { UNUSED; carried_has = false; return none(); }
REGISTER("TestTickContext", "clear_carried_item", TTC_clear_carried_item);
static union grug_value TTC_get_carried_item(ARGS) {
    UNUSED;
    uint64_t o = new_obj(K_OPTION);
    if (carried_has) { objs[o - 1].has = true; objs[o - 1].val = id(carried_item); }
    return id(o);
}
REGISTER("TestTickContext", "get_carried_item", TTC_get_carried_item);

// Why TestTickContext.click_screen refuses a press, or false (HostFunctions.screenClickProblem and
// GrugScreenCalls.buttonProblem, whose MAX_BUTTON is 2).
// SMOKETEST_EXCL_START: no committed test presses with a bad button or position; selftest.c checks the helper
static bool click_problem(double x, double y, double button, char* out, size_t cap) {
    if (!isfinite(x) || !isfinite(y)) { snprintf(out, cap, "The position has to be finite."); return true; }
    if (!isfinite(button)) { snprintf(out, cap, "The button has to be finite."); return true; }
    if (button != floor(button) || button < 0 || button > 2) {
        char t[400];
        double_text(button, t, sizeof t);
        snprintf(out, cap, "The button has to be a whole number from 0 to 2, where 0 is the left button, 1 the right and 2 the middle, and was %s.", t);
        return true;
    }
    return false;
}
// SMOKETEST_EXCL_STOP
// SMOKETEST_EXCL_START: the committed presses all land on the centre of a slot; selftest.c checks the square's edges
static bool slot_hovered(double mx, double my, double sx, double sy) { return mx >= sx - 1 && mx < sx + 17 && my >= sy - 1 && my < sy + 17; }
// SMOKETEST_EXCL_STOP
// A press the open screen's overlays were asked for, and whether one took it (#615, #863). The
// overlay section below owns the pass; this is only its entry from the screen's own press path.
static bool overlay_click_taken(double x, double y, double button);
// A press on the open container screen. The open screen's overlays are asked first, and a press one
// took answers true and never reaches the screen. A press no overlay took is the screen's own: on a
// chest or a GUI it reaches the slot model below, which takes a left click the way vanilla's pickup
// does for the two cases the committed tests assert: an empty cursor picks the slot's stack up, and a
// carried stack goes whole into an empty slot. Every other click (a right or middle button, a slot that already
// holds something while the cursor carries, the result slot, a press outside every grid slot) is not modelled and
// marks the run stubbed. A slot answers a press from one pixel left of and above its 16 by 16 square to one pixel
// past it (AbstractContainerScreen.isHovering).
static union grug_value TTC_click_screen(ARGS) {
    UNUSED;
    char msg[600];
    if (click_problem(a[1]._number, a[2]._number, a[3]._number, msg, sizeof msg)) return fail("TestTickContext.click_screen: %s", msg);  // SMOKETEST_EXCL: see click_problem
    if (overlay_click_taken(a[1]._number, a[2]._number, a[3]._number)) return boolean(true);
    if (!screen_is_open) { stubbed = true; return boolean(false); }  // SMOKETEST_EXCL: every committed click that passes has a screen open
    struct be* b = be_of(screen_be);
    if (!b) { stubbed = true; return boolean(false); }  // the player's inventory screen has no block entity, and the vanilla slot overlay-Test presses on it is not modelled, so that run is STUBBED
    struct gui* gu = &guis[screen_gui];
    double mx = a[1]._number - gui_geometry(0), my = a[2]._number - gui_geometry(1);
    struct slot* hit = NULL;
    for (size_t g = 0; g < gu->ngrids && !hit; g++)  // SMOKETEST_EXCL_BRANCH: every committed press hits a slot of the first grid, so no loop runs out of grids
        for (int k = 0; k < 9 && !hit; k++) {  // SMOKETEST_EXCL_BRANCH: the committed presses hit slot 0 of the grid, so the nine slots never run out
            double sx = gu->grid_x[g] + (k % 3) * 18, sy = gu->grid_y[g] + (k / 3) * 18;
            if (slot_hovered(mx, my, sx, sy)) hit = slot_of(b, (double)(int32_t)((uint32_t)gu->grid_start[g] + (uint32_t)k));
        }
    if (a[3]._number != 0 || !hit) { stubbed = true; return boolean(false); }  // SMOKETEST_EXCL_BRANCH: the committed presses are all left clicks on a grid slot
    if (!carried_has && hit->has) {
        carried_has = true; carried_item = hit->item; carried_count = hit->count; carried_damage = hit->damage;
        slot_clear(hit);
    } else if (carried_has && !hit->has) {
        hit->has = true; hit->item = carried_item; hit->count = carried_count; hit->damage = carried_damage;
        carried_has = false;
    } else if (carried_has) stubbed = true;  // SMOKETEST_EXCL: a carried stack over a full slot swaps or merges, and no committed test presses one
    if (b->item_inserted) stubbed = true;  // SMOKETEST_EXCL_BRANCH: whether a slot click runs item_inserted is the loader's, and no committed block with a screen declares it
    return boolean(false);
}
REGISTER("TestTickContext", "click_screen", TTC_click_screen);

// Ticking a level and sounds (tick_level-Test, expect_tick_level_*-Test, sound-Test, expect_*sound*-Test).
// The smoketest runs no world tick, so tick_level answers 0, which the API defines as "a loader that cannot
// advance its level", and leaves the clock alone; a test that needs the ticks to have run still fails.
// The game has no sound table either, so has_sound is false for every well-formed name, like a 1.2.5 run
// with an empty pool, and play_ui_sound reports the unknown sound after the argument checks.
// Why a tick count is not a whole number from 0 to Integer.MAX_VALUE (GrugTestState.checkTickCount), or false.
static bool tick_count_problem(double ticks, char* out, size_t cap) {
    if (ticks >= 0 && ticks <= 2147483647.0 && ticks == floor(ticks)) return false;
    char t[400];
    double_text(ticks, t, sizeof t);
    snprintf(out, cap, "The number of ticks has to be a whole number from 0 to 2147483647, and was %s.", t);
    return true;
}
static union grug_value TTC_tick_level(ARGS) {
    UNUSED;
    char msg[512];
    if (tick_count_problem(a[2]._number, msg, sizeof msg)) { fail("TestTickContext.tick_level: %s", msg); return num(0); }
    stubbed = true;
    return num(0);
}
REGISTER("TestTickContext", "tick_level", TTC_tick_level);
// The daylight cycle a test can stop to prove the two clocks apart (#1762). The toy world has no such
// rule, so the test's arm is skipped and the run is STUBBED, which is honest: it is never PASSED
// without the rule having had an effect. The toy world has no sky either, so it has no separate day
// time, and -1 is the API's answer for that.
static union grug_value TTC_get_day_time(ARGS) {
    UNUSED;
    stubbed = true;
    return num(-1);
}
REGISTER("TestTickContext", "get_day_time", TTC_get_day_time);
static union grug_value TTC_set_daylight_cycle(ARGS) {
    UNUSED;
    stubbed = true;
    return boolean(false);
}
REGISTER("TestTickContext", "set_daylight_cycle", TTC_set_daylight_cycle);
// A block id above 255 surviving the game's own chunk save and load (high_block_round_trip-Test, #518).
// The toy world hands blocks out as names with no numeric id and saves no chunk, so it has no such id
// to round trip; -1 is the API's no-op answer for a loader whose block ids never reach there.
static union grug_value TTC_high_block_round_trip(ARGS) { UNUSED; return num(-1); }
REGISTER("TestTickContext", "high_block_round_trip", TTC_high_block_round_trip);
// GrugSoundArgs.nameProblem: [a-z0-9_.-]+:[a-z0-9_./-]+, the whole string.
static bool sound_name_ok(const char* n) {
    const char* c = strchr(n, ':');
    if (!c || c == n || !c[1]) return false;
    for (const char* p = n; p < c; p++) if (!(islower((unsigned char)*p) || isdigit((unsigned char)*p) || strchr("_.-", *p))) return false;
    for (const char* p = c + 1; *p; p++) if (!(islower((unsigned char)*p) || isdigit((unsigned char)*p) || strchr("_./-", *p))) return false;
    return true;
}
static void sound_name_problem(const char* n, char* out, size_t cap) {
    snprintf(out, cap, "A sound name is a namespace, a colon and a path, such as \"minecraft:ui.button.click\", and \"%s\" is not one.", n);
}
static union grug_value hf_has_sound(ARGS) {
    UNUSED;
    if (!sound_name_ok(a[0]._string)) {
        char msg[1024];
        sound_name_problem(a[0]._string, msg, sizeof msg);
        fail("has_sound: %s", msg);
    }
    // sound-Test branches on loader_name == "1.20.6-forge", which the smoketest is not, so it takes the older
    // versions' branch (no ui.button.click) because of its name, not because it modelled a pool: STUBBED.
    else stubbed = true;
    return boolean(false);
}
REGISTER(NULL, "has_sound", hf_has_sound);
static union grug_value hf_play_ui_sound(ARGS) {
    UNUSED;
    const char* name = a[0]._string;
    double vol = a[1]._number, pitch = a[2]._number;
    char t[400];
    if (!sound_name_ok(name)) { char msg[1024]; sound_name_problem(name, msg, sizeof msg); return fail("play_ui_sound: %s", msg); }
    if (!(vol >= 0 && vol <= 1)) { double_text(vol, t, sizeof t); return fail("play_ui_sound: The volume has to be from 0 to 1, and was %s.", t); }
    if (!(pitch >= 0.5 && pitch <= 2)) { double_text(pitch, t, sizeof t); return fail("play_ui_sound: The pitch has to be from 0.5 to 2, and was %s.", t); }
    return fail("play_ui_sound: The game has no sound named \"%s\". Ask has_sound first for a sound the versions the mod runs on do not all have.", name);
}
REGISTER(NULL, "play_ui_sound", hf_play_ui_sound);

// Block entity NBT edits (block_entity_nbt-Test, expect_nbt_*-Test, expect_extract_item_into_no_slot-Test).
// set_nbt_number stages an edit and load_nbt applies the staged edits to the NBT the block entity would
// write. The smoketest holds no NBT tree, so it models only what a grug block entity's NBT is made of
// that the committed tests edit: GrugInvSize (the inventory size), GrugNumbers (the saved numbers
// sorted by name, as a list of Name and Value compounds) and GrugLists (the saved lists sorted by name, each
// with Records holding Numbers sorted by name and an Item). A path into anything else is a key no grug
// block entity reads, which the real load drops, and a path the model cannot place marks the run STUBBED.
static bool nbt_is_index(const char* p, size_t n) {
    if (!n) return false;
    for (size_t i = 0; i < n; i++) if (p[i] < '0' || p[i] > '9') return false;
    return true;
}
// SMOKETEST_EXCL_START: the validation helpers are reached by selftest.c, which proves every message against GrugBlockEntityNbt; no committed test stages a bad type, value or path
// Why a dotted path is not a path (the Java parsePath messages), or false when it is one.
static bool nbt_path_problem(const char* path, char* out, size_t cap) {
    bool prev_index = false, first = true;
    const char* p = path;
    for (;;) {
        const char* dot = strchr(p, '.');
        size_t n = dot ? (size_t)(dot - p) : strlen(p);
        if (!n) { snprintf(out, cap, "The path \"%s\" has an empty part.", path); return true; }
        bool idx = nbt_is_index(p, n);
        if (idx && (first || prev_index)) { snprintf(out, cap, "The path \"%s\" has an index that does not follow a key.", path); return true; }
        if (idx && n > 9) { snprintf(out, cap, "The index %.*s in the path \"%s\" is too large.", (int)n, p, path); return true; }
        prev_index = idx; first = false;
        if (!dot) break;
        p = dot + 1;
    }
    if (prev_index) { snprintf(out, cap, "The path \"%s\" has to end in a key, not an index.", path); return true; }
    return false;
}
// Why a value does not fit its NBT type (the Java checkValue message), or false when it does.
static bool nbt_value_problem(const char* type, double v, char* out, size_t cap) {
    double lo, hi;
    if (!strcmp(type, "byte")) { lo = -128; hi = 127; }
    else if (!strcmp(type, "short")) { lo = -32768; hi = 32767; }
    else if (!strcmp(type, "int")) { lo = -2147483648.0; hi = 2147483647.0; }
    else if (!strcmp(type, "long")) { lo = -9223372036854775808.0; hi = 9223372036854775807.0; }
    else return false;
    if (v == rint(v) && v >= lo && v <= hi) return false;
    char vt[64], lot[32], hit[32];
    double_text(v, vt, sizeof vt);
    snprintf(lot, sizeof lot, "%.0f", lo);
    // Java casts the bounds to long, which saturates 2^63 to Long.MAX_VALUE
    snprintf(hit, sizeof hit, "%s", !strcmp(type, "long") ? "9223372036854775807" : "");
    if (!hit[0]) snprintf(hit, sizeof hit, "%.0f", hi);
    snprintf(out, cap, "A %s has to be a whole number from %s to %s, not %s.", type, lot, hit, vt);
    return true;
}
// SMOKETEST_EXCL_STOP
struct nbt_edit { size_t be; char* path; double value; bool is_item; uint64_t item; };
static struct nbt_edit* nbt_staged;
static size_t nnbt_staged, capnbt_staged;
static void clear_nbt(void) {
    for (size_t i = 0; i < nnbt_staged; i++) free(nbt_staged[i].path);
    nnbt_staged = 0;
}
static void nbt_stage(struct be* b, const char* path, double value, bool is_item, uint64_t item) {
    if (nnbt_staged == capnbt_staged) { capnbt_staged = capnbt_staged ? capnbt_staged * 2 : 8; nbt_staged = xrealloc(nbt_staged, capnbt_staged * sizeof *nbt_staged); }
    nbt_staged[nnbt_staged++] = (struct nbt_edit){(size_t)(b - bes), xstrdup(path), value, is_item, item};
}
static union grug_value TTC_set_nbt_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    if (!b) return fail("TestTickContext.set_nbt_number: not a live block entity");  // SMOKETEST_EXCL: no committed test
    const char* type = a[3]._string;
    char msg[512];
    if (strcmp(type, "byte") && strcmp(type, "short") && strcmp(type, "int") && strcmp(type, "long") && strcmp(type, "float") && strcmp(type, "double"))
        return fail("TestTickContext.set_nbt_number: The type has to be one of [byte, short, int, long, float, double], not \"%s\".", type);  // SMOKETEST_EXCL: no committed test stages a bad type
    if (nbt_value_problem(type, a[4]._number, msg, sizeof msg)) return fail("TestTickContext.set_nbt_number: %s", msg);  // SMOKETEST_EXCL: no committed test stages a bad value
    if (nbt_path_problem(a[2]._string, msg, sizeof msg)) return fail("TestTickContext.set_nbt_number: %s", msg);  // SMOKETEST_EXCL: no committed test stages a bad path
    nbt_stage(b, a[2]._string, a[4]._number, false, 0);
    return none();
}
REGISTER("TestTickContext", "set_nbt_number", TTC_set_nbt_number);
static union grug_value TTC_set_nbt_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    if (!b) return fail("TestTickContext.set_nbt_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    char msg[512];
    if (nbt_path_problem(a[2]._string, msg, sizeof msg)) return fail("TestTickContext.set_nbt_item: %s", msg);  // SMOKETEST_EXCL: no committed test stages a bad path
    nbt_stage(b, a[2]._string, 0, true, a[3]._id);
    return none();
}
REGISTER("TestTickContext", "set_nbt_item", TTC_set_nbt_item);
static int cmp_numbers_by_name(const void* l, const void* r) { return strcmp(((const struct kv*)l)->k, ((const struct kv*)r)->k); }
static int cmp_lists_by_name(const void* l, const void* r) { return strcmp(((const struct list*)l)->name, ((const struct list*)r)->name); }  // SMOKETEST_EXCL: the only committed edit of a list's record has one list on the block entity, so no comparison runs
// The saved number at an index of the numbers sorted by name, or NULL.
static struct kv* nth_number_by_name(struct kv* nums, size_t n, size_t i) {
    struct kv* tmp = xmalloc((n + 1) * sizeof *tmp);
    memcpy(tmp, nums, n * sizeof *tmp);
    qsort(tmp, n, sizeof *tmp, cmp_numbers_by_name);
    struct kv* hit = NULL;
    for (size_t j = 0; j < n && i < n; j++) if (nums[j].k == tmp[i].k) hit = &nums[j];
    free(tmp);
    return hit;
}
// Splits a dotted path into its pieces, in place; returns how many (up to 8).
static size_t nbt_split(char* path, char** piece) {
    size_t n = 0;
    for (char* p = path; n < 8;) {
        piece[n++] = p;
        char* dot = strchr(p, '.');
        if (!dot) break;
        *dot = 0;
        p = dot + 1;
    }
    return n;
}
static bool nbt_index(const char* p, size_t* out) {
    char* end;
    if (!nbt_is_index(p, strlen(p))) return false;
    *out = strtoul(p, &end, 10);
    return true;
}
// Applies one edit to the block entity, or returns the message of why it does not fit.
static bool nbt_apply(struct be* b, const struct nbt_edit* e, char* out, size_t cap) {
    char path[512], *p[8];
    snprintf(path, sizeof path, "%s", e->path);
    size_t n = nbt_split(path, p), i, j, k;
    if (n == 1 && !strcmp(p[0], "GrugInvSize")) {
        for (size_t q = 0; q < b->nslots; q++) if (b->slots[q].has) stubbed = true;  // SMOKETEST_EXCL: no committed test resizes an inventory that holds items; the real load keeps the stacks that fit
        free(b->slots);
        give_slots(b, (long)e->value);
        return false;
    }
    if (n >= 3 && !strcmp(p[0], "GrugNumbers") && nbt_index(p[1], &i) && !strcmp(p[2], "Value")) {
        if (i > b->nnums) {
            snprintf(out, cap, "The index %zu in the path \"%s\" is past the end of a list of %zu, and only its size appends.", i, e->path, b->nnums);  // SMOKETEST_EXCL: no committed test indexes past the end of the numbers
            return true;  // SMOKETEST_EXCL: same
        }
        struct kv* hit = nth_number_by_name(b->nums, b->nnums, i);
        if (n > 3 && hit) {
            snprintf(out, cap, "The key \"Value\" in the path \"%s\" does not hold a compound.", e->path);
            return true;
        }
        if (n > 3) stubbed = true;  // SMOKETEST_EXCL: no committed test walks into the Value of an appended compound
        if (hit) hit->v = e->value;
        return false;  // an index at the size appends a compound with a Value and no Name, which the load drops
    }
    if (n >= 5 && !strcmp(p[0], "GrugLists") && nbt_index(p[1], &i) && !strcmp(p[2], "Records") && nbt_index(p[3], &j)) {
        struct list* sorted = xmalloc((b->nlists + 1) * sizeof *sorted);
        memcpy(sorted, b->lists, b->nlists * sizeof *sorted);
        qsort(sorted, b->nlists, sizeof *sorted, cmp_lists_by_name);
        struct list* l = i < b->nlists ? find_list(b, sorted[i].name) : NULL;
        free(sorted);
        if (l) {
            if (j > l->nr) {
                snprintf(out, cap, "The index %zu in the path \"%s\" is past the end of a list of %zu, and only its size appends.", j, e->path, l->nr);  // SMOKETEST_EXCL: no committed test indexes past the end of a record list
                return true;  // SMOKETEST_EXCL: same
            }
            if (j == l->nr) {
                l->r = xrealloc(l->r, (l->nr + 1) * sizeof *l->r);
                memset(&l->r[l->nr++], 0, sizeof *l->r);
            }
            struct record* r = &l->r[j];
            if (n == 5 && !strcmp(p[4], "Item") && e->is_item) { r->item = e->item; r->has_item = true; return false; }
            if (n == 7 && !strcmp(p[4], "Numbers") && nbt_index(p[5], &k) && !strcmp(p[6], "Value")) {
                struct kv* hit = nth_number_by_name(r->n, r->nn, k);
                if (hit) hit->v = e->value;
                return false;
            }
        }
    }
    stubbed = true;  // a key the model does not hold: the loader writes it into NBT that no grug block entity reads
    return false;
}
static union grug_value TTC_load_nbt(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    if (!b) return fail("TestTickContext.load_nbt: not a live block entity");  // SMOKETEST_EXCL: no committed test
    size_t idx = (size_t)(b - bes), kept = 0, taken = 0;
    char msg[512];
    bool bad = false;
    for (size_t i = 0; i < nnbt_staged; i++) {
        if (nbt_staged[i].be != idx) { nbt_staged[kept++] = nbt_staged[i]; continue; }  // SMOKETEST_EXCL: no committed test stages edits for two block entities at once
        taken++;
        if (!bad && nbt_apply(b, &nbt_staged[i], msg, sizeof msg)) bad = true;
        free(nbt_staged[i].path);
    }
    nnbt_staged = kept;
    if (!taken) return fail("TestTickContext.load_nbt: Nothing was staged for this block entity.");
    if (bad) return fail("TestTickContext.load_nbt: %s", msg);
    return none();
}
REGISTER("TestTickContext", "load_nbt", TTC_load_nbt);

// =============================================================================================
// SECTION: Runner settings and graphics stubs
// =============================================================================================
// The runner's own test settings. Graphics calls are stubs: a capture is a token equal to itself,
// and the world is always settled, so a test that mixes world changes with a screenshot goes on.
static int max_ticks = 200;
// The range is mod_api.json's; no committed test asserts the error.
static union grug_value TIC_set_max_ticks(ARGS) {
    UNUSED;
    double n = a[1]._number;
    if (!is_whole(n) || n < 200 || n > 2147483647.0) return fail("The ticks have to be a whole number from 200 to 2147483647");
    if (n > max_ticks) max_ticks = (int)n;
    return none();
}
REGISTER("TestInitContext", "set_max_ticks", TIC_set_max_ticks);
// Only the upper bound has a committed test (expect_box_radius_beyond_view-Test); the room itself
// is not built, so a pass is STUBBED.
static union grug_value TTC_set_box_radius(ARGS) {
    UNUSED;
    stubbed = true;
    // The room build's sweep removes the item entities in the test area (test_box_sweep-Test); the room
    // itself is not built, so every spawned item entity is taken out of the world.
    for (size_t i = 0; i < nents; i++) ents[i].spawned = false;
    if (a[1]._number > 30) return fail("The radius has to be at most 30: a wider room would reach chunks the client never receives");
    return none();
}
REGISTER("TestTickContext", "set_box_radius", TTC_set_box_radius);
// Screenshots are tokens equal to themselves (#1548): nothing is drawn, so a capture is always the
// same as the reference and a test that mixes world changes with a screenshot goes on. The one
// comparison that cannot hold is differs_from, which fails unless asked for no difference.
static union grug_value TTC_screenshot(ARGS) { UNUSED; stubbed = true; return id(new_obj(K_SHOT)); }
REGISTER("TestTickContext", "screenshot", TTC_screenshot);
static union grug_value Shot_self(ARGS) { UNUSED; stubbed = true; return a[0]; }
REGISTER("Screenshot", "capture", Shot_self);
REGISTER("Screenshot", "tolerance", Shot_self);
static union grug_value Shot_equals(ARGS) { UNUSED; stubbed = true; return none(); }
REGISTER("Screenshot", "equals", Shot_equals);
static union grug_value Shot_change_from(ARGS) { UNUSED; stubbed = true; return num(0); }
REGISTER("Screenshot", "change_from", Shot_change_from);
static union grug_value Shot_differs_from(ARGS) {
    UNUSED;
    stubbed = true;
    if (a[2]._number > 0) return fail("Screenshot.differs_from: the smoketest draws nothing, so two captures never differ");
    return none();
}
REGISTER("Screenshot", "differs_from", Shot_differs_from);
// The smoketest asks every mod's overlay and routes a press, key and wheel through it, and it asks
// the world exports and draws a frame after every test tick, so a screen overlay test and a world
// overlay test both run for real here. It paints nothing, so a test that waits for a capture is
// STUBBED, never PASS.
static union grug_value Test_world_overlay_supported(ARGS) { UNUSED; return boolean(true); }
REGISTER("Test", "world_overlay_supported", Test_world_overlay_supported);
static union grug_value Test_overlay_supported(ARGS) { UNUSED; return boolean(true); }
REGISTER("Test", "overlay_supported", Test_overlay_supported);
// The forced-normalization hook is GL state for a capture. The smoketest draws nothing, so it answers
// what a loader whose item draw inherits the state reports and marks the run STUBBED (#1137).
static union grug_value TTC_normalization_forced(ARGS) { UNUSED; stubbed = true; return boolean(true); }
REGISTER("TestTickContext", "normalization_forced", TTC_normalization_forced);
// The smoketest has no renderer: it draws an overlay's geometry into no framebuffer, and a tick's
// world work is finished when the tick returns, so no frame is ever left with work to read. The
// answer is therefore an immediate yes, and only a capture is what this host cannot answer
// (#1287); TestTickContext.screenshot is what says so.
static union grug_value TTC_world_settled(ARGS) { UNUSED; return boolean(true); }
REGISTER("TestTickContext", "world_settled", TTC_world_settled);
// The client's copy of the world's block light, which a toy world with one copy always matches (a loader whose
// client and server share one world answers true the same way), so a pass is STUBBED. The position check is
// HostFunctions.lightAppliedProblem's, with Double.toString's text (selftest.c checks double_text).
static union grug_value TTC_light_applied(ARGS) {
    UNUSED;
    for (int i = 1; i <= 3; i++) {
        if (isfinite(a[i]._number)) continue;
        char x[40], y[40], z[40];
        double_text(a[1]._number, x, sizeof x); double_text(a[2]._number, y, sizeof y); double_text(a[3]._number, z, sizeof z);
        fail("TestTickContext.light_applied: The position has to be finite, and was (%s, %s, %s).", x, y, z);
        return boolean(false);
    }
    stubbed = true;
    return boolean(true);
}
REGISTER("TestTickContext", "light_applied", TTC_light_applied);
// The 0 to 10000 ms limit is asserted by expect_stall_negative-Test and expect_stall_too_long-Test;
// nothing blocks, so a pass is STUBBED. TestInitContext.stall is the same call from init() (its only
// committed caller is runner_first_tick-Test, and the smoketest has no queued ticks to replay), so it
// shares this implementation.
static union grug_value TTC_stall(ARGS) {
    UNUSED;
    stubbed = true;
    if (!(a[1]._number >= 0 && a[1]._number <= 10000)) return fail("The time has to be from 0 to 10000 milliseconds");
    return none();
}
REGISTER("TestTickContext", "stall", TTC_stall);
REGISTER("TestInitContext", "stall", TTC_stall);
// Closing a screen with an empty cursor is only a close. The game also hands a stack the cursor
// still carries back to the player's inventory, which this host does not model, so a close with a
// carried stack marks the run STUBBED.
static union grug_value TTC_close_screen(ARGS) {
    UNUSED;
    if (carried_has) stubbed = true;  // the carried stack is not handed back to the player
    screen_is_open = false;
    carried_has = false;
    return none();
}
REGISTER("TestTickContext", "close_screen", TTC_close_screen);

// =============================================================================================
// SECTION: Overlays
// =============================================================================================
// Overlay scripts (#1586, #1587): every mod's *-Overlay.grug entity, asked for one frame per runner
// tick while a container screen is open, in the clean-name order GrugOverlayScripts keeps. The
// smoketest paints nothing, so an
// overlay's draw_item, draw_text and draw_box only record that the mod asked to draw: that request
// is the overlay's own logic, which a test may read through overlay_drawn, while TestTickContext
// .screenshot is what marks the run STUBBED because a capture is what this host cannot answer.
static char current_test_mod[128];
static bool overlay_drawn;
static bool overlay_first_frame_recorded, overlay_first_frame_cleared;
static double overlay_screen_serial;
static double overlay_mouse_x, overlay_mouse_y;
static bool overlay_pass_drew;
static bool overlay_key_held;
static double overlay_key_held_code;
static uint64_t overlay_render_fn = UINT64_MAX;

// The mod that owns a test is the directory above its tests/ directory: jei/tests/x-Test.grug
// belongs to jei, which is what the loader names when it asks whose overlay has drawn (#1099). The
// paths run from the mods directory, so the mod is their first segment.
static void note_test_mod(const char* label) {
    size_t n = strcspn(label, "/");
    if (n >= sizeof current_test_mod) n = sizeof current_test_mod - 1;  // SMOKETEST_EXCL_BRANCH: a committed test path never fills the mod name
    memcpy(current_test_mod, label, n);
    current_test_mod[n] = 0;
}

static void clear_overlays(void) {
    for (size_t i = 0; i < noverlays; i++)
        if (overlays[i].entity) grug_deinit_entity(g_state, overlays[i].entity);  // SMOKETEST_EXCL_BRANCH: every committed overlay file creates its entity
    noverlays = 0;
    overlay_drawn = false;
    overlay_first_frame_recorded = false;
    overlay_first_frame_cleared = false;
}

// One overlay entity per compiled Overlay file, fresh for each test so its member state starts as
// the load leaves it. Sorted by the file's clean entity name, which is the order GrugOverlayScripts
// asks the scripts in: the coverage mod's "coverage_overlay" runs before "jei", and a key the
// coverage one takes never reaches JEI, whatever the two mods are called.
static void make_overlays(void) {
    for (size_t i = 0; i < G.f.len; i++) {
        struct grug_file_info* fi = &G.f.ptr[i];
        if (fi->file_id == UINT64_MAX || !fi->entity_type || strcmp(fi->entity_type, "Overlay")) continue;  // SMOKETEST_EXCL_BRANCH: a compiled file always has an id and a type, so only the type can skip it
        if (noverlays == capoverlays) {  // SMOKETEST_EXCL_BRANCH: the four committed overlays fit the first allocation
            capoverlays = capoverlays ? capoverlays * 2 : 8;  // SMOKETEST_EXCL_BRANCH: no more than eight overlays are compiled at once, so the doubling never runs
            overlays = xrealloc(overlays, capoverlays * sizeof *overlays);
        }
        overlays[noverlays] = (struct overlay){grug_create_entity(g_state, fi->file_id), fi->entity_name, fi->mod_name, fi->path};
        noverlays++;
    }
    for (size_t i = 1; i < noverlays; i++)
        for (size_t j = i; j > 0 && strcmp(overlays[j].name, overlays[j - 1].name) < 0; j--) {
            struct overlay t = overlays[j];
            overlays[j] = overlays[j - 1];
            overlays[j - 1] = t;
        }
}

// Runs one overlay export with its pass's context. A grug mod err is contained, as the game's JNI
// layer contains it, so an overlay whose script hits an entry the host lacks draws nothing instead
// of failing the running test; the run is STUBBED, because that export did not finish.
static void call_overlay_export(void* entity, uint64_t fn, const union grug_value* args, size_t n) {
    if (fn == UINT64_MAX) return;  // SMOKETEST_EXCL_BRANCH: every committed overlay declares render, click, key and scroll
    char saved[sizeof last_error], saved_msg[sizeof last_msg];
    memcpy(saved, last_error, sizeof saved);
    memcpy(saved_msg, last_msg, sizeof saved_msg);
    last_error[0] = 0;
    if (!grug_call_export_fn(g_state, entity, fn, args, n) && last_error[0]) stubbed = true;  // SMOKETEST_EXCL_BRANCH: no committed overlay's export reports an error
    memcpy(last_error, saved, sizeof saved);
    memcpy(last_msg, saved_msg, sizeof saved_msg);
}

// Whether the file's own state leaves the overlay unable to draw: a swapped-in text that exports
// nothing, or a deleted file, removes the overlay the game had until a load brings it back. An
// invalid edit leaves the overlay that was already drawing in place, as the engine keeps it.
static bool overlay_disabled(const char* path) {
    struct modfile* m = modfile_of(path, false);
    return m && (!strcmp(m->state, "swapped") || !strcmp(m->state, "deleted"));  // SMOKETEST_EXCL_BRANCH: an overlay's path is always in the mod table
}

// One frame of every overlay on the open screen, the way the loader's ScreenEvent.Render.Post asks
// them. The first frame of a screen records whether the drawn mark was already clear when it began,
// which is what overlay_first_frame_cleared answers (#1391).
static void render_overlays(void) {
    if (!screen_is_open) return;
    if (!overlay_first_frame_recorded) {
        overlay_first_frame_recorded = true;
        overlay_first_frame_cleared = !overlay_drawn;
    }
    for (size_t i = 0; i < noverlays; i++) {
        if (overlay_disabled(overlays[i].path)) continue;
        overlay_pass_drew = false;
        uint64_t context = new_obj(K_OVERLAY_RENDER);
        union grug_value arg = id(context);
        call_overlay_export(overlays[i].entity, overlay_render_fn, &arg, 1);
        if (overlay_pass_drew && !strcmp(overlays[i].mod, current_test_mod)) overlay_drawn = true;
    }
}

// A new screen forgets the drawn mark and starts a new first-frame record (#863). The loader's
// screen init also forgets the key that was down, so the first press on the new screen is asked.
static void note_screen_opened(void) {
    overlay_screen_serial += 1;
    overlay_drawn = false;
    overlay_first_frame_recorded = false;
    overlay_first_frame_cleared = false;
    overlay_key_held = false;
}
static union grug_value TTC_open_inventory(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.open_inventory: not a Player");  // SMOKETEST_EXCL_BRANCH: no committed test opens the inventory for anything but the player
    screen_is_open = true;
    screen_gui = 0;
    screen_be = 0;
    carried_has = false;
    note_screen_opened();
    return none();
}
REGISTER("TestTickContext", "open_inventory", TTC_open_inventory);
static union grug_value TTC_overlay_drawn(ARGS) { UNUSED; return boolean(overlay_drawn); }
REGISTER("TestTickContext", "overlay_drawn", TTC_overlay_drawn);
static union grug_value TTC_overlay_first_frame_cleared(ARGS) { UNUSED; return boolean(overlay_first_frame_cleared); }
REGISTER("TestTickContext", "overlay_first_frame_cleared", TTC_overlay_first_frame_cleared);
// The point a test parks the cursor at so a capture shows a hover. The smoketest has no window and
// paints nothing, so the point changes no answer here; a test that asks is a capture test, which
// marks the run STUBBED at TestTickContext.screenshot.
static union grug_value TTC_set_mouse_position(ARGS) { UNUSED; return none(); }
REGISTER("TestTickContext", "set_mouse_position", TTC_set_mouse_position);

// OverlayRenderContext: the open screen's geometry and mouse, and the screen serial, which changes
// when a different screen is drawn on (#865). A draw records that this mod produced a frame, which
// is the overlay's own logic; the host paints nothing, and TestTickContext.screenshot is what
// marks the run STUBBED because a capture is what it cannot answer.
static union grug_value option_of_carried(void) {
    uint64_t o = new_obj(K_OPTION);
    if (carried_has) { objs[o - 1].has = true; objs[o - 1].val = id(carried_item); }
    return id(o);
}

static bool render_context(uint64_t handle) {
    return get_obj(handle, K_OVERLAY_RENDER) != NULL;
}
static union grug_value ORC_screen_width(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.screen_width: not a render pass"); return num(gui_geometry(4)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "screen_width", ORC_screen_width);
static union grug_value ORC_screen_height(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.screen_height: not a render pass"); return num(screen_is_open ? WINDOW_H : 0); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "screen_height", ORC_screen_height);
static union grug_value ORC_gui_left(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.gui_left: not a render pass"); return num(gui_geometry(0)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "gui_left", ORC_gui_left);
static union grug_value ORC_gui_top(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.gui_top: not a render pass"); return num(gui_geometry(1)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "gui_top", ORC_gui_top);
static union grug_value ORC_gui_width(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.gui_width: not a render pass"); return num(gui_geometry(2)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "gui_width", ORC_gui_width);
static union grug_value ORC_gui_height(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.gui_height: not a render pass"); return num(gui_geometry(3)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "gui_height", ORC_gui_height);
static union grug_value ORC_mouse_x(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.mouse_x: not a render pass"); return num(overlay_mouse_x); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "mouse_x", ORC_mouse_x);
static union grug_value ORC_mouse_y(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.mouse_y: not a render pass"); return num(overlay_mouse_y); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "mouse_y", ORC_mouse_y);
static union grug_value ORC_screen_serial(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.screen_serial: not a render pass"); return num(overlay_screen_serial); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "screen_serial", ORC_screen_serial);
static union grug_value ORC_draw_item(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.draw_item: not a render pass"); overlay_pass_drew = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "draw_item", ORC_draw_item);
static union grug_value ORC_draw_text(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.draw_text: not a render pass"); overlay_pass_drew = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "draw_text", ORC_draw_text);
static union grug_value ORC_draw_box(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.draw_box: not a render pass"); overlay_pass_drew = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "draw_box", ORC_draw_box);
static union grug_value ORC_draw_tooltip(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.draw_tooltip: not a render pass"); overlay_pass_drew = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "draw_tooltip", ORC_draw_tooltip);
static union grug_value ORC_carried_item(ARGS) { UNUSED; if (!render_context(a[0]._id)) return fail("OverlayRenderContext.carried_item: not a render pass"); return option_of_carried(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayRenderContext", "carried_item", ORC_carried_item);

// =============================================================================================
// SECTION: Overlay input
// =============================================================================================
// A press, key or wheel an overlay sees (#615, #866, #986), asked in the same mod order as the
// render pass and only while a screen is open. Asking stops at the first overlay that takes it, so
// two overlays over the same pixel cannot both act on one press. The key hold is the loader's: a
// repeat of a key that is still down is not a new press, and a release lets the key be pressed
// again.
static uint64_t overlay_click_fn = UINT64_MAX, overlay_key_fn = UINT64_MAX, overlay_scroll_fn = UINT64_MAX;
static bool overlay_pass_taken;
static double overlay_click_x, overlay_click_y, overlay_click_button;
static double overlay_key_code;
static double overlay_key_character;
static bool overlay_key_ctrl;
static bool overlay_key_shift;
static bool overlay_key_repeat;
static double overlay_scroll_x, overlay_scroll_y, overlay_scroll_delta;

static bool click_context(uint64_t handle) { return get_obj(handle, K_OVERLAY_CLICK) != NULL; }
static union grug_value OCC_button(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.button: not a click pass"); return num(overlay_click_button); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "button", OCC_button);
static union grug_value OCC_shift(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.shift: not a click pass"); return boolean(false); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "shift", OCC_shift);
static union grug_value OCC_mouse_x(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.mouse_x: not a click pass"); return num(overlay_click_x); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "mouse_x", OCC_mouse_x);
static union grug_value OCC_mouse_y(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.mouse_y: not a click pass"); return num(overlay_click_y); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "mouse_y", OCC_mouse_y);
static union grug_value OCC_screen_width(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.screen_width: not a click pass"); return num(gui_geometry(4)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "screen_width", OCC_screen_width);
static union grug_value OCC_screen_height(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.screen_height: not a click pass"); return num(screen_is_open ? WINDOW_H : 0); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "screen_height", OCC_screen_height);
static union grug_value OCC_gui_left(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.gui_left: not a click pass"); return num(gui_geometry(0)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "gui_left", OCC_gui_left);
static union grug_value OCC_gui_top(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.gui_top: not a click pass"); return num(gui_geometry(1)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "gui_top", OCC_gui_top);
static union grug_value OCC_gui_width(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.gui_width: not a click pass"); return num(gui_geometry(2)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "gui_width", OCC_gui_width);
static union grug_value OCC_gui_height(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.gui_height: not a click pass"); return num(gui_geometry(3)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "gui_height", OCC_gui_height);
static union grug_value OCC_carried_item(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.carried_item: not a click pass"); return option_of_carried(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "carried_item", OCC_carried_item);
static union grug_value OCC_delete_carried_item(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.delete_carried_item: not a click pass"); carried_has = false; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "delete_carried_item", OCC_delete_carried_item);
// The only committed test that presses the hotbar while the cursor carries an item is
// click_screen_release-Test, which asks for the 1.20.6 loader and returns on any other name, so the
// smoketest cannot reach this entry through it.
static union grug_value OCC_close_screen(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.close_screen: not a click pass"); screen_is_open = false; carried_has = false; return none(); }  // SMOKETEST_EXCL: only click_screen_release-Test presses the hotbar while carrying, and it runs on 1.20.6-forge alone
REGISTER("OverlayClickContext", "close_screen", OCC_close_screen);
static union grug_value OCC_take(ARGS) { UNUSED; if (!click_context(a[0]._id)) return fail("OverlayClickContext.take: not a click pass"); overlay_pass_taken = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayClickContext", "take", OCC_take);

static bool key_context(uint64_t handle) { return get_obj(handle, K_OVERLAY_KEY) != NULL; }
static union grug_value OKC_key(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.key: not a key pass"); return num(overlay_key_code); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "key", OKC_key);
static union grug_value OKC_character(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.character: not a key pass"); return num(overlay_key_character); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "character", OKC_character);
static union grug_value OKC_repeat(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.repeat: not a key pass"); return boolean(overlay_key_repeat); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "repeat", OKC_repeat);
static union grug_value OKC_ctrl(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.ctrl: not a key pass"); return boolean(overlay_key_ctrl); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "ctrl", OKC_ctrl);
static union grug_value OKC_shift(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.shift: not a key pass"); return boolean(overlay_key_shift); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "shift", OKC_shift);
static union grug_value OKC_take(ARGS) { UNUSED; if (!key_context(a[0]._id)) return fail("OverlayKeyContext.take: not a key pass"); overlay_pass_taken = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayKeyContext", "take", OKC_take);

static bool scroll_context(uint64_t handle) { return get_obj(handle, K_OVERLAY_SCROLL) != NULL; }
static union grug_value OSC_delta(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.delta: not a scroll pass"); return num(overlay_scroll_delta); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "delta", OSC_delta);
static union grug_value OSC_mouse_x(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.mouse_x: not a scroll pass"); return num(overlay_scroll_x); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "mouse_x", OSC_mouse_x);
static union grug_value OSC_mouse_y(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.mouse_y: not a scroll pass"); return num(overlay_scroll_y); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "mouse_y", OSC_mouse_y);
static union grug_value OSC_screen_width(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.screen_width: not a scroll pass"); return num(gui_geometry(4)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "screen_width", OSC_screen_width);
static union grug_value OSC_screen_height(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.screen_height: not a scroll pass"); return num(screen_is_open ? WINDOW_H : 0); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "screen_height", OSC_screen_height);
static union grug_value OSC_gui_left(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.gui_left: not a scroll pass"); return num(gui_geometry(0)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "gui_left", OSC_gui_left);
static union grug_value OSC_gui_top(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.gui_top: not a scroll pass"); return num(gui_geometry(1)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "gui_top", OSC_gui_top);
static union grug_value OSC_gui_width(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.gui_width: not a scroll pass"); return num(gui_geometry(2)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "gui_width", OSC_gui_width);
static union grug_value OSC_gui_height(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.gui_height: not a scroll pass"); return num(gui_geometry(3)); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "gui_height", OSC_gui_height);
static union grug_value OSC_take(ARGS) { UNUSED; if (!scroll_context(a[0]._id)) return fail("OverlayScrollContext.take: not a scroll pass"); overlay_pass_taken = true; return none(); }  // SMOKETEST_EXCL_BRANCH: no committed test reads an overlay context outside the export that was passed it
REGISTER("OverlayScrollContext", "take", OSC_take);

// The open screen's overlays are asked for one press, in mod order, until one takes it.
static bool overlay_click_taken(double x, double y, double button) {
    if (!screen_is_open) return false;  // SMOKETEST_EXCL_BRANCH: every committed press through the overlays has a screen open
    overlay_click_x = x;
    overlay_click_y = y;
    overlay_click_button = button;
    overlay_mouse_x = x;
    overlay_mouse_y = y;
    for (size_t i = 0; i < noverlays; i++) {
        if (overlay_disabled(overlays[i].path)) continue;
        overlay_pass_taken = false;
        uint64_t c = new_obj(K_OVERLAY_CLICK);
        union grug_value arg = id(c);
        call_overlay_export(overlays[i].entity, overlay_click_fn, &arg, 1);
        if (overlay_pass_taken) return true;
    }
    return false;
}

// TestTickContext.press_screen_key: a key down through the screen's own key path, with the shift
// and release the test asks for. A letter, a digit, space, backspace and a key with no character of
// its own reach an overlay as that key, and any other printable number reaches it as the character
// it types, as the loader routes them; shift types the character a US keyboard puts on the key, so
// 77 types M and 49 types !; a press of a key still held is its repeat, so a toggle flips once per
// press and a text field types its character again. The four loaders before 1.20.6 ask LWJGL's
// keyboard for the name GrugOverlay.keyNameForCode gives a character code and have no key for any
// other, and a number from 128 up that is not one of the API's named keys presses nothing on any
// loader, which is the host error below. The smoketest has no loader's keyboard, so it answers as
// the older ones do, the branch its own name takes in a test (sound-Test branches on loader_name
// the same way). It fuses the key pass and the character pass into one call, which a field that
// types on both would double-type on 1.20.6 without failing here: only the loader's own run can
// catch that.

// The characters a US keyboard gives an unshifted key, in GrugOverlay.UNSHIFTED_CHARACTERS order,
// and what the same key gives while shift is held.
static const char *const unshifted_characters = "`1234567890-=[]\\;',./";
static const char *const shifted_characters = "~!@#$%^&*()_+{}|:\"<>?";

// The character a key number types, as GrugOverlay.typedCharacterFor gives it: a letter types lower
// case, or its upper case letter with shift, a digit or a punctuation key types itself, or the
// symbol above it with shift, the space bar types a space either way, and a key with no character of
// its own, or a control character, types none.
static double typed_character(int key, bool shift) {
    if (key >= 'A' && key <= 'Z') return shift ? key : key + 32;
    if (key < 32 || key > 126) return 0;
    if (shift) {
        const char *at = strchr(unshifted_characters, (char) key);
        if (at != NULL) return (double) shifted_characters[at - unshifted_characters];
    }
    return key;
}

static union grug_value TTC_press_screen_key(ARGS) {
    UNUSED;
    double k = a[1]._number;
    // SMOKETEST_EXCL_START: no committed test presses a key that is not a whole number from 0 up
    if (!isfinite(k) || k != floor(k) || k < 0) {
        char t[400];
        double_text(k, t, sizeof t);
        return fail("TestTickContext.press_screen_key: The key has to be a whole number from 0 up, and was %s.", t);
    }
    // SMOKETEST_EXCL_STOP
    if (!screen_is_open) return fail("TestTickContext.press_screen_key: There is no screen open to press.");  // SMOKETEST_EXCL: every committed press has a screen open
    int key = (int)k;
    bool ctrl = a[2]._bool, shift = a[3]._bool, release = a[4]._bool;
    // The keys a loader routes to an overlay are backspace, space, a digit, a letter and the keys
    // with no character of their own. Each range is one unsigned comparison, so every arm below is
    // a key a test can press. Any other printable number is no key, and a press of it types the
    // character it names, or presses nothing at all when control is held.
    bool digit = (unsigned) (key - '0') <= 9;
    bool letter = (unsigned) (key - 'A') <= 25;
    bool named = key == 256 || key == 257 || (key >= 260 && key <= 269);
    bool has_key = key == 8 || key == 32 || digit || letter || named;
    bool types = !ctrl && key >= 32 && key <= 126;
    if (!has_key && !types)
        return fail("TestTickContext.press_screen_key: The game has no key for character code %d.", key);
    // A press of the key that is already down is the game's repeat of it, which every loader asks
    // again with repeat() true, and a press of another key is a new press even while one is held.
    bool repeat = overlay_key_held && overlay_key_held_code == key;
    overlay_key_held = true;
    overlay_key_held_code = key;
    overlay_key_code = has_key ? key : 0;
    // The character that number produces, with the shift the test asked for, as a real press types
    // it, and none for a chord or for a key with no character of its own.
    overlay_key_character = ctrl ? 0 : typed_character(key, shift);
    overlay_key_ctrl = ctrl;
    overlay_key_shift = shift;
    overlay_key_repeat = repeat;
    bool taken = false;
    for (size_t i = 0; i < noverlays; i++) {
        if (overlay_disabled(overlays[i].path)) continue;
        overlay_pass_taken = false;
        uint64_t c = new_obj(K_OVERLAY_KEY);
        union grug_value arg = id(c);
        call_overlay_export(overlays[i].entity, overlay_key_fn, &arg, 1);
        if (overlay_pass_taken) { taken = true; break; }
    }
    // A press with release lets go of whatever was held, as the loader sets its held key to none.
    if (release) overlay_key_held = false;
    return boolean(taken);
}
REGISTER("TestTickContext", "press_screen_key", TTC_press_screen_key);

// TestTickContext.scroll_screen: one wheel movement through the screen's own wheel path. The answer
// is whether an overlay took it; the screen's own handling is never an overlay's take.
static union grug_value TTC_scroll_screen(ARGS) {
    UNUSED;
    for (int i = 1; i <= 2; i++)
        if (!isfinite(a[i]._number)) return fail("TestTickContext.scroll_screen: The position has to be finite.");  // SMOKETEST_EXCL: no committed test passes a bad position
    if (!isfinite(a[3]._number)) return fail("TestTickContext.scroll_screen: The delta has to be finite.");  // SMOKETEST_EXCL: no committed test passes a bad delta
    if (!screen_is_open) return fail("TestTickContext.scroll_screen: There is no screen open to scroll.");  // SMOKETEST_EXCL: every committed wheel has a screen open
    overlay_scroll_x = a[1]._number;
    overlay_scroll_y = a[2]._number;
    overlay_scroll_delta = a[3]._number;
    overlay_mouse_x = overlay_scroll_x;
    overlay_mouse_y = overlay_scroll_y;
    for (size_t i = 0; i < noverlays; i++) {
        if (overlay_disabled(overlays[i].path)) continue;
        overlay_pass_taken = false;
        uint64_t c = new_obj(K_OVERLAY_SCROLL);
        union grug_value arg = id(c);
        call_overlay_export(overlays[i].entity, overlay_scroll_fn, &arg, 1);
        if (overlay_pass_taken) return boolean(true);
    }
    return boolean(false);
}
REGISTER("TestTickContext", "scroll_screen", TTC_scroll_screen);
// =============================================================================================
// SECTION: Mod files
// =============================================================================================
// The one directory a mod may write (#1757). The writable copy is this host's own temp game
// directory, so a run never writes into the repository's mods; the shipped tree is the repository's
// mods/<mod>/files, read only, exactly as a development run sees it. A path is checked segment by
// segment as a portable file name (a leading "./" is accepted, since appending a listing's own name
// to "." produces one), a missing file is an empty Option, and a write is a temporary file
// under grug_mod_files/.tmp, outside every mod's files directory, renamed into place, so nothing a
// mod lists or counts ever sees a temporary file and a crash leaves one where no call reads it.
// A grug script has no call that creates a symbolic link, so the escape through one is covered by
// GrugModDataTest.aResolvedPathStaysInsideItsOwnDirectory and not here. A read does not check that
// the bytes are UTF-8: a grug string is UTF-8 by construction and no committed fixture is not.
static char* g_game_dir;  // the temp game directory, made in main and removed with everything under it
static const long mod_file_cap = 16L * 1024 * 1024;
// GrugModData.BLOCK: the charge of one entry that holds less than a filesystem block, and of every
// directory, so the cap counts entries as well as bytes and a mod cannot fill the disk with tiny
// files while its byte count stays small.
static const long mod_file_block = 4096;
// Defined below beside case_clash: every File call refuses a path that differs only in case from an
// entry it would answer from, not only a write.
static const char* case_clash_in_path(const char* mod, const char* path);

// The mod a resource names, from the <mod>/files the compiler rewrote it into, or NULL after fail().
static const char* mod_of(const char* resource) {
    const char* slash = strrchr(resource, '/');
    // SMOKETEST_EXCL_START: grug-rs rewrites every resource literal to <mod>/files, so what this host reads always has a slash before its last segment
    if (!slash || slash == resource || !slash[1]) {
        fail("File.open: the resource %s does not name <mod>/files, so it names no mod: pass r\"files\"", resource);
        return NULL;
    }
    // SMOKETEST_EXCL_STOP
    const char* leaf = slash + 1;
    if (strcmp(leaf, "files")) {
        fail("File.open: the resource %s must name the mod's files directory, such as r\"files\", not %s", resource, leaf);
        return NULL;
    }
    for (const char* c = resource; c < slash; c++)
        if (*c == '/' || *c == '\\' || *c == ':') {  // SMOKETEST_EXCL_BRANCH: the mod name the compiler prefixed holds no backslash or colon, so only the '/' arm is reachable
            fail("File.open: the resource %s names no mod directory", resource);
            return NULL;
        }
    size_t n = (size_t)(slash - resource);
    char* mod = xmalloc(n + 1);
    memcpy(mod, resource, n);
    mod[n] = 0;
    return keep(mod);
}
static union grug_value File_open(ARGS) {
    UNUSED;
    uint64_t v = new_obj(K_FILE);
    objs[v - 1].str = mod_of(a[0]._string);  // a static method has no receiver, so its first argument is a[0]
    return id(v);
}
REGISTER("File", "open", File_open);

// The mod a File method runs for, or NULL after fail(). An error in File.open ends the test at that
// call, so no committed test reaches a call on the handle it made.
static const char* file_mod(const union grug_value a[], const char* who) {
    struct obj* o = get_obj(a[0]._id, K_FILE);
    // SMOKETEST_EXCL_START: grug's types keep a File method on a File, and an error in File.open ends the test at that call, so no committed test reaches either line
    if (!o) {
        fail("%s: not a File handle", who);
        return NULL;
    }
    if (!o->str) {
        fail("%s: the handle names no mod, because File.open reported why", who);
        return NULL;
    }
    // SMOKETEST_EXCL_STOP
    return o->str;
}

// The characters Windows refuses in a file name, besides a control character and a slash.
static const char* const illegal_name_chars = ":*?\"<>|\\";
// The names Windows keeps for devices, with or without an extension and in any case.
static const char* const reserved_names[] = {
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
};
// Whether a segment holds a control character or one of the characters Windows refuses.
static bool segment_illegal(const char* s, size_t n) {
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c < 0x20) return true;  // SMOKETEST_EXCL_BRANCH: grug has no escape for a control character, so no test can pass one
        if (strchr(illegal_name_chars, c)) return true;
    }
    return false;
}
// Whether a segment is a device name Windows reserves, with or without an extension.
static bool segment_reserved(const char* s, size_t n) {
    size_t stem = 0;
    while (stem < n && s[stem] != '.') stem++;
    for (size_t i = 0; i < sizeof reserved_names / sizeof *reserved_names; i++) {
        size_t len = strlen(reserved_names[i]);
        if (len == stem && !strncasecmp(s, reserved_names[i], stem)) return true;
    }
    return false;
}
// Why `path` is not a name this host accepts, or NULL: GrugModData.pathProblem's rules, a path being
// a portable file name rather than one this host's filesystem happens to accept.
static const char* path_problem(const char* path, bool allow_root) {
    if (!path) return "names no file in files.";  // SMOKETEST_EXCL_BRANCH: a grug string is never null
    while (!strncmp(path, "./", 2)) path += 2;
    if (!*path) return "names no file in files.";
    if (path[0] == '/') return "is absolute, and a path is always relative to the mod's own files.";
    if (allow_root && !strcmp(path, ".")) return NULL;
    for (const char* s = path;;) {
        const char* e = strchr(s, '/');
        size_t n = e ? (size_t)(e - s) : strlen(s);
        if (n == 0 || (n == 1 && s[0] == '.') || (n == 2 && s[0] == '.' && s[1] == '.'))  // SMOKETEST_EXCL_BRANCH: a committed test covers the .. segment; an empty or . segment has no grug test
            return "has an empty or relative segment, and no path may climb out of files.";
        if (segment_illegal(s, n))
            return "holds a character Windows refuses in a name, so it is not a portable file name.";
        if (s[n - 1] == '.' || s[n - 1] == ' ')
            return "has a segment that ends in a dot or a space, which Windows strips, so it is not a portable file name.";
        if (segment_reserved(s, n))
            return "names a device name Windows keeps for itself, so it is not a portable file name.";
        if (!e) break;
        s = e + 1;
    }
    return NULL;
}

// The full path of `path` in one of the mod's two trees, or false after fail(). The path stays inside
// the tree because every segment is a name and nothing is absolute.
static bool tree_path(const char* mod, bool shipped, const char* path, bool allow_root, const char* who, char* out, size_t cap) {
    const char* problem = path_problem(path, allow_root);
    if (problem) {
        fail("%s: The path \"%s\" %s", who, path ? path : "(no path)", problem);  // SMOKETEST_EXCL_BRANCH: a grug string is never null
        return false;
    }
    char root[4096];
    int n = shipped ? snprintf(root, sizeof root, "%s/%s/files", g_mods_dir, mod)
                    : snprintf(root, sizeof root, "%s/grug_mod_files/%s/files", g_game_dir, mod);
    // SMOKETEST_EXCL_START: a temp and repository path this long cannot be built in a test
    if (n < 0 || (size_t)n >= sizeof root) {
        fail("%s: the mod's directory path is too long", who);
        return false;
    }
    // SMOKETEST_EXCL_STOP
    n = snprintf(out, cap, "%s/%s", root, path);
    // SMOKETEST_EXCL_START: a path this long cannot be built in a test
    if (n < 0 || (size_t)n >= cap) {
        fail("%s: the path \"%s\" is too long", who, path);
        return false;
    }
    // SMOKETEST_EXCL_STOP
    return true;
}

enum { TREE_MISSING, TREE_FILE, TREE_NOT_FILE, TREE_FAILED };
static int read_tree(const char* mod, bool shipped, const char* path, const char* who, const char** out) {
    char full[4096];
    if (!tree_path(mod, shipped, path, false, who, full, sizeof full)) return TREE_FAILED;
    struct stat st;
    if (stat(full, &st) != 0) return TREE_MISSING;
    if (!S_ISREG(st.st_mode)) return TREE_NOT_FILE;
    FILE* f = fopen(full, "rb");
    // SMOKETEST_EXCL_START: a regular file the host just statted in a directory it may write always opens
    if (!f) {
        fail("%s: cannot read %s", who, full);
        return TREE_FAILED;
    }
    // SMOKETEST_EXCL_STOP
    char* text = xmalloc((size_t)st.st_size + 1);
    size_t n = fread(text, 1, (size_t)st.st_size, f);
    fclose(f);
    text[n] = 0;
    *out = keep(text);
    return TREE_FILE;
}
static union grug_value some_text(const char* text) {
    uint64_t v = new_obj(K_OPTION);
    objs[v - 1].has = true;
    objs[v - 1].val = str(text);
    return id(v);
}
static union grug_value File_read_text(ARGS) {
    UNUSED;
    const char* mod = file_mod(a, "File.read_text");
    if (!mod) return none();  // SMOKETEST_EXCL_BRANCH: no committed test calls a failed File.open's handle
    const char* path = a[1]._string;
    const char* clash = case_clash_in_path(mod, path);
    if (clash)
        return fail("File.read_text: The path \"%s\" differs only in case from \"%s\", which is one file on Windows and two on this system, so it is not a portable file name.", path, clash);
    const char* text = NULL;
    int written = read_tree(mod, false, path, "File.read_text", &text);
    if (written == TREE_FILE) return some_text(text);
    if (written == TREE_FAILED) return none();  // SMOKETEST_EXCL_BRANCH: only a read the filesystem refuses, which no test makes
    if (written == TREE_NOT_FILE) return fail("File.read_text: The path \"%s\" names a directory, not a file.", path);
    int shipped = read_tree(mod, true, path, "File.read_text", &text);
    if (shipped == TREE_FILE) return some_text(text);
    if (shipped == TREE_FAILED) return none();  // SMOKETEST_EXCL_BRANCH: as above, for the shipped tree
    if (shipped == TREE_NOT_FILE) return fail("File.read_text: The path \"%s\" names a directory, not a file.", path);
    return id(new_obj(K_OPTION));
}
REGISTER("File", "read_text", File_read_text);

static union grug_value File_exists(ARGS) {
    UNUSED;
    const char* mod = file_mod(a, "File.exists");
    if (!mod) return boolean(false);  // SMOKETEST_EXCL_BRANCH: no committed test calls a failed File.open's handle
    const char* clash = case_clash_in_path(mod, a[1]._string);
    if (clash)
        return fail("File.exists: The path \"%s\" differs only in case from \"%s\", which is one file on Windows and two on this system, so it is not a portable file name.", a[1]._string, clash);
    char full[4096];
    struct stat st;
    if (!tree_path(mod, false, a[1]._string, false, "File.exists", full, sizeof full)) return boolean(false);  // SMOKETEST_EXCL_BRANCH: a refused path ends the test at the call
    if (stat(full, &st) == 0) return boolean(true);
    if (!tree_path(mod, true, a[1]._string, false, "File.exists", full, sizeof full)) return boolean(false);  // SMOKETEST_EXCL_BRANCH: as above
    return boolean(stat(full, &st) == 0);
}
REGISTER("File", "exists", File_exists);

struct names { const char** ptr; size_t len, cap; };
static void names_add(struct names* names, const char* name) {
    if (names->len == names->cap) {
        names->cap = names->cap ? names->cap * 2 : 8;
        names->ptr = xrealloc(names->ptr, names->cap * sizeof *names->ptr);
    }
    names->ptr[names->len++] = keep(xstrdup(name));
}
static int names_cmp(const void* p, const void* q) { return strcmp(*(const char* const*)p, *(const char* const*)q); }
// Adds the entries of one tree's directory, answering whether the tree has a directory there and
// whether it has anything there at all.
static bool list_tree(const char* mod, bool shipped, const char* path, const char* who, struct names* names, bool* directory, bool* something) {
    char full[4096];
    if (!tree_path(mod, shipped, path, true, who, full, sizeof full)) return false;
    DIR* d = opendir(full);
    if (!d) {
        struct stat st;
        *something = stat(full, &st) == 0;
        return true;
    }
    *directory = true;
    *something = true;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        names_add(names, e->d_name);
    }
    closedir(d);
    return true;
}
static bool list_both(const char* mod, const char* path, const char* who, struct names* names) {
    const char* clash = case_clash_in_path(mod, path);
    if (clash) {
        fail("%s: The path \"%s\" differs only in case from \"%s\", which is one file on Windows and two on this system, so it is not a portable file name.", who, path, clash);
        return false;
    }
    bool written_dir = false, written_any = false, shipped_dir = false, shipped_any = false;
    if (!list_tree(mod, false, path, who, names, &written_dir, &written_any)) return false;  // SMOKETEST_EXCL_BRANCH: a refused path ends the test before this answers, and expect_file_list_parent covers the refusal
    if (!list_tree(mod, true, path, who, names, &shipped_dir, &shipped_any)) return false;  // SMOKETEST_EXCL_BRANCH: the shipped root differs from the writable one only in its prefix, so a path the first call accepted cannot be refused here
    // The written copy decides what the name is, as GrugModData.entryNames does: a written file makes
    // the path a file whatever the shipped tree holds, and only a name nothing wrote is a shipped
    // file's to refuse.
    if (written_any ? !written_dir : (shipped_any && !shipped_dir)) {
        // A tree that is a directory already added its names, so the caller's early return after
        // this would leak the array; the names themselves are kept for the process, the array is not.
        free(names->ptr);
        names->ptr = NULL;
        names->len = names->cap = 0;
        fail("%s: The path \"%s\" names a file, not a directory.", who, path);
        return false;
    }
    if (names->len > 1) qsort(names->ptr, names->len, sizeof *names->ptr, names_cmp);
    size_t kept = 0;
    for (size_t i = 0; i < names->len; i++)
        if (kept == 0 || strcmp(names->ptr[kept - 1], names->ptr[i])) names->ptr[kept++] = names->ptr[i];
    names->len = kept;
    return true;
}
static union grug_value File_entry_count(ARGS) {
    UNUSED;
    const char* mod = file_mod(a, "File.entry_count");
    if (!mod) return num(0);  // SMOKETEST_EXCL_BRANCH: no committed test calls a failed File.open's handle
    struct names names = {0};
    if (!list_both(mod, a[1]._string, "File.entry_count", &names)) return num(0);
    size_t n = names.len;
    free(names.ptr);
    return num((double)n);
}
REGISTER("File", "entry_count", File_entry_count);
static union grug_value File_entry_name(ARGS) {
    UNUSED;
    const char* mod = file_mod(a, "File.entry_name");
    if (!mod) return str("");  // SMOKETEST_EXCL_BRANCH: no committed test calls a failed File.open's handle
    const char* path = a[1]._string;
    double index = a[2]._number;
    if (!is_whole(index) || index < 0 || index > 2147483647.0)
        return fail("File.entry_name: The index %g is not a whole number from 0 up, so it names no entry of \"%s\".", index, path);
    struct names names = {0};
    if (!list_both(mod, path, "File.entry_name", &names)) return str("");
    if ((double)names.len <= index) {
        size_t n = names.len;
        free(names.ptr);
        return fail("File.entry_name: The directory \"%s\" has no entry %g: it holds %zu.", path, index, n);
    }
    const char* name = names.ptr[(size_t)index];
    free(names.ptr);
    return str(name);
}
REGISTER("File", "entry_name", File_entry_name);

// What an entry of that size is charged against the cap: at least one filesystem block.
static long mod_file_charge(long size) { return size > mod_file_block ? size : mod_file_block; }
// The bytes every entry under `dir` is charged, which is what the cap counts: one block per file
// that holds less, a larger file's own size, and one block per directory, as GrugModData.charge
// does, so a run of tiny files costs what it costs in the game rather than nearly nothing.
static void total_bytes_in(const char* dir, long* total) {
    DIR* d = opendir(dir);
    if (!d) return;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char full[4096];
        snprintf(full, sizeof full, "%s/%s", dir, e->d_name);
        struct stat st;
        if (lstat(full, &st) != 0) continue;  // SMOKETEST_EXCL_BRANCH: an entry readdir just listed is stattable
        if (S_ISREG(st.st_mode)) *total += mod_file_charge((long)st.st_size);
        else if (S_ISDIR(st.st_mode)) {  // SMOKETEST_EXCL_BRANCH: no test leaves a link or a device in the writable directory
            *total += mod_file_block;
            total_bytes_in(full, total);
        }
    }
    closedir(d);
}
// The block charge of every directory a write of `full` will create below `root`, so the cap counts
// them before they exist: every missing component counts, not only those above the first that
// exists, because a write of a/deep/path creates and is charged all of them.
static long pending_dirs(const char* root, const char* full) {
    char parent[4096];
    int n = snprintf(parent, sizeof parent, "%s", full);
    if (n < 0 || (size_t)n >= sizeof parent) return 0;  // SMOKETEST_EXCL_BRANCH: a path this long cannot be built in a test
    char* slash = strrchr(parent, '/');
    if (!slash) return 0;  // SMOKETEST_EXCL_BRANCH: tree_path always puts the path under a root
    *slash = 0;
    size_t root_len = strlen(root);
    if (strncmp(parent, root, root_len) || (parent[root_len] && parent[root_len] != '/')) return 0;  // SMOKETEST_EXCL_BRANCH: tree_path builds the path under this root
    long pending = 0;
    for (char* p = parent + root_len; *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        struct stat st;
        if (lstat(parent, &st) != 0) pending += mod_file_block;
        *p = '/';
    }
    struct stat st;
    if (lstat(parent, &st) != 0) pending += mod_file_block;
    return pending;
}
static bool make_dirs(const char* dir) {
    char full[4096];
    int n = snprintf(full, sizeof full, "%s", dir);
    if (n < 0 || (size_t)n >= sizeof full) return false;  // SMOKETEST_EXCL_BRANCH: a path this long cannot be built in a test
    for (char* p = full + 1; *p; p++)
        if (*p == '/') {
            *p = 0;
            if (mkdir(full, 0755) != 0 && errno != EEXIST) return false;  // SMOKETEST_EXCL_BRANCH: a directory of the host's own temp tree is always creatable
            *p = '/';
        }
    return mkdir(full, 0755) == 0 || errno == EEXIST;  // SMOKETEST_EXCL_BRANCH: the only existing thing on a host-made path is a directory, which answers EEXIST
}
// The entry of `dir` that equals `name` ignoring case but not exactly, or NULL.
static const char* case_clash(const char* dir, const char* name) {
    DIR* d = opendir(dir);
    if (!d) return NULL;
    const char* clash = NULL;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        if (strcmp(e->d_name, name) && !strcasecmp(e->d_name, name)) {
            clash = keep(xstrdup(e->d_name));
            break;
        }
    }
    closedir(d);
    return clash;
}
// The existing entry that differs only in case from a component `path` names, or NULL. At each
// level the written copy decides, as GrugModData.requirePortableCase does: an entry of exactly that
// name there answers the segment, and only when it has none is a name differing only in case from
// an entry of either tree refused, because Windows is one directory where this host is two.
static const char* case_clash_in_path(const char* mod, const char* path) {
    char written[4096], shipped[4096];
    snprintf(written, sizeof written, "%s/grug_mod_files/%s/files", g_game_dir, mod);
    snprintf(shipped, sizeof shipped, "%s/%s/files", g_mods_dir, mod);
    while (!strncmp(path, "./", 2)) path += 2;
    bool written_live = true, shipped_live = true;
    for (const char* s = path;;) {
        const char* e = strchr(s, '/');
        size_t n = e ? (size_t)(e - s) : strlen(s);
        size_t wl = strlen(written), sl = strlen(shipped);
        // SMOKETEST_EXCL_START: no test builds a path long enough to fill either buffer
        if (wl + n + 2 > sizeof written || sl + n + 2 > sizeof shipped) return NULL;
        // SMOKETEST_EXCL_STOP
        char* name = xmalloc(n + 1);
        memcpy(name, s, n);
        name[n] = 0;
        char probe[4096];
        struct stat st;
        memcpy(probe, written, wl);
        probe[wl] = '/';
        memcpy(probe + wl + 1, name, n + 1);
        bool written_exact = written_live && lstat(probe, &st) == 0;
        memcpy(probe, shipped, sl);
        probe[sl] = '/';
        memcpy(probe + sl + 1, name, n + 1);
        bool shipped_exact = shipped_live && lstat(probe, &st) == 0;
        if (!written_exact) {
            const char* clash = written_live ? case_clash(written, name) : NULL;
            if (!clash && shipped_live) clash = case_clash(shipped, name);
            if (clash) {
                free(name);
                return clash;
            }
        }
        snprintf(written + wl, sizeof written - wl, "/%s", name);
        snprintf(shipped + sl, sizeof shipped - sl, "/%s", name);
        written_live = written_exact;
        shipped_live = shipped_exact;
        free(name);
        if (!e) break;
        s = e + 1;
    }
    return NULL;
}
static union grug_value File_write_text(ARGS) {
    UNUSED;
    const char* mod = file_mod(a, "File.write_text");
    if (!mod) return none();  // SMOKETEST_EXCL_BRANCH: no committed test calls a failed File.open's handle
    const char* path = a[1]._string, *text = a[2]._string;
    char full[4096];
    if (!tree_path(mod, false, path, false, "File.write_text", full, sizeof full)) return none();  // SMOKETEST_EXCL_BRANCH: a refused path ends the test at the call
    const char* clash = case_clash_in_path(mod, path);
    if (clash)
        return fail("File.write_text: The path \"%s\" differs only in case from \"%s\", which is one file on Windows and two on this system, so it is not a portable file name.", path, clash);
    struct stat st;
    long previous = lstat(full, &st) == 0 && S_ISREG(st.st_mode) ? mod_file_charge((long)st.st_size) : 0;  // SMOKETEST_EXCL_BRANCH: the only other existing kind a write names is a directory, whose refusal the rename reports with a platform message no cross-loader test can assert
    char root[4096];
    snprintf(root, sizeof root, "%s/grug_mod_files/%s/files", g_game_dir, mod);
    long total = 0, after;
    total_bytes_in(root, &total);
    after = total - previous + mod_file_charge((long)strlen(text)) + pending_dirs(root, full);
    if (after > mod_file_cap)
        return fail("File.write_text: Writing \"%s\" would make the mod's files directory %ld bytes, over the %ld-byte cap.", path, after, mod_file_cap);
    char parent[4096];
    snprintf(parent, sizeof parent, "%s", full);
    *strrchr(parent, '/') = 0;
    if (!make_dirs(parent)) return fail("File.write_text: cannot create %s", parent);  // SMOKETEST_EXCL_BRANCH: a directory of the host's own temp tree is always creatable
    char temp_dir[4096];
    snprintf(temp_dir, sizeof temp_dir, "%s/grug_mod_files/.tmp", g_game_dir);
    if (!make_dirs(temp_dir)) return fail("File.write_text: cannot create %s", temp_dir);  // SMOKETEST_EXCL_BRANCH: a directory of the host's own temp tree is always creatable
    char temp[4200];
    snprintf(temp, sizeof temp, "%s/.grug-write-XXXXXX", temp_dir);
    int fd = mkstemp(temp);
    if (fd < 0) return fail("File.write_text: cannot make a temporary file in %s", parent);  // SMOKETEST_EXCL_BRANCH: the host's own temp tree always accepts one
    size_t off = 0, size = strlen(text);
    while (off < size) {
        ssize_t w = write(fd, text + off, size - off);
        if (w <= 0) { close(fd); unlink(temp); return fail("File.write_text: cannot write %s", full); }  // SMOKETEST_EXCL_BRANCH: a file of the host's own temp tree always accepts its bytes
        off += (size_t)w;
    }
    if (close(fd) != 0) { unlink(temp); return fail("File.write_text: cannot close %s", full); }  // SMOKETEST_EXCL_BRANCH: closing a file of the host's own temp tree does not fail
    if (rename(temp, full) != 0) { unlink(temp); return fail("File.write_text: cannot rename %s", full); }  // SMOKETEST_EXCL_BRANCH: renaming within the host's own temp tree does not fail
    return none();
}
REGISTER("File", "write_text", File_write_text);

// The test world's name and dimension. The name is not asserted beyond being non-empty, because a
// joined client answers the address it dialled (or MpServer on the oldest loaders) and never the
// server's save name; the dimension of the test world is the overworld on every loader.
static union grug_value Level_name(ARGS) { UNUSED; return str("smoketest"); }
REGISTER("Level", "name", Level_name);
static union grug_value Level_dimension(ARGS) { UNUSED; return str("overworld"); }
REGISTER("Level", "dimension", Level_dimension);

static void remove_tree(const char* path) {
    DIR* d = opendir(path);
    // SMOKETEST_EXCL_START: the walk starts at the temp directory and recurses only into directories it just found
    if (!d) {
        unlink(path);
        return;
    }
    // SMOKETEST_EXCL_STOP
    struct dirent* e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char full[4096];
        snprintf(full, sizeof full, "%s/%s", path, e->d_name);
        struct stat st;
        if (lstat(full, &st) == 0 && S_ISDIR(st.st_mode)) remove_tree(full);  // SMOKETEST_EXCL_BRANCH: an entry readdir just listed is stattable
        else unlink(full);
    }
    closedir(d);
    rmdir(path);
}
static void remove_game_dir(void) {
    remove_tree(g_game_dir);
    free(g_game_dir);
    g_game_dir = NULL;
}

// =============================================================================================
// SECTION: Registry and contract
// =============================================================================================
// The registry: every entry of mod_api.json, and which of them have an implementation.
struct api_row { const char* cls; const char* name; host_fn stub; };
static const struct api_row api_rows[] = {API_ROWS};
#define NAPI (sizeof api_rows / sizeof api_rows[0])

#define impls __start_smoke_impls
#define NIMPL ((size_t)(__stop_smoke_impls - __start_smoke_impls))

static bool same(const char* a, const char* b) { return (a == NULL) == (b == NULL) && (!a || !strcmp(a, b)); }
static host_fn find_impl(const char* cls, const char* name) {
    for (size_t i = 0; i < NIMPL; i++)
        if (same(impls[i].cls, cls) && !strcmp(impls[i].name, name)) return impls[i].fn;
    return NULL;
// SMOKETEST_EXCL_START: the --contract mode, run by run.sh directly, which no mods/coverage test can drive
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
    size_t duplicates = 0;
    for (size_t i = 0; i < NIMPL; i++)
        for (size_t j = i + 1; j < NIMPL; j++)
            if (same(impls[i].cls, impls[j].cls) && !strcmp(impls[i].name, impls[j].name)) {
                if (!duplicates++) puts("implemented twice:");
                show(impls[i].cls, impls[i].name);
            }
    if (duplicates) orphans += duplicates;
    if (missing) {
        puts("in mod_api.json with no implementation:");
        for (size_t i = 0; i < NAPI; i++)
            if (!find_impl(api_rows[i].cls, api_rows[i].name)) show(api_rows[i].cls, api_rows[i].name);
    }
    printf("contract: %zu entries, %zu implemented, %zu missing, %zu orphans\n", NAPI, implemented, missing, orphans);
    // 2 is a defect of the smoketest itself (a typo, a removed function, a duplicate), 1 only means
    // entries are still missing, which is progress to report and never a failure of the tool.
    return orphans ? 2 : missing ? 1 : 0;
}
// SMOKETEST_EXCL_STOP

// =============================================================================================
// SECTION: Runner (run_test, --all, main)
// =============================================================================================
static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static void on_runtime_error(void* data, struct grug_runtime_error* e) {
    (void)data;
    snprintf(last_error, sizeof last_error, "%s", e && e->error_string ? e->error_string : "(no message)");
    // Matching only the message, not the source line the error string carries, so an expectation
    // cannot pass on the test's own text.
    snprintf(last_msg, sizeof last_msg, "%s", e && e->error_message ? e->error_message : last_error);
}

// The outcome of the last run_test, for the --all summary. quiet silences the per-test lines and
// the tests' own print calls.
static char fail_reason[4096];
static void report(const char* verdict, const char* label, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
static void report(const char* verdict, const char* label, const char* fmt, ...) {
    char buf[4096];
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    __builtin_va_end(ap);
    snprintf(fail_reason, sizeof fail_reason, "%s", buf);
    if (!quiet) printf("%s %s%s%s\n", verdict, label, buf[0] ? ": " : "", buf);
}

// run_test's verdicts: a pass that called a stub is STUBBED, because it may have returned early or
// compared a capture with itself and says nothing about the behaviour under test.
enum { OUT_PASS = 0, OUT_FAIL = 1, OUT_STUBBED = 2 };
static void clear_entities(void);
static void clear_mod_files(void);
static void clear_nbt(void);
static void clear_guis(void);
static void clear_world_overlays(void);
static void clear_overlays(void);
static const char* mod_of_file(uint64_t file_id) {
    for (size_t i = 0; i < G.f.len; i++)
        if (G.f.ptr[i].file_id == file_id) return G.f.ptr[i].mod_name;
    return NULL;
}
static int run_test(const char* label, uint64_t file_id, uint64_t tick_fn, uint64_t init_fn) {
    clear_bes();
    free_test_state();
    clear_world();
    clear_entities();
    clear_mod_files();
    clear_nbt();
    clear_guis();
    clear_scans();
    clear_world_overlays();
    clear_overlays();
    note_test_mod(label);
    nblockdefs = 0;
    max_ticks = 200;
    rnd_reset();
    memset(inv, 0, sizeof inv);
    time_offset = 0;
    operator_status = true;
    bg_missing[0] = 0;
    stubbed = false;
    cur_tick = 0;
    free(expected_error);
    expected_error = NULL;
    void* entity = grug_create_entity(g_state, file_id);
    if (!entity) { report("FAIL", label, "could not create the entity"); return OUT_FAIL; }
    make_overlays();
    // The test's own mod: its world overlays are entities for this test, and a configuration it
    // reads is its own. The feet start around the room, as TestTickContext.get_origin's eye is
    // three up.
    cur_test_mod = mod_of_file(file_id);
    player_x = origin_x; player_y = origin_y - 3; player_z = origin_z;
    uint64_t ctx = new_obj(K_CTX);
    union grug_value arg = id(ctx);
    int rc = 0;
    last_error[0] = 0;
    if (init_fn != UINT64_MAX) {
        uint64_t ictx = new_obj(K_CTX);
        union grug_value iarg = id(ictx);
        // A false with no error recorded is a file that has no init, which most tests do not.
        if (!grug_call_export_fn(g_state, entity, init_fn, &iarg, 1) && last_error[0]) {  // SMOKETEST_EXCL_BRANCH: no committed test's init() reports an error, so this branch is never taken
            // SMOKETEST_EXCL_START: no committed test's init() reports an error. runner_first_tick-Test was the only init that failed, on TestInitContext.stall, which the host now implements, and a test that asserted this path would itself be the failing init.
            report("FAIL", label, "init stopped: %s", last_error);
            rc = 1;
            // SMOKETEST_EXCL_STOP
        }
    }
    while (!rc) {
        last_error[0] = 0;
        last_msg[0] = 0;
        not_done_called = false;
        bool completed = grug_call_export_fn(g_state, entity, tick_fn, &arg, 1);
        if (completed && !bg_missing[0]) render_overlays();
        if (bg_missing[0]) { report("FAIL", label, "%s", bg_missing); rc = 1; }
        else if (!completed) {
            if (expected_error && strstr(last_msg, expected_error)) { report(stubbed ? "STUBBED" : "PASS", label, "%s", ""); break; }
            if (expected_error) report("FAIL", label, "expected an error containing '%s', but got: %s", expected_error, last_msg);
            else report("FAIL", label, "%s", last_error);
            rc = 1;
        } else if (not_done_called) {
            overlay_render_world();
            tick_bes();
            if (++cur_tick >= max_ticks) { report("FAIL", label, "exceeded %d ticks", max_ticks); rc = 1; }
        } else {
            if (expected_error) { report("FAIL", label, "expected an error containing '%s', but it completed without one", expected_error); rc = 1; }
            else report(stubbed ? "STUBBED" : "PASS", label, "%s", "");
            break;
        }
    }
    clear_world_overlays();
    grug_deinit_entity(g_state, entity);
    clear_overlays();
    return rc ? OUT_FAIL : stubbed ? OUT_STUBBED : OUT_PASS;
}

// SMOKETEST_EXCL_START: the --all mode, run by run.sh and CI directly, which no mods/coverage test can drive
// --all: run every test of the mods in `mods`, and print each failure with the first entry it
// needed that is missing, then the counts and a tally of the missing entries.
struct tally { char name[160]; int n; };
static int run_all(struct grug_files_slice files, uint64_t tick_fn, uint64_t init_fn) {
    static struct tally tallies[512];
    size_t ntally = 0;
    int passed = 0, stubbed_n = 0, failed = 0, missing_failures = 0;
    quiet = true;
    for (size_t i = 0; i < files.len; i++) {
        const char* path = files.ptr[i].path;
        if (!strstr(path, "/tests/") || files.ptr[i].file_id == UINT64_MAX) continue;
        int outcome = run_test(path, files.ptr[i].file_id, tick_fn, init_fn);
        if (outcome == OUT_PASS) { passed++; continue; }
        if (outcome == OUT_STUBBED) { stubbed_n++; continue; }
        failed++;
        const char* m = strstr(fail_reason, "does not implement ");
        printf("FAIL %s: %.*s\n", path, (int)strcspn(fail_reason, "\n"), fail_reason);
        if (!m) continue;
        m += strlen("does not implement ");
        missing_failures++;
        size_t k = 0;
        while (k < ntally && strncmp(tallies[k].name, m, strlen(tallies[k].name)) != 0) k++;
        if (k == ntally && ntally < 512) {
            snprintf(tallies[ntally].name, sizeof tallies[ntally].name, "%.*s", (int)strcspn(m, " \n"), m);
            tallies[ntally++].n = 0;
        }
        if (k < ntally) tallies[k].n++;
    }
    quiet = false;
    for (size_t i = 1; i < ntally; i++)
        for (size_t j = i; j > 0 && tallies[j].n > tallies[j - 1].n; j--) { struct tally t = tallies[j]; tallies[j] = tallies[j - 1]; tallies[j - 1] = t; }
    for (size_t k = 0; k < ntally; k++) printf("  needs %s: %d tests\n", tallies[k].name, tallies[k].n);
    // passed ran its asserts; stubbed passed but called a graphics or capability stub (returned early
    // on an overlay flag, or compared a capture with itself), so it proves less than a pass.
    printf("smoketest --all: %d passed, %d stubbed, %d failed (%d on a missing entry), %d total\n", passed, stubbed_n, failed, missing_failures, passed + stubbed_n + failed);
    return failed ? 1 : 0;
}

// SMOKETEST_EXCL_STOP
int main(int argc, char** argv) {
    if (argc == 2 && !strcmp(argv[1], "--contract")) return contract();
    if (argc < 3) {
        fprintf(stderr, "usage: smoketest --contract | smoketest <repo> --compile-only | smoketest <repo> <test.grug>...\n");  // SMOKETEST_EXCL: the usage message is for a run with too few arguments, which run.sh never makes
        return 2;  // SMOKETEST_EXCL: the usage exit, as on the line above
    }
    char api_path[4096], mods_path[4096];
    snprintf(api_path, sizeof api_path, "%s/mod_api.json", argv[1]);
    snprintf(mods_path, sizeof mods_path, "%s/mods", argv[1]);
    // SMOKETEST_MODS points at another mods directory, to time a subset of the files.
    if (getenv("SMOKETEST_MODS")) snprintf(mods_path, sizeof mods_path, "%s", getenv("SMOKETEST_MODS"));
    g_mods_dir = mods_path;

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
        if (!f) f = api_rows[i].stub;
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
            bad++;  // SMOKETEST_EXCL: a mod file that fails to compile, which no committed mod does
            if (bad <= 5) fprintf(stderr, "compile error in %s: %s\n", files.ptr[i].path, files.ptr[i].error.error_string);  // SMOKETEST_EXCL: the report of that compile error
        }
    }
    printf("registered %zu entries (%zu implemented), init+register %.2f ms; compiled %zu files (%zu with errors) in %.2f ms\n",
           NAPI, implemented, t1 - t0, files.len, bad, t2 - t1);

    int rc = 0;
    if (!strcmp(argv[2], "--compile-only")) { grug_deinit(g_state); return bad ? 1 : 0; }
    // The game directory a File test writes under, so a run never touches the repository's mods.
    char game_dir[] = "/tmp/grug-smoketest-XXXXXX";
    if (!mkdtemp(game_dir)) {  // SMOKETEST_EXCL_BRANCH: /tmp is writable in every run
        // SMOKETEST_EXCL_START: the report of a failure to make the temp directory, which no run reaches
        fprintf(stderr, "smoketest: cannot make a temp game directory\n");
        grug_deinit(g_state);
        return 2;
        // SMOKETEST_EXCL_STOP
    }
    g_game_dir = xstrdup(game_dir);
    G.f = files;
    toy_files = &G.f;
    build_toy_items();
    G.block_init = grug_get_on_fn_id(g_state, "Block", "init");
    block_use_fn = grug_get_on_fn_id(g_state, "Block", "use");
    block_shape_fn = grug_get_on_fn_id(g_state, "Block", "shape");
    block_display_tick_fn = grug_get_on_fn_id(g_state, "Block", "display_tick");
    G.be_init = grug_get_on_fn_id(g_state, "BlockEntity", "init");
    G.be_tick = grug_get_on_fn_id(g_state, "BlockEntity", "tick");
    be_inserted_fn = grug_get_on_fn_id(g_state, "BlockEntity", "item_inserted");
    be_output_taken_fn = grug_get_on_fn_id(g_state, "BlockEntity", "output_taken");
    be_neighbor_fn = grug_get_on_fn_id(g_state, "BlockEntity", "on_neighbor_change");
    G.be_extract_item = grug_get_on_fn_id(g_state, "BlockEntity", "extract_item");
    G.be_add_item = grug_get_on_fn_id(g_state, "BlockEntity", "add_item");
    G.be_offer_liquid = grug_get_on_fn_id(g_state, "BlockEntity", "offer_liquid");
    G.be_take_liquid = grug_get_on_fn_id(g_state, "BlockEntity", "take_liquid");
    G.be_get_liquid = grug_get_on_fn_id(g_state, "BlockEntity", "get_liquid");
    G.be_get_liquid_amount = grug_get_on_fn_id(g_state, "BlockEntity", "get_liquid_amount");
    G.be_get_liquid_capacity = grug_get_on_fn_id(g_state, "BlockEntity", "get_liquid_capacity");
    overlay_render_fn = grug_get_on_fn_id(g_state, "Overlay", "render");
    overlay_click_fn = grug_get_on_fn_id(g_state, "Overlay", "click");
    overlay_key_fn = grug_get_on_fn_id(g_state, "Overlay", "key");
    overlay_scroll_fn = grug_get_on_fn_id(g_state, "Overlay", "scroll");
    world_render_fn = grug_get_on_fn_id(g_state, "Overlay", "render_world");
    world_key_fn = grug_get_on_fn_id(g_state, "Overlay", "world_key");
    world_binding_fn = grug_get_on_fn_id(g_state, "Overlay", "world_binding");
    uint64_t tick_fn = grug_get_on_fn_id(g_state, "Test", "tick");
    uint64_t init_fn = grug_get_on_fn_id(g_state, "Test", "init");
    if (!strcmp(argv[2], "--all")) { rc = run_all(files, tick_fn, init_fn); goto done; }
    int ran = 0;
    for (int i = 2; i < argc; i++) {
        size_t found = files.len;
        for (size_t j = 0; j < files.len; j++) if (!strcmp(files.ptr[j].path, argv[i])) found = j;
        if (found == files.len || files.ptr[found].file_id == UINT64_MAX) { printf("FAIL %s: not compiled\n", argv[i]); rc = 1; continue; }
        double a = now_ms();
        rc |= run_test(argv[i], files.ptr[found].file_id, tick_fn, init_fn) == OUT_FAIL;
        printf("  %s: %.3f ms\n", argv[i], now_ms() - a);
        ran++;
    }
    printf("ran %d tests, %s\n", ran, rc ? "FAILED" : "all passed");
done:
    clear_bes();
    free_test_state();
    clear_world();
    clear_entities();
    clear_mod_files();
    clear_nbt();
    clear_guis();
    clear_overlays();
    free(bes); free(blockdefs);
    clear_scans();
    free(scans);
    clear_world_overlays();
    free(world); free(objs); free(arena); free(expected_error);
    remove_game_dir();
    grug_deinit(g_state);
    return rc;
}
