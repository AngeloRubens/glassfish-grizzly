#!/usr/bin/env python3
#
# Copyright (c) 2026 Contributors to the Eclipse Foundation
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0,
# or the Eclipse Distribution License v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
#
"""Compare two JMH JSON result files and print a Markdown table (base vs head)."""
import json
import sys


def load(path):
    out = {}
    for r in json.load(open(path)):
        name = r["benchmark"].rsplit(".", 2)
        key = name[-2] + "." + name[-1]
        params = r.get("params") or {}
        if params:
            key += " [" + ",".join(f"{k}={v}" for k, v in sorted(params.items())) + "]"
        m = r["primaryMetric"]
        out[key] = (m["score"], m.get("scoreError") or 0.0, m["scoreUnit"], r["mode"])
        alloc = r.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
        if alloc:
            out[key + " (alloc B/op)"] = (alloc["score"], alloc.get("scoreError") or 0.0, "B/op", "avgt")
    return out


def main(base_path, head_path):
    base, head = load(base_path), load(head_path)
    print("| Benchmark | Base | Head | Δ | |")
    print("|---|---:|---:|---:|---|")
    for key in sorted(set(base) | set(head)):
        b, h = base.get(key), head.get(key)
        if not b or not h:
            print(f"| `{key}` | {fmt(b)} | {fmt(h)} | n/a | |")
            continue
        lower_is_better = b[3] in ("avgt", "sample", "ss")
        delta = (h[0] - b[0]) / b[0] * 100 if b[0] else 0.0
        improved = delta < 0 if lower_is_better else delta > 0
        noise = (b[1] + h[1]) / b[0] * 100 if b[0] else 0.0
        mark = "≈" if abs(delta) <= max(noise, 2.0) else ("✅" if improved else "❌")
        print(f"| `{key}` | {fmt(b)} | {fmt(h)} | {delta:+.1f}% | {mark} |")


def fmt(v):
    if not v:
        return "—"
    return f"{v[0]:.1f} ± {v[1]:.1f} {v[2]}"


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
