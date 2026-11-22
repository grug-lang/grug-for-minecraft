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
static void* xrealloc(void* p, size_t n) { void* q = realloc(p, n); if (!q) die_oom(); return q; }
static void* xmalloc(size_t n) { void* q = malloc(n); if (!q) die_oom(); return q; }
static char* xstrdup(const char* s) { char* q = strdup(s); if (!q) die_oom(); return q; }

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
    if (!o || !o->has) return fail("Option.unwrap: the Option is empty");
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
static union grug_value String_length(ARGS) { UNUSED; return num((double)strlen(a[0]._string)); }
REGISTER("String", "length", String_length);
static union grug_value String_concat(ARGS) {
    UNUSED;
    size_t n = strlen(a[0]._string), m = strlen(a[1]._string);
    char* s = xmalloc(n + m + 1);
    memcpy(s, a[0]._string, n);
    memcpy(s + n, a[1]._string, m + 1);
    return str(keep(s));
}
REGISTER("String", "concat", String_concat);
static union grug_value String_from_number(ARGS) {
    UNUSED;
    char buf[64];
    if (is_whole(a[0]._number) && fabs(a[0]._number) < 1e15) snprintf(buf, sizeof buf, "%.0f", a[0]._number);
    else snprintf(buf, sizeof buf, "%g", a[0]._number);
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
    default: printf("<object %llu>", (unsigned long long)v->_id);
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
static union grug_value hf_item(ARGS) {
    UNUSED;
    struct obj* rl = get_obj(a[0]._id, K_RL);
    if (!rl) return fail("item: not a ResourceLocation");
    // new_obj may move the table, so the name is read before it and rl is not used after it.
    const char* name = rl->str;
    for (size_t i = 0; i < nobjs; i++)
        if (objs[i].kind == K_ITEM && !strcmp(objs[i].str, name)) return id(i + 1);
    uint64_t it = new_obj(K_ITEM);
    objs[it - 1].str = name;
    return id(it);
}
REGISTER(NULL, "item", hf_item);

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
static union grug_value TTC_get_player(ARGS) { UNUSED; return id(new_obj(K_PLAYER)); }
REGISTER("TestTickContext", "get_player", TTC_get_player);
static union grug_value TTC_set_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.set_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    if (!get_obj(a[2]._id, K_ITEM)) return fail("TestTickContext.set_held_item: not an Item");  // SMOKETEST_EXCL: no committed test
    held_has = true; held_item_id = a[2]._id;
    return none();
}
REGISTER("TestTickContext", "set_held_item", TTC_set_held_item);
static union grug_value TTC_clear_held_item(ARGS) {
    UNUSED;
    if (!get_obj(a[1]._id, K_PLAYER)) return fail("TestTickContext.clear_held_item: not a Player");  // SMOKETEST_EXCL: no committed test
    held_has = false;
    return none();
}
REGISTER("TestTickContext", "clear_held_item", TTC_clear_held_item);
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
        snprintf(bg_missing, sizeof bg_missing, "%s", last_error);
    bool ran = ok || last_error[0];
    if (err) snprintf(err, 4096, "%s", last_msg);
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
static void make_be(long x, long y, long z, long file, long slots) {
    if (nbes == capbes) { capbes = capbes ? capbes * 2 : 32; bes = xrealloc(bes, capbes * sizeof *bes); }
    size_t idx = nbes++;  // an index, not a pointer: the member scope below can place blocks
    memset(&bes[idx], 0, sizeof bes[idx]);
    bes[idx].live = true; bes[idx].x = x; bes[idx].y = y; bes[idx].z = z;
    give_slots(&bes[idx], slots);
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
    else if (!strcmp(name, "minecraft:chest")) {
        // The one vanilla block entity the committed tests place (chest_inventory-Test and
        // inventory_size-Test assert its 27 slots, power-Test and expect_receive_energy_not_a_receiver-Test
        // that it is no receiver). It has no script, so it never ticks.
        if (nbes == capbes) { capbes = capbes ? capbes * 2 : 32; bes = xrealloc(bes, capbes * sizeof *bes); }
        struct be* b = &bes[nbes++];
        memset(b, 0, sizeof *b);
        b->live = true; b->x = x; b->y = y; b->z = z;
        give_slots(b, 27);
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
static uint64_t be_inserted_fn;
static union grug_value BE_set_item_in_slot(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_item_in_slot: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!slot_in_range(b, a[1]._number)) return fail("BlockEntity.set_item_in_slot: there is no slot %g, the inventory has %zu", a[1]._number, b->nslots);
    struct slot* sl = &b->slots[(size_t)a[1]._number];
    // The tests write whole positive counts; any other count (negative, fractional, NaN) is not
    // asserted by a test, so it is stored as given and the test is marked stubbed.
    if (!(is_whole(a[3]._number) && a[3]._number > 0)) stubbed = true;  // SMOKETEST_EXCL: no committed test
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
    if (!(is_whole(a[2]._number) && a[2]._number >= 0)) stubbed = true;  // SMOKETEST_EXCL: no committed test
    return none();
}
REGISTER("BlockEntity", "set_item_count_in_slot", BE_set_item_count_in_slot);
// How many of a slot's stack leave when `amount` are asked for, and the slot emptied when none is left.
static double slot_take(struct slot* sl, double amount) {
    double took = amount < sl->count ? amount : sl->count;
    if (took < 0) took = 0;  // SMOKETEST_EXCL: no committed test
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
    // so a script block entity, which may declare it, marks the test stubbed.
    if (b->entity) stubbed = true;
    return num(slot_take(sl, a[2]._number));
}
REGISTER("BlockEntity", "take_item_from_slot", BE_take_item_from_slot);
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
    if (b->entity) stubbed = true;  // item_extracted is not run, as in take_item_from_slot
    double left = a[3]._number, total = 0;
    for (size_t i = 0; i < b->nslots && left > 0; i++)
        if (b->slots[i].has && b->slots[i].item == a[1]._id && b->slots[i].damage == a[2]._number) {
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
    return boolean(!b->has_insertable || (a[1]._number >= b->ins_first && a[1]._number <= b->ins_last));
}
REGISTER("BlockEntity", "is_slot_insertable", BE_is_slot_insertable);
static union grug_value BE_is_slot_extractable(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.is_slot_extractable: not a live block entity");  // SMOKETEST_EXCL: no committed test
    return boolean(!b->has_extractable || (a[1]._number >= b->ext_first && a[1]._number <= b->ext_last));
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
    uint64_t rid = new_obj(kind);
    objs[rid - 1].open = true;
    union grug_value all[8];
    memcpy(all, args, nargs * sizeof *args);
    all[nargs] = id(rid);
    bool ran = call_export(b->entity, fn, all, nargs + 1, err);
    objs[rid - 1].open = false;
    if (!ran) return false;  // SMOKETEST_EXCL: no committed test asks a block entity without the export
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
static union grug_value BE_ask_extract_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.ask_extract_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return num(-1);  // SMOKETEST_EXCL: no committed test asks a vanilla block entity
    struct obj r; bool errored;
    union grug_value args[2] = {num(a[1]._number), boolean(false)};
    uint64_t self = a[0]._id;
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
    // The smoketest has no crafting result to give (update_recipe_output is missing), so a request for
    // one answers nothing and marks the test stubbed.
    if (r.z != ANS_REFUSE) stubbed = true;  // SMOKETEST_EXCL: reached once update_recipe_output exists
    return num(0);
}
REGISTER("BlockEntity", "ask_extract_item", BE_ask_extract_item);
static union grug_value BE_add_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.add_item: not a live block entity");  // SMOKETEST_EXCL: no committed test
    if (!b->entity) return num(-1);
    uint64_t self = a[0]._id;
    double count = a[3]._number, remaining = count;
    while (remaining > 0) {
        struct obj r; bool errored;
        union grug_value args[5] = {a[1], num(a[2]._number), num(remaining), num(a[4]._number), boolean(a[5]._bool)};
        if (!ask_response(be_of(self), G.be_add_item, K_ADD_RESPONSE, args, 5, &r, &errored, "add_item")) return remaining == count ? num(-1) : num(count - remaining);  // SMOKETEST_EXCL: no committed test asks twice
        if (errored) return none();
        b = be_of(self);
        if (r.z == ANS_REFUSE || !b || !slot_named_ok(b, r.x, "add_item")) break;
        // Putting the items in needs insert_item_into_inventory's stack rules, which the smoketest
        // lacks, so a block entity that accepts fails here naming it.
        return fail("the smoketest does not implement BlockEntity.insert_item_into_inventory, which add_item uses to put the items in");  // SMOKETEST_EXCL: reached once insert_item_into_inventory exists (response_add-Test)
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
    struct list* l = find_list(b, a[1]._string);
    for (size_t j = 0; j < r->nn; j++) free(r->n[j].k);
    free(r->n);
    memmove(r, r + 1, (size_t)(l->r + l->nr - (r + 1)) * sizeof *r);
    l->nr--;
    return none();
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
static int run_test(const char* label, uint64_t file_id, uint64_t tick_fn, uint64_t init_fn) {
    clear_bes();
    free_test_state();
    clear_world();
    nblockdefs = 0;
    max_ticks = 200;
    rnd_reset();
    held_has = false; held_item_id = 0;
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
            bad++;
            if (bad <= 5) fprintf(stderr, "compile error in %s: %s\n", files.ptr[i].path, files.ptr[i].error.error_string);
        }
    }
    printf("registered %zu entries (%zu implemented), init+register %.2f ms; compiled %zu files (%zu with errors) in %.2f ms\n",
           NAPI, implemented, t1 - t0, files.len, bad, t2 - t1);

    int rc = 0;
    if (!strcmp(argv[2], "--compile-only")) { grug_deinit(g_state); return bad ? 1 : 0; }
    G.f = files;
    G.block_init = grug_get_on_fn_id(g_state, "Block", "init");
    G.be_init = grug_get_on_fn_id(g_state, "BlockEntity", "init");
    G.be_tick = grug_get_on_fn_id(g_state, "BlockEntity", "tick");
    be_inserted_fn = grug_get_on_fn_id(g_state, "BlockEntity", "item_inserted");
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
    free(bes); free(blockdefs);
    free(world); free(objs); free(arena); free(expected_error);
    grug_deinit(g_state);
    return rc;
}
