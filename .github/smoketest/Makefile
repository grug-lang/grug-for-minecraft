# The smoketest host (#1585). GRUG_RS is a grug-rs checkout at core/build.gradle.kts's
# grugRsRevision with `cargo build --release -p gruggers` run in it.
GRUG_RS ?= $(HOME)/agent-workspaces/grug-rs-1585
CC ?= gcc
CFLAGS ?= -O2 -g -Wall -Wextra -std=gnu11
SAN ?=
OUT ?= smoketest
SELFTEST ?= selftest
ROOT := $(abspath $(CURDIR)/../..)

$(OUT): smoketest.c api_table.inc $(GRUG_RS)/target/release/libgruggers.a
	$(CC) $(CFLAGS) $(SAN) -o $@ smoketest.c $(GRUG_RS)/target/release/libgruggers.a -lm -lpthread -ldl

api_table.inc: gen_api_table.py $(ROOT)/mod_api.json
	python3 gen_api_table.py $(ROOT)/mod_api.json $@

# C tests of the pure helpers (selftest.c), a separate binary that never feeds the coverage run.
$(SELFTEST): selftest.c smoketest.c api_table.inc $(GRUG_RS)/target/release/libgruggers.a
	$(CC) $(CFLAGS) $(SAN) -o $@ selftest.c $(GRUG_RS)/target/release/libgruggers.a -lm -lpthread -ldl

clean:
	rm -f smoketest selftest api_table.inc
