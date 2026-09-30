#!/usr/bin/env python3
"""Build a reobfuscation srg (dev names -> notch names) from MCP's client.srg.

MCP's client.srg maps notch names to searge names (func_* / field_*). The
decompiled dev classes use the human names from fields.csv / methods.csv, so the
reobf map has to join the two and then invert.
"""
import csv
import sys


def load_csv(path):
    # searge -> human, client side only (side 0). MCP lists a server-side row for
    # a handful of members whose name differs by side, and taking both collides.
    out = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            if row.get("side", "0") != "0":
                continue
            out[row["searge"]] = row["name"]
    return out


def main(srg_in, fields_csv, methods_csv, srg_out):
    fields = load_csv(fields_csv)
    methods = load_csv(methods_csv)

    pks, cls, fds, mds = [], [], [], []
    with open(srg_in) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            kind, _, rest = line.partition(": ")
            if kind == "PK":
                obf_pkg, dev_pkg = rest.split(" ", 1)
                pks.append((dev_pkg, obf_pkg))
            elif kind == "CL":
                obf_cls, dev_cls = rest.split(" ", 1)
                cls.append((dev_cls, obf_cls))
            elif kind == "FD":
                left, dev = rest.split(" ")
                obf_cls, obf_fld = left.rsplit("/", 1)
                dev_cls, searge = dev.rsplit("/", 1)
                fds.append((f"{dev_cls}/{fields.get(searge, searge)}", f"{obf_cls}/{obf_fld}"))
            elif kind == "MD":
                left, obf_desc, dev, dev_desc = rest.split(" ")
                obf_cls, obf_mth = left.rsplit("/", 1)
                dev_cls, searge = dev.rsplit("/", 1)
                mds.append(
                    (f"{dev_cls}/{methods.get(searge, searge)}", dev_desc,
                     f"{obf_cls}/{obf_mth}", obf_desc)
                )
            else:
                raise SystemExit(f"unknown srg line: {line}")

    with open(srg_out, "w") as f:
        for dev, obf in pks:
            f.write(f"PK: {dev} {obf}\n")
        for dev, obf in cls:
            f.write(f"CL: {dev} {obf}\n")
        for dev, obf in fds:
            f.write(f"FD: {dev} {obf}\n")
        for dev, dev_desc, obf, obf_desc in mds:
            f.write(f"MD: {dev} {dev_desc} {obf} {obf_desc}\n")
    print(f"wrote {srg_out}: {len(pks)} PK, {len(cls)} CL, {len(fds)} FD, {len(mds)} MD")


if __name__ == "__main__":
    main(*sys.argv[1:5])
