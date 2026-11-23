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
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

static bool is_whole(double d) { return isfinite(d) && d == floor(d); }

// =============================================================================================
// SECTION: TestTickContext
// =============================================================================================
// TestTickContext
static union grug_value TTC_assert(ARGS) {
    UNUSED;
    if (!a[1]._bool) return fail("%s", a[2]._string);
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
static union grug_value Vec3_new(ARGS) {
    UNUSED;
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
// Java's Double.toString: the shortest digits that read back as the same double, plain between 1e-3 and
// 1e7 and as d.dddE+x outside it. This is what a Java string concatenation of a double writes.
static void double_text(double v, char* out, size_t cap) {
    if (isnan(v)) { snprintf(out, cap, "NaN"); return; }
    if (isinf(v)) { snprintf(out, cap, v > 0 ? "Infinity" : "-Infinity"); return; }
    if (v == 0) { snprintf(out, cap, signbit(v) ? "-0.0" : "0.0"); return; }
    char e[40];
    // Java picks the closest of the two-digit texts when one digit would do (4.9E-324, not 5.0E-324),
    // so the search starts at two digits and a trailing zero is dropped afterwards.
    int p = 2;
    for (; p < 17; p++) {
        snprintf(e, sizeof e, "%.*e", p - 1, v);
        if (strtod(e, NULL) == v) break;
    }
    snprintf(e, sizeof e, "%.*e", p - 1, v);
    char digits[20]; size_t nd = 0;
    const char* c = e;
    bool neg = *c == '-';
    if (neg) c++;
    for (; *c && *c != 'e'; c++) if (*c != '.') digits[nd++] = *c;
    while (nd > 1 && digits[nd - 1] == '0') nd--;
    digits[nd] = 0;
    int e10 = atoi(c + 1);
    char* w = out;
    if (neg) *w++ = '-';
    if (fabs(v) >= 1e-3 && fabs(v) < 1e7) {
        if (e10 >= 0) {
            for (int k = 0; k <= e10; k++) *w++ = (size_t)k < nd ? digits[k] : '0';
            *w++ = '.';
            if ((size_t)e10 + 1 < nd) { memcpy(w, digits + e10 + 1, nd - (size_t)e10 - 1); w += nd - (size_t)e10 - 1; }
            else *w++ = '0';
        } else {
            *w++ = '0'; *w++ = '.';
            for (int k = 0; k < -e10 - 1; k++) *w++ = '0';
            memcpy(w, digits, nd); w += nd;
        }
        *w = 0;
    } else {
        *w++ = digits[0]; *w++ = '.';
        if (nd > 1) { memcpy(w, digits + 1, nd - 1); w += nd - 1; } else *w++ = '0';
        snprintf(w, cap - (size_t)(w - out), "E%d", e10);
    }
}
// The text of a number as the adapter writes it: a whole number as a plain integer (BigDecimal's exact
// expansion), anything else as Double.toString.
static void number_text(double v, char* out, size_t cap) {
    if (isfinite(v) && v == rint(v)) { snprintf(out, cap, v == 0 ? "0" : "%.0f", v); return; }
    double_text(v, out, cap);
}
static union grug_value String_from_number(ARGS) {
    UNUSED;
    char buf[400];
    number_text(a[0]._number, buf, sizeof buf);
    return str(keep(xstrdup(buf)));
}
REGISTER("String", "from_number", String_from_number);

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

// The player and their hand, as held_item-Test asserts them: one local player, whose hand holds
// an item or nothing. Nothing else about the player is modelled (position, inventory and the use
// of items each wait for a committed test that needs them).
static bool held_has;
static uint64_t held_item_id;
static double held_count;  // set_held_item gives one item; a stack arrives only by a give, which is not modelled
static union grug_value TTC_get_player(ARGS) { UNUSED; return id(new_obj(K_PLAYER)); }
REGISTER("TestTickContext", "get_player", TTC_get_player);
static union grug_value TTC_set_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.set_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    if (!get_obj(a[2]._id, K_ITEM)) return fail("TestTickContext.set_held_item: not an Item");  // SMOKETEST_EXCL: no committed test
    held_has = true; held_item_id = a[2]._id; held_count = 1;
    return none();
}
REGISTER("TestTickContext", "set_held_item", TTC_set_held_item);
static union grug_value TTC_clear_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.clear_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    held_has = false; held_count = 0;
    return none();
}
REGISTER("TestTickContext", "clear_held_item", TTC_clear_held_item);
// hotbar_reset_a-Test and overlay-Test: one item in the hand after set_held_item, none after clear_held_item.
static union grug_value TTC_get_held_count(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.get_held_count: not a Player");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but the player
    return num(held_has ? held_count : 0);
}
REGISTER("TestTickContext", "get_held_count", TTC_get_held_count);
static union grug_value Player_get_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[0]._id, K_PLAYER)) return fail("Player.get_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    uint64_t o = new_obj(K_OPTION);
    if (held_has) { objs[o - 1].has = true; objs[o - 1].val = id(held_item_id); }
    return id(o);
}
REGISTER("Player", "get_held_item", Player_get_held_item);

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
struct record { struct kv* n; size_t nn; uint64_t item; bool has_item; };
struct list { char* name; struct record* r; size_t nr; };
struct slot { bool has; uint64_t item; double count, damage; };
struct be {
    bool live;
    bool doomed;  // replaced or removed while one of its calls was running; freed when the call returns
    long x, y, z;
    void* entity;
    struct kv* nums; size_t nnums;
    struct list* lists; size_t nlists;
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

static void free_list(struct list* l) {
    for (size_t i = 0; i < l->nr; i++) {
        for (size_t j = 0; j < l->r[i].nn; j++) free(l->r[i].n[j].k);
        free(l->r[i].n);
    }
    free(l->r); free(l->name);
}
static void release_be(struct be* b) {
    if (b->entity) grug_deinit_entity(g_state, b->entity);
    for (size_t i = 0; i < b->nnums; i++) free(b->nums[i].k);
    free(b->nums);
    for (size_t i = 0; i < b->nlists; i++) free_list(&b->lists[i]);
    free(b->lists);
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

// Per-block registry: Block.init runs once per test on first placement, with set_block_entity and
// set_inventory_size the only BlockInitContext entries implemented. Any other setter a block calls
// fails the placement naming the entry.
struct blockdef { const char* name; long be_file; long slots; };
static struct blockdef* blockdefs;
static size_t nblockdefs;
// Block.init's own results. Block.init can place another undefined block, whose init nests inside
// this one, so find_blockdef saves and restores it and keeps no pointer into `blockdefs`.
struct binit { long be_file; long slots; const char* mod; };
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
REGISTER("BlockInitContext", "set_shape", BIC_noop);
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
        // Block.init has no entity to hold: create, run, drop.
        void* ent = grug_create_entity(g_state, fi->file_id);
        struct binit outer = cur_binit;
        cur_binit.be_file = BLOCK_NO_ENTITY;
        cur_binit.slots = 0;
        cur_binit.mod = fi->mod_name;
        union grug_value arg = id(new_obj(K_CTX));
        call_contained(ent, G.block_init, &arg, 1);
        long result = cur_binit.be_file, slots = cur_binit.slots;
        cur_binit = outer;
        blockdefs[idx].be_file = result;
        blockdefs[idx].slots = slots;
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
    else if (!objs[rid - 1].has) { *errored = true; fail("%s returned without answering its response, and refusing has to be said with response.refuse().", export_name); }
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
// Where a click on the block at (x, y, z) places the held block: the neighbour across the face the eye sees.
static void use_face(long x, long y, long z, long out[3]) {
    double dx = x + 0.5 - origin_x, dy = y + 0.5 - (origin_y - 3), dz = z + 0.5 - origin_z;
    double ax = fabs(dx), ay = fabs(dy), az = fabs(dz);
    out[0] = x; out[1] = y; out[2] = z;
    if (ay >= ax && ay >= az) out[1] += dy > 0 ? -1 : 1;
    else if (ax >= az) out[0] += dx > 0 ? -1 : 1;
    else out[2] += dz > 0 ? -1 : 1;  // SMOKETEST_EXCL: no committed test clicks a block whose largest distance from the eye is along z
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
        void* ent = grug_create_entity(g_state, fi->file_id);
        uint64_t c = new_obj(K_USE_CTX);
        union grug_value args[6] = {id(c), a[1], num(a[2]._number), num(a[3]._number), num(a[4]._number), id(new_obj(K_PLAYER))};
        bool ran = call_export(ent, block_use_fn, args, 6, NULL);
        handled = ran && !objs[c - 1].has;
        grug_deinit_entity(g_state, ent);
    }
    if (handled || !held_has) return none();
    long at[3];
    use_face(p[0], p[1], p[2], at);
    if (!strcmp(objs[held_item_id - 1].str, "minecraft:cobblestone")) {
        struct block* t = find_block(at[0], at[1], at[2]);
        if (!t || !strcmp(t->name, "minecraft:air")) {
            set_block(at[0], at[1], at[2], "minecraft:cobblestone");
            on_block_placed(at[0], at[1], at[2], "minecraft:cobblestone");
        }
    } else stubbed = true;  // SMOKETEST_EXCL: no committed test holds another vanilla item for a declined click; whether it places is the game's
    return none();
}
REGISTER("TestTickContext", "use_block", TTC_use_block);

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
static union grug_value hf_item_entity(ARGS) {
    UNUSED;
    struct obj* st = get_obj(a[4]._id, K_ITEM_STACK);
    if (!st) return fail("item_entity: not an ItemStack");  // SMOKETEST_EXCL_BRANCH: no committed test passes anything but an ItemStack
    if (nents == capents) {
        capents = capents ? capents * 2 : 64;
        ents = xrealloc(ents, capents * sizeof *ents);
    }
    struct ent* e = &ents[nents];
    memset(e, 0, sizeof *e);
    e->x = a[1]._number; e->y = a[2]._number; e->z = a[3]._number;
    e->item = st->val._id; e->damage = st->x; e->count = st->y;
    uint64_t o = new_obj(K_ITEM_ENTITY);
    objs[o - 1].x = (double)nents++;
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

// The spawned item entities within the radius of a point, the first one's index in *first.
static double items_near(const union grug_value a[], size_t* first) {
    double n = 0;
    for (size_t i = 0; i < nents; i++) {
        double dx = ents[i].x - a[1]._number, dy = ents[i].y - a[2]._number, dz = ents[i].z - a[3]._number;
        if (!ents[i].spawned || !(sqrt(dx * dx + dy * dy + dz * dz) <= a[4]._number)) continue;
        if (n == 0) *first = i;
        n++;
    }
    return n;
}
static union grug_value Level_count_item_entities(ARGS) {
    UNUSED;
    size_t first = 0;
    return num(items_near(a, &first));
}
REGISTER("Level", "count_item_entities", Level_count_item_entities);
static union grug_value Level_find_item_entity(ARGS) {
    UNUSED;
    size_t first = 0;
    double n = items_near(a, &first);
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

// Mod file states (the hot_reload*-Test files, expect_set_mod_file_state_*-Test). The real call rewrites the
// file on disk and the engine's watcher reports the change; the smoketest rewrites nothing (a test must
// never disturb the repository's mods) and counts a change per call that would have disturbed a file, which is
// all the tests' wait loops read. The reload itself is not run, so every test that calls it is STUBBED.
struct modfile { const char* path; double changes; bool disturbed; };
static struct modfile* modfiles;
static size_t nmodfiles, capmodfiles;
static void clear_mod_files(void) { nmodfiles = 0; }
static struct modfile* modfile_of(const char* path, bool create) {
    for (size_t i = 0; i < nmodfiles; i++) if (!strcmp(modfiles[i].path, path)) return &modfiles[i];
    if (!create) return NULL;
    if (nmodfiles == capmodfiles) { capmodfiles = capmodfiles ? capmodfiles * 2 : 8; modfiles = xrealloc(modfiles, capmodfiles * sizeof *modfiles); }
    modfiles[nmodfiles] = (struct modfile){keep(xstrdup(path)), 0, false};
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

// No smoketest call opens a screen (use_block, which opens a block's GUI, is not modelled), so the
// answer is always closed, which is what screen_open-Test asserts after close_screen. A test that waits
// for a screen to open stops earlier, at the use_block it needs.
static union grug_value TTC_screen_open(ARGS) { UNUSED; return boolean(false); }
REGISTER("TestTickContext", "screen_open", TTC_screen_open);

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
// A loader that draws no overlays and no world overlays is one a test returns early on, which is
// what a test that asks has to handle; the smoketest is such a loader.
static union grug_value Test_overlay_supported(ARGS) { UNUSED; stubbed = true; return boolean(false); }
REGISTER("Test", "world_overlay_supported", Test_overlay_supported);
REGISTER("Test", "overlay_supported", Test_overlay_supported);
static union grug_value TTC_world_settled(ARGS) { UNUSED; stubbed = true; return boolean(true); }
REGISTER("TestTickContext", "world_settled", TTC_world_settled);
// The 0 to 10000 ms limit is asserted by expect_stall_negative-Test and expect_stall_too_long-Test;
// nothing blocks, so a pass is STUBBED.
static union grug_value TTC_stall(ARGS) {
    UNUSED;
    stubbed = true;
    if (!(a[1]._number >= 0 && a[1]._number <= 10000)) return fail("The time has to be from 0 to 10000 milliseconds");
    return none();
}
REGISTER("TestTickContext", "stall", TTC_stall);
static union grug_value TTC_close_screen(ARGS) { UNUSED; stubbed = true; return none(); }
REGISTER("TestTickContext", "close_screen", TTC_close_screen);

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
static int run_test(const char* label, uint64_t file_id, uint64_t tick_fn, uint64_t init_fn) {
    clear_bes();
    free_test_state();
    clear_world();
    clear_entities();
    clear_mod_files();
    clear_nbt();
    nblockdefs = 0;
    max_ticks = 200;
    rnd_reset();
    held_has = false; held_item_id = 0; held_count = 0;
    time_offset = 0;
    bg_missing[0] = 0;
    stubbed = false;
    cur_tick = 0;
    free(expected_error);
    expected_error = NULL;
    void* entity = grug_create_entity(g_state, file_id);
    if (!entity) { report("FAIL", label, "could not create the entity"); return OUT_FAIL; }
    uint64_t ctx = new_obj(K_CTX);
    union grug_value arg = id(ctx);
    int rc = 0;
    last_error[0] = 0;
    if (init_fn != UINT64_MAX) {
        uint64_t ictx = new_obj(K_CTX);
        union grug_value iarg = id(ictx);
        // A false with no error recorded is a file that has no init, which most tests do not.
        if (!grug_call_export_fn(g_state, entity, init_fn, &iarg, 1) && last_error[0]) {
            report("FAIL", label, "init stopped: %s", last_error);
            rc = 1;
        }
    }
    while (!rc) {
        last_error[0] = 0;
        last_msg[0] = 0;
        not_done_called = false;
        bool completed = grug_call_export_fn(g_state, entity, tick_fn, &arg, 1);
        if (bg_missing[0]) { report("FAIL", label, "%s", bg_missing); rc = 1; }
        else if (!completed) {
            if (expected_error && strstr(last_msg, expected_error)) { report(stubbed ? "STUBBED" : "PASS", label, "%s", ""); break; }
            if (expected_error) report("FAIL", label, "expected an error containing '%s', but got: %s", expected_error, last_msg);
            else report("FAIL", label, "%s", last_error);
            rc = 1;
        } else if (not_done_called) {
            tick_bes();
            if (++cur_tick >= max_ticks) { report("FAIL", label, "exceeded %d ticks", max_ticks); rc = 1; }
        } else {
            if (expected_error) { report("FAIL", label, "expected an error containing '%s', but it completed without one", expected_error); rc = 1; }
            else report(stubbed ? "STUBBED" : "PASS", label, "%s", "");
            break;
        }
    }
    grug_deinit_entity(g_state, entity);
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
    g_mods_dir = mods_path;
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
    G.f = files;
    G.block_init = grug_get_on_fn_id(g_state, "Block", "init");
    block_use_fn = grug_get_on_fn_id(g_state, "Block", "use");
    G.be_init = grug_get_on_fn_id(g_state, "BlockEntity", "init");
    G.be_tick = grug_get_on_fn_id(g_state, "BlockEntity", "tick");
    be_inserted_fn = grug_get_on_fn_id(g_state, "BlockEntity", "item_inserted");
    be_output_taken_fn = grug_get_on_fn_id(g_state, "BlockEntity", "output_taken");
    be_neighbor_fn = grug_get_on_fn_id(g_state, "BlockEntity", "on_neighbor_change");
    G.be_extract_item = grug_get_on_fn_id(g_state, "BlockEntity", "extract_item");
    G.be_add_item = grug_get_on_fn_id(g_state, "BlockEntity", "add_item");
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
    free(bes); free(blockdefs);
    free(world); free(objs); free(arena); free(expected_error);
    grug_deinit(g_state);
    return rc;
}
