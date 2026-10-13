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
"""Summarize async-profiler collapsed stacks: top self and total frames (Markdown)."""
import collections
import sys


def main(path, n=40, only=None):
    self_c, total_c = collections.Counter(), collections.Counter()
    samples = 0
    for line in open(path):
        stack, _, count = line.rstrip("\n").rpartition(" ")
        if not stack:
            continue
        c = int(count)
        samples += c
        frames = stack.split(";")
        self_c[frames[-1]] += c
        for f in set(frames):
            total_c[f] += c
    if not samples:
        print("_no samples_")
        return
    for title, counter in (("self", self_c), ("total (inclusive), grizzly frames", total_c)):
        print(f"\n**Top {n} by {title}** — {samples} samples\n")
        print("| % | samples | frame |")
        print("|---:|---:|---|")
        shown = 0
        for f, c in counter.most_common():
            if title.startswith("total") and "glassfish/grizzly" not in f and "glassfish.grizzly" not in f:
                continue
            print(f"| {c * 100.0 / samples:.2f} | {c} | `{f[:160]}` |")
            shown += 1
            if shown >= n:
                break


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 40)
