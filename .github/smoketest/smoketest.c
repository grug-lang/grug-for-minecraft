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
extern void grug_set_next_entity_id(void*, uint64_t next_id);

// ---------------------------------------------------------------------------------------------
// Objects. A grug value of a class type is an id; here it indexes this table and is never reused
// within a test. The table is emptied between tests, so a handle a test saved cannot leak across.
enum kind { K_NONE, K_LEVEL, K_VEC3, K_OPTION, K_CTX, K_ME, K_RL, K_ITEM, K_BLOCKPOS, K_SHOT };
struct obj { enum kind kind; double x, y, z; bool has; union grug_value val; const char* str; };
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
static bool quiet;

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
static void on_block_placed(long x, long y, long z, const char* name);
static union grug_value Level_place_block(ARGS) {
    UNUSED;
    double x = a[1]._number, y = a[2]._number, z = a[3]._number;
    if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return fail("Level.place_block: a coordinate is not finite");
    if (y < 0 || y >= world_height)
        return fail("Level.place_block: the game did not put %s at (%g, %g, %g)", a[4]._string, x, y, z);
    if (!strchr(a[4]._string, ':')) return fail("Level.place_block: '%s' is not a block name", a[4]._string);
    set_block((long)floor(x), (long)floor(y), (long)floor(z), a[4]._string);
    on_block_placed((long)floor(x), (long)floor(y), (long)floor(z), a[4]._string);
    return none();
}
// The level's random is java.util.Random with a fixed seed (the algorithm random_int's description names).
static uint64_t rnd_seed = (42ull ^ 0x5DEECE66Dull) & ((1ull << 48) - 1);
static int32_t rnd_next(int bits) {
    rnd_seed = (rnd_seed * 0x5DEECE66Dull + 0xB) & ((1ull << 48) - 1);
    return (int32_t)(rnd_seed >> (48 - bits));
}
static union grug_value Level_random_int(ARGS) {
    UNUSED;
    double b = a[1]._number;
    if (!is_whole(b) || b < 1 || b > 2147483647.0) return fail("The bound has to be a whole number from 1 to 2147483647");
    int32_t bound = (int32_t)b, r = rnd_next(31), m = bound - 1;
    if ((bound & m) == 0) return num((double)(int32_t)(((int64_t)bound * (int64_t)r) >> 31));
    for (int32_t u = r; u - (r = u % bound) + m < 0; u = rnd_next(31)) {}
    return num((double)r);
}
static union grug_value Level_random_float(ARGS) { UNUSED; return num(rnd_next(24) / (double)(1 << 24)); }
static union grug_value Level_random_gaussian(ARGS) {
    UNUSED;
    double v1, v2, s;
    do {
        v1 = 2 * ((((int64_t)rnd_next(26) << 27) + rnd_next(27)) * (1.0 / (1ll << 53))) - 1;
        v2 = 2 * ((((int64_t)rnd_next(26) << 27) + rnd_next(27)) * (1.0 / (1ll << 53))) - 1;
        s = v1 * v1 + v2 * v2;
    } while (s >= 1 || s == 0);
    return num(v1 * sqrt(-2 * log(s) / s));
}
static double time_offset;
static union grug_value Level_get_time(ARGS) { UNUSED; return num((double)cur_tick + time_offset); }
static union grug_value Level_set_time(ARGS) {
    UNUSED;
    double t = a[1]._number;
    if (!is_whole(t) || t < 0 || t > 9007199254740991.0) return fail("Level.set_time: The time has to be a whole number of ticks from 0 to 9007199254740991");
    time_offset = t - (double)cur_tick;
    return none();
}

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
static union grug_value print1(ARGS) { (void)gst; if (quiet) return none(); print_value(&a[0], &g[0]); putchar('\n'); return none(); }
static union grug_value print2(ARGS) {
    (void)gst;
    if (quiet) return none();
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar('\n');
    return none();
}
static union grug_value print3(ARGS) {
    (void)gst;
    if (quiet) return none();
    print_value(&a[0], &g[0]); putchar(' '); print_value(&a[1], &g[1]); putchar(' ');
    print_value(&a[2], &g[2]); putchar('\n');
    return none();
}

// ---------------------------------------------------------------------------------------------
// Resource locations and items. An Item is interned by its name, so grug's `==` on an Item (which
// compares ids) and equals() agree, as they do for the game's one item per name. No item table is
// kept: a name is taken as given, with "minecraft:" in front of a bare one.
static union grug_value hf_resource_location(ARGS) {
    UNUSED;
    uint64_t r = new_obj(K_RL);
    const char* s = a[0]._string;
    size_t n = strlen(s) + 11;
    char* full = malloc(n);
    snprintf(full, n, "%s%s", strchr(s, ':') ? "" : "minecraft:", s);
    objs[r - 1].str = keep(full);
    return id(r);
}
static union grug_value hf_item(ARGS) {
    UNUSED;
    struct obj* rl = get_obj(a[0]._id, K_RL);
    if (!rl) return fail("item: not a ResourceLocation");
    for (size_t i = 0; i < nobjs; i++)
        if (objs[i].kind == K_ITEM && !strcmp(objs[i].str, rl->str)) return id(i + 1);
    uint64_t it = new_obj(K_ITEM);
    objs[it - 1].str = rl->str;
    return id(it);
}

// BlockPos: the two methods the committed block entity scripts and tests call.
static union grug_value BlockPos_above_n(ARGS) {
    UNUSED;
    struct obj* p = get_obj(a[0]._id, K_BLOCKPOS);
    if (!p) return fail("BlockPos.above_n: not a BlockPos");
    uint64_t q = new_obj(K_BLOCKPOS);
    objs[q - 1].x = p->x; objs[q - 1].y = p->y + a[1]._number; objs[q - 1].z = p->z;
    return id(q);
}
static union grug_value BlockPos_center(ARGS) {
    UNUSED;
    struct obj* p = get_obj(a[0]._id, K_BLOCKPOS);
    if (!p) return fail("BlockPos.center: not a BlockPos");
    uint64_t v = new_obj(K_VEC3);
    objs[v - 1].x = p->x + 0.5; objs[v - 1].y = p->y + 0.5; objs[v - 1].z = p->z + 0.5;
    return id(v);
}

// ---------------------------------------------------------------------------------------------
// Block entities. A block with a BlockEntity script gets a grug entity when it is placed, whose
// init() runs at once (block_entity_init_moment-Test) and whose tick() runs once per runner tick
// (the toy world has no game-tick/frame split). A block entity's handle is its grug entity's id,
// which is what `me` holds, set with grug_set_next_entity_id before the entity is created because
// the member scope already calls me.block_pos(). The ids start far above the object table's.
#define BE_BASE (1ull << 40)
struct kv { char* k; double v; };
struct record { struct kv* n; size_t nn; uint64_t item; bool has_item; };
struct list { char* name; struct record* r; size_t nr; };
struct be {
    bool live;
    long x, y, z;
    void* entity;
    struct kv* nums; size_t nnums;
    struct list* lists; size_t nlists;
};
static struct be* bes;
static size_t nbes, capbes;

static struct files_ref { struct grug_files_slice f; uint64_t block_init, be_init, be_tick; } G;
static char bg_missing[512];   // a "does not implement" raised inside a block entity call
static char last_error[4096];  // the last runtime error the handler saw

static struct be* be_of(uint64_t h) { return h >= BE_BASE && h - BE_BASE < nbes && bes[h - BE_BASE].live ? &bes[h - BE_BASE] : NULL; }

// Runs a block entity or block export. A grug mod err is contained, as in the game; the one error
// that is not is a missing smoketest entry, which would make every later assertion meaningless.
static void call_contained(void* entity, uint64_t fn, const union grug_value* args, size_t n) {
    char saved[sizeof last_error];
    memcpy(saved, last_error, sizeof saved);
    last_error[0] = 0;
    if (!grug_call_export_fn(g_state, entity, fn, args, n) && strstr(last_error, "does not implement") && !bg_missing[0])
        snprintf(bg_missing, sizeof bg_missing, "%s", last_error);
    memcpy(last_error, saved, sizeof saved);
}

static void free_list(struct list* l) {
    for (size_t i = 0; i < l->nr; i++) {
        for (size_t j = 0; j < l->r[i].nn; j++) free(l->r[i].n[j].k);
        free(l->r[i].n);
    }
    free(l->r); free(l->name);
}
static void free_be(struct be* b) {
    if (!b->live) return;
    if (b->entity) grug_deinit_entity(g_state, b->entity);
    for (size_t i = 0; i < b->nnums; i++) free(b->nums[i].k);
    free(b->nums);
    for (size_t i = 0; i < b->nlists; i++) free_list(&b->lists[i]);
    free(b->lists);
    memset(b, 0, sizeof *b);
}
static void clear_bes(void) {
    for (size_t i = 0; i < nbes; i++) free_be(&bes[i]);
    nbes = 0;
}

// Per-block registry: Block.init runs once per test on first placement, with set_block_entity and
// set_inventory_size the only BlockInitContext entries implemented. Any other setter a block calls
// fails the placement naming the entry.
struct blockdef { const char* name; bool inited; long be_file; };
static struct blockdef* blockdefs;
static size_t nblockdefs;
static struct { long be_file; bool in_init; } cur_binit;

static union grug_value BIC_set_block_entity(ARGS) {
    UNUSED;
    const char* e = a[1]._string;
    const char* colon = strchr(e, ':');
    e = colon ? colon + 1 : e;
    for (size_t i = 0; i < G.f.len; i++)
        if (G.f.ptr[i].entity_type && !strcmp(G.f.ptr[i].entity_type, "BlockEntity") && !strcmp(G.f.ptr[i].entity_name, e)) cur_binit.be_file = (long)i;
    return none();
}
static union grug_value BIC_set_inventory_size(ARGS) { UNUSED; return none(); }
// Recorded and read by nothing yet: no committed logic test observes a block's hardness or material
// without mining or a world capture, which the toy world has not. A test that does needs the mining
// entry first and fails on that, by name.
static union grug_value BIC_set_hardness(ARGS) {
    UNUSED;
    if (!isfinite(a[1]._number) || a[1]._number < 0) return fail("BlockInitContext.set_hardness: the hardness has to be a finite number of at least 0");
    return none();
}
static union grug_value BIC_set_material(ARGS) { UNUSED; return none(); }
// Graphics flags (#1548: graphics calls stay stubs): nothing draws, so nothing reads them.
static union grug_value BIC_graphics_flag(ARGS) { UNUSED; return none(); }

static struct blockdef* find_blockdef(const char* name) {
    if (strncmp(name, "grug:", 5)) return NULL;
    for (size_t i = 0; i < nblockdefs; i++) if (!strcmp(blockdefs[i].name, name)) return &blockdefs[i];
    for (size_t i = 0; i < G.f.len; i++) {
        struct grug_file_info* fi = &G.f.ptr[i];
        if (fi->file_id == UINT64_MAX || !fi->entity_type || strcmp(fi->entity_type, "Block") || strcmp(fi->entity_name, name + 5)) continue;
        blockdefs = realloc(blockdefs, (nblockdefs + 1) * sizeof *blockdefs);
        struct blockdef* d = &blockdefs[nblockdefs++];
        d->name = keep(strdup(name)); d->inited = false; d->be_file = -1;
        // Block.init has no entity to hold: create, run, drop.
        void* ent = grug_create_entity(g_state, fi->file_id);
        cur_binit.be_file = -1;
        union grug_value arg = id(new_obj(K_CTX));
        call_contained(ent, G.block_init, &arg, 1);
        d->be_file = cur_binit.be_file;
        grug_deinit_entity(g_state, ent);
        return d;
    }
    return NULL;
}

static struct be* be_at(long x, long y, long z) {
    for (size_t i = 0; i < nbes; i++) if (bes[i].live && bes[i].x == x && bes[i].y == y && bes[i].z == z) return &bes[i];
    return NULL;
}
static void make_be(long x, long y, long z, long file) {
    if (nbes == capbes) { capbes = capbes ? capbes * 2 : 32; bes = realloc(bes, capbes * sizeof *bes); if (!bes) abort(); }
    struct be* b = &bes[nbes];
    memset(b, 0, sizeof *b);
    b->live = true; b->x = x; b->y = y; b->z = z;
    uint64_t handle = BE_BASE + nbes++;
    grug_set_next_entity_id(g_state, handle);
    b->entity = grug_create_entity(g_state, G.f.ptr[file].file_id);
    union grug_value arg = id(new_obj(K_CTX));
    call_contained(b->entity, G.be_init, &arg, 1);
}
static void on_block_placed(long x, long y, long z, const char* name) {
    struct be* old = be_at(x, y, z);
    if (old) free_be(old);
    struct blockdef* d = find_blockdef(name);
    if (d && d->be_file >= 0) make_be(x, y, z, d->be_file);
}
static void tick_bes(void) {
    for (size_t i = 0; i < nbes; i++)
        if (bes[i].live && bes[i].entity) call_contained(bes[i].entity, G.be_tick, NULL, 0);
}

static union grug_value Level_get_block_entity(ARGS) {
    UNUSED;
    struct be* b = be_at((long)floor(a[1]._number), (long)floor(a[2]._number), (long)floor(a[3]._number));
    uint64_t o = new_obj(K_OPTION);
    if (b) { objs[o - 1].has = true; objs[o - 1].val = id(BE_BASE + (uint64_t)(b - bes)); }
    return id(o);
}
static union grug_value BE_block_pos(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.block_pos: not a live block entity");
    uint64_t p = new_obj(K_BLOCKPOS);
    objs[p - 1].x = (double)b->x; objs[p - 1].y = (double)b->y; objs[p - 1].z = (double)b->z;
    return id(p);
}
static union grug_value BE_level(ARGS) { UNUSED; return id(new_obj(K_LEVEL)); }

static union grug_value TTC_tick_block_entity(ARGS) {
    UNUSED;
    struct be* b = be_of(a[1]._id);
    if (!b) return fail("TestTickContext.tick_block_entity: not a live block entity");
    if (!b->entity) return fail("Only a grug block entity has a tick to run");
    double n = a[2]._number;
    if (!is_whole(n) || n < 0 || n > 2147483647.0) return fail("The ticks have to be a whole number from 0 to 2147483647");
    for (long i = 0; i < (long)n; i++) call_contained(b->entity, G.be_tick, NULL, 0);
    return none();
}

// Saved numbers and saved lists. Index and key errors use the texts the expect_*saved* tests wait for.
static union grug_value BE_set_saved_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_number: not a live block entity");
    for (size_t i = 0; i < b->nnums; i++) if (!strcmp(b->nums[i].k, a[1]._string)) { b->nums[i].v = a[2]._number; return none(); }
    b->nums = realloc(b->nums, (b->nnums + 1) * sizeof *b->nums);
    b->nums[b->nnums].k = strdup(a[1]._string);
    b->nums[b->nnums++].v = a[2]._number;
    return none();
}
static union grug_value BE_get_saved_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_number: not a live block entity");
    for (size_t i = 0; i < b->nnums; i++) if (!strcmp(b->nums[i].k, a[1]._string)) return num(b->nums[i].v);
    return num(0);
}
static struct list* find_list(struct be* b, const char* name) {
    for (size_t i = 0; i < b->nlists; i++) if (!strcmp(b->lists[i].name, name)) return &b->lists[i];
    return NULL;
}
// The record a call names, or NULL after raising the error.
static struct record* record_at(struct be* b, const char* name, double index, size_t* out_n) {
    struct list* l = find_list(b, name);
    size_t n = l ? l->nr : 0;
    if (out_n) *out_n = n;
    if (!is_whole(index) || index < 0) { fail("The index has to be a whole number from 0 to %zu", n ? n - 1 : 0); return NULL; }
    if (!l || (size_t)index >= n) { fail("There is no record at index %.0f", index); return NULL; }
    return &l->r[(size_t)index];
}
static union grug_value BE_get_saved_list_size(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_size: not a live block entity");
    struct list* l = find_list(b, a[1]._string);
    return num(l ? (double)l->nr : 0);
}
static union grug_value BE_append_saved_list_record(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.append_saved_list_record: not a live block entity");
    struct list* l = find_list(b, a[1]._string);
    if (!l) {
        b->lists = realloc(b->lists, (b->nlists + 1) * sizeof *b->lists);
        l = &b->lists[b->nlists++];
        memset(l, 0, sizeof *l);
        l->name = strdup(a[1]._string);
    }
    l->r = realloc(l->r, (l->nr + 1) * sizeof *l->r);
    memset(&l->r[l->nr], 0, sizeof *l->r);
    return num((double)l->nr++);
}
static union grug_value BE_remove_saved_list_record(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.remove_saved_list_record: not a live block entity");
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    struct list* l = find_list(b, a[1]._string);
    for (size_t j = 0; j < r->nn; j++) free(r->n[j].k);
    free(r->n);
    memmove(r, r + 1, (size_t)(l->r + l->nr - (r + 1)) * sizeof *r);
    l->nr--;
    return none();
}
static union grug_value BE_get_saved_list_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_number: not a live block entity");
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    for (size_t j = 0; j < r->nn; j++) if (!strcmp(r->n[j].k, a[3]._string)) return num(r->n[j].v);
    return num(0);
}
static union grug_value BE_set_saved_list_number(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_list_number: not a live block entity");
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (!r) return none();
    for (size_t j = 0; j < r->nn; j++) if (!strcmp(r->n[j].k, a[3]._string)) { r->n[j].v = a[4]._number; return none(); }
    r->n = realloc(r->n, (r->nn + 1) * sizeof *r->n);
    r->n[r->nn].k = strdup(a[3]._string);
    r->n[r->nn++].v = a[4]._number;
    return none();
}
static union grug_value BE_set_saved_list_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.set_saved_list_item: not a live block entity");
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    if (r) { r->has_item = true; r->item = a[3]._id; }
    return none();
}
static union grug_value BE_get_saved_list_item(ARGS) {
    UNUSED;
    struct be* b = be_of(a[0]._id);
    if (!b) return fail("BlockEntity.get_saved_list_item: not a live block entity");
    struct record* r = record_at(b, a[1]._string, a[2]._number, NULL);
    uint64_t o = new_obj(K_OPTION);
    if (r && r->has_item) { objs[o - 1].has = true; objs[o - 1].val = id(r->item); }
    return id(o);
}

// The runner's own test settings. Graphics calls are stubs: a capture is a token equal to itself,
// and the world is always settled, so a test that mixes world changes with a screenshot goes on.
static int max_ticks = 200;
static union grug_value TIC_set_max_ticks(ARGS) {
    UNUSED;
    double n = a[1]._number;
    if (!is_whole(n) || n < 200 || n > 2147483647.0) return fail("The ticks have to be a whole number from 200 to 2147483647");
    if (n > max_ticks) max_ticks = (int)n;
    return none();
}
static union grug_value TTC_set_box_radius(ARGS) {
    UNUSED;
    // The room itself is not built: the toy world has no walls, and a stub can only check the argument.
    if (!is_whole(a[1]._number) || a[1]._number < 1) return fail("The radius has to be a whole number of at least 1");
    if (a[1]._number > 30) return fail("The radius has to be at most 30: a wider room would reach chunks the client never receives");
    return none();
}
// Screenshots are tokens equal to themselves (#1548): nothing is drawn, so a capture is always the
// same as the reference and a test that mixes world changes with a screenshot goes on. The one
// comparison that cannot hold is differs_from, which fails unless asked for no difference.
static union grug_value TTC_screenshot(ARGS) { UNUSED; return id(new_obj(K_SHOT)); }
static union grug_value Shot_self(ARGS) { UNUSED; return a[0]; }
static union grug_value Shot_equals(ARGS) { UNUSED; return none(); }
static union grug_value Shot_change_from(ARGS) { UNUSED; return num(0); }
static union grug_value Shot_differs_from(ARGS) {
    UNUSED;
    if (a[2]._number > 0) return fail("Screenshot.differs_from: the smoketest draws nothing, so two captures never differ");
    return none();
}
// A loader that draws no overlays and no world overlays is one a test returns early on, which is
// what a test that asks has to handle; the smoketest is such a loader.
static union grug_value Test_overlay_supported(ARGS) { UNUSED; return boolean(false); }
static union grug_value TTC_world_settled(ARGS) { UNUSED; return boolean(true); }
// A stall only has to take time on the real game; the toy world has no clock to block.
static union grug_value TTC_stall(ARGS) {
    UNUSED;
    if (!isfinite(a[1]._number) || a[1]._number < 0 || a[1]._number > 10000) return fail("The time has to be from 0 to 10000 milliseconds");
    return none();
}
static union grug_value TTC_close_screen(ARGS) { UNUSED; return none(); }

// ---------------------------------------------------------------------------------------------
// The registry: every entry of mod_api.json, and which of them have an implementation.
struct api_row { const char* cls; const char* name; host_fn stub; };
#include "api_table_stubs.inc"
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
    {"TestInitContext", "get_origin", TTC_get_origin},
    {"TestInitContext", "set_max_ticks", TIC_set_max_ticks},
    {"TestTickContext", "tick_block_entity", TTC_tick_block_entity},
    {"TestTickContext", "set_box_radius", TTC_set_box_radius},
    {"TestTickContext", "world_settled", TTC_world_settled},
    {"TestTickContext", "stall", TTC_stall},
    {"TestTickContext", "close_screen", TTC_close_screen},
    {NULL, "resource_location", hf_resource_location},
    {NULL, "item", hf_item},
    {"BlockPos", "above_n", BlockPos_above_n},
    {"BlockPos", "center", BlockPos_center},
    {"BlockInitContext", "set_block_entity", BIC_set_block_entity},
    {"BlockInitContext", "set_inventory_size", BIC_set_inventory_size},
    {"BlockInitContext", "set_hardness", BIC_set_hardness},
    {"BlockInitContext", "set_material", BIC_set_material},
    {"BlockInitContext", "set_custom_render", BIC_graphics_flag},
    {"BlockInitContext", "set_frame_render", BIC_graphics_flag},
    {"BlockInitContext", "set_flat_lighting", BIC_graphics_flag},
    {"BlockInitContext", "set_display_tick", BIC_graphics_flag},
    {"BlockInitContext", "set_shape", BIC_graphics_flag},
    {"Level", "get_block_entity", Level_get_block_entity},
    {"BlockEntity", "block_pos", BE_block_pos},
    {"BlockEntity", "level", BE_level},
    {"BlockEntity", "set_saved_number", BE_set_saved_number},
    {"BlockEntity", "get_saved_number", BE_get_saved_number},
    {"BlockEntity", "get_saved_list_size", BE_get_saved_list_size},
    {"BlockEntity", "append_saved_list_record", BE_append_saved_list_record},
    {"BlockEntity", "remove_saved_list_record", BE_remove_saved_list_record},
    {"BlockEntity", "get_saved_list_number", BE_get_saved_list_number},
    {"BlockEntity", "set_saved_list_number", BE_set_saved_list_number},
    {"BlockEntity", "set_saved_list_item", BE_set_saved_list_item},
    {"BlockEntity", "get_saved_list_item", BE_get_saved_list_item},
    {"TestTickContext", "screenshot", TTC_screenshot},
    {"Screenshot", "tolerance", Shot_self},
    {"Screenshot", "capture", Shot_self},
    {"Screenshot", "equals", Shot_equals},
    {"Screenshot", "change_from", Shot_change_from},
    {"Screenshot", "differs_from", Shot_differs_from},
    {"Test", "overlay_supported", Test_overlay_supported},
    {"Test", "world_overlay_supported", Test_overlay_supported},
    {"Level", "random_int", Level_random_int},
    {"Level", "random_float", Level_random_float},
    {"Level", "random_gaussian", Level_random_gaussian},
    {"Level", "set_time", Level_set_time},
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

// ---------------------------------------------------------------------------------------------
static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static void on_runtime_error(void* data, struct grug_runtime_error* e) {
    (void)data;
    snprintf(last_error, sizeof last_error, "%s", e && e->error_string ? e->error_string : "(no message)");
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

static int run_test(const char* label, uint64_t file_id, uint64_t tick_fn, uint64_t init_fn) {
    clear_bes();
    free_test_state();
    clear_world();
    nblockdefs = 0;
    max_ticks = 200;
    time_offset = 0;
    bg_missing[0] = 0;
    cur_tick = 0;
    free(expected_error);
    expected_error = NULL;
    void* entity = grug_create_entity(g_state, file_id);
    if (!entity) { report("FAIL", label, "could not create the entity"); return 1; }
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
        not_done_called = false;
        bool completed = grug_call_export_fn(g_state, entity, tick_fn, &arg, 1);
        if (bg_missing[0]) { report("FAIL", label, "%s", bg_missing); rc = 1; }
        else if (!completed) {
            if (expected_error && strstr(last_error, expected_error)) { report("PASS", label, "%s", ""); break; }
            if (expected_error) report("FAIL", label, "expected an error containing '%s', but got: %s", expected_error, last_error);
            else report("FAIL", label, "%s", last_error);
            rc = 1;
        } else if (not_done_called) {
            tick_bes();
            if (++cur_tick >= max_ticks) { report("FAIL", label, "exceeded %d ticks", max_ticks); rc = 1; }
        } else {
            if (expected_error) { report("FAIL", label, "expected an error containing '%s', but it completed without one", expected_error); rc = 1; }
            else report("PASS", label, "%s", "");
            break;
        }
    }
    grug_deinit_entity(g_state, entity);
    return rc;
}

// --all: run every test of the mods in `mods`, and print each failure with the first entry it
// needed that is missing, then the counts and a tally of the missing entries.
struct tally { char name[160]; int n; };
static int run_all(struct grug_files_slice files, uint64_t tick_fn, uint64_t init_fn) {
    static struct tally tallies[512];
    size_t ntally = 0;
    int passed = 0, failed = 0, missing_failures = 0;
    quiet = true;
    for (size_t i = 0; i < files.len; i++) {
        const char* path = files.ptr[i].path;
        if (!strstr(path, "/tests/") || files.ptr[i].file_id == UINT64_MAX) continue;
        if (!run_test(path, files.ptr[i].file_id, tick_fn, init_fn)) { passed++; continue; }
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
    printf("smoketest --all: %d passed, %d failed (%d on a missing entry), %d total\n", passed, failed, missing_failures, passed + failed);
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
    uint64_t tick_fn = grug_get_on_fn_id(g_state, "Test", "tick");
    uint64_t init_fn = grug_get_on_fn_id(g_state, "Test", "init");
    if (!strcmp(argv[2], "--all")) { rc = run_all(files, tick_fn, init_fn); goto done; }
    int ran = 0;
    for (int i = 2; i < argc; i++) {
        size_t found = files.len;
        for (size_t j = 0; j < files.len; j++) if (!strcmp(files.ptr[j].path, argv[i])) found = j;
        if (found == files.len || files.ptr[found].file_id == UINT64_MAX) { printf("FAIL %s: not compiled\n", argv[i]); rc = 1; continue; }
        double a = now_ms();
        rc |= run_test(argv[i], files.ptr[found].file_id, tick_fn, init_fn);
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
