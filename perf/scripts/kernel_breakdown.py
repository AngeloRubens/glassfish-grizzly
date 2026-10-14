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
"""Where does server CPU go, from an async-profiler 'collapsed' CPU profile (kernel frames end with _[k]).

Prints Markdown: share of samples per syscall, per kernel category of the leaf frame, and the JVM-side I/O glue.
What a native transport (epoll edge-triggered, io_uring) can save is bounded by syscall entry/exit + epoll +
NIO glue, not by the TCP/IP stack work itself.
"""
import collections
import re
import sys

CATEGORIES = [
    ("syscall entry/exit", re.compile(r"^(entry_SYSCALL|do_syscall_64|syscall_exit|exit_to_user_mode|syscall_return|srso_|__x64_sys_|__do_sys_|__se_sys_|x64_sys_call|fpregs_restore|switch_fpu)")),
    ("epoll / poll", re.compile(r"^(ep_|do_epoll|__ep_|eventpoll|ep_poll)")),
    ("scheduler / wakeups / futex", re.compile(r"^(futex|__futex|schedule|__schedule|try_to_wake_up|finish_task_switch|pick_next|dequeue_|enqueue_|update_curr|update_load|psi_|__wake_up|wake_up|eventfd|_raw_spin_unlock_irqrestore|_raw_spin_lock|native_queued_spin|__pv_queued|ttwu|select_task_rq|set_next_entity|put_prev)")),
    ("TCP/IP + loopback stack", re.compile(r"^(tcp_|__tcp_|ip_|__ip_|ipv4_|inet_|net_|__netif|netif_|process_backlog|loopback_|__dev_|dev_|skb_|__skb|kfree_skb|__kfree_skb|consume_skb|__alloc_skb|alloc_skb|sock_|__sock|sk_|__sk_|release_sock|lock_sock|nf_|__nf_|nft_|validate_xmit|eth_|napi|__napi|__local_bh|do_softirq|__do_softirq|irq_exit|csum|__copy_skb|skb|kmem_cache|__kmalloc|kmalloc|kfree|__slab|slab_|___slab|page_frag|mod_timer|__mod_timer|read_tsc|ktime_get|security_sock|apparmor|bpf_|__cgroup|cgroup_bpf|__inet|tcp4_|raw_local|__x86_return|rcu_|memcg|refill_stock|__check_object|check_heap|_copy_|copy_user|rep_movs|clear_user|__fdget|fput|__fget|fget)")),
]


def category(leaf):
    name = leaf[:-4] if leaf.endswith("_[k]") else leaf
    for label, rx in CATEGORIES:
        if rx.match(name):
            return label
    return "other kernel"


def main(path):
    total = 0
    by_syscall = collections.Counter()
    by_cat = collections.Counter()
    jvm_io = collections.Counter()
    kernel = 0
    for line in open(path):
        stack, _, count = line.rstrip("\n").rpartition(" ")
        if not stack:
            continue
        c = int(count)
        total += c
        frames = stack.split(";")
        leaf = frames[-1]
        if leaf.endswith("_[k]"):
            kernel += c
            sc = next((f[:-4] for f in frames if f.startswith("__x64_sys_") or f.startswith("__do_sys_")), None)
            by_syscall[(sc or "(no syscall: softirq/irq)").replace("__x64_sys_", "").replace("__do_sys_", "")] += c
            by_cat[category(leaf)] += c
        else:
            if any("sun/nio/ch/" in f or "java/nio/" in f for f in frames[-6:]):
                for f in reversed(frames):
                    if "sun/nio/ch/" in f:
                        jvm_io[f.split("/")[-1]] += c
                        break
                else:
                    jvm_io["java.nio (buffers)"] += c
    if not total:
        print("_no samples_")
        return
    pct = lambda c: f"{c * 100.0 / total:.1f}%"
    print(f"\n**{total} samples, kernel {pct(kernel)}**\n")
    print("| kernel time under syscall | % of all samples |\n|---|---:|")
    for k, c in by_syscall.most_common(10):
        print(f"| `{k}` | {pct(c)} |")
    print("\n| kernel leaf category | % of all samples |\n|---|---:|")
    for k, c in by_cat.most_common():
        print(f"| {k} | {pct(c)} |")
    print("\n| JVM-side NIO glue (self time near sun.nio.ch) | % of all samples |\n|---|---:|")
    for k, c in jvm_io.most_common(8):
        print(f"| `{k}` | {pct(c)} |")


if __name__ == "__main__":
    main(sys.argv[1])
