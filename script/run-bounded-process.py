#!/usr/bin/env python3
"""Run one command with bounded child cleanup and atomic evidence.

This is the reusable subset of the supervision protocol already exercised by
run-forensic-gate.sh. It intentionally owns one exact unreaped child, deadline,
signal relay, cleanup, and final status so shell callers never signal a
recorded PID that may have been reaped and reused.
"""

import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys


WATCHED_SIGNALS = (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)


def atomic_text(path, text):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("x", encoding="ascii") as handle:
        handle.write(text)
    os.replace(temporary, path)


def stop_owned_child(process, grace_seconds):
    """Stop only the exact child while its PID is still owned and unreaped."""
    if process.poll() is not None:
        return True
    process.terminate()
    try:
        process.wait(timeout=grace_seconds)
        return True
    except subprocess.TimeoutExpired:
        process.kill()
    try:
        process.wait(timeout=grace_seconds)
        return True
    except subprocess.TimeoutExpired:
        # An uninterruptible child can outlive SIGKILL. Do not turn that kernel
        # state into an unbounded supervisor wait; retain its still-owned PID.
        return False


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deadline-seconds", type=float, required=True)
    parser.add_argument("--term-grace-seconds", type=float, default=2.0)
    parser.add_argument("--stdin", type=Path, required=True)
    parser.add_argument("--stdout", type=Path, required=True)
    parser.add_argument("--stderr", type=Path, required=True)
    parser.add_argument("--pid-file", type=Path, required=True)
    parser.add_argument("--status-file", type=Path, required=True)
    parser.add_argument("--timeout-file", type=Path, required=True)
    parser.add_argument("--cleanup-failed-file", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    result = parser.parse_args()
    if result.command[:1] == ["--"]:
        result.command = result.command[1:]
    if not result.command:
        parser.error("a command is required after --")
    if result.deadline_seconds <= 0 or result.term_grace_seconds <= 0:
        parser.error("deadline and grace must be positive")
    return result


def main():
    args = parse_args()
    caught = [None]
    process = None
    old_mask = None
    exit_code = 125
    cleanup_succeeded = True

    if hasattr(signal, "pthread_sigmask"):
        old_mask = signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED_SIGNALS)

    def restore_child_mask():
        if old_mask is not None:
            signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)

    def relay(signum, _frame):
        if caught[0] is None:
            caught[0] = signum
        if process is not None and process.poll() is None:
            # poll() returning None guarantees this Popen still owns an
            # unreaped child PID; terminate() therefore cannot hit reuse.
            process.terminate()

    try:
        with args.stdin.open("rb", buffering=0) as stdin_handle, \
                args.stdout.open("wb", buffering=0) as stdout_handle, \
                args.stderr.open("wb", buffering=0) as stderr_handle:
            process = subprocess.Popen(
                args.command,
                stdin=stdin_handle,
                stdout=stdout_handle,
                stderr=stderr_handle,
                preexec_fn=restore_child_mask if old_mask is not None else None,
            )
            atomic_text(args.pid_file, str(process.pid) + "\n")
            for watched_signal in WATCHED_SIGNALS:
                signal.signal(watched_signal, relay)
            if old_mask is not None:
                signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)
                old_mask = None
            try:
                exit_code = process.wait(timeout=args.deadline_seconds)
            except subprocess.TimeoutExpired:
                args.timeout_file.open("x").close()
                exit_code = 124
                cleanup_succeeded = stop_owned_child(
                    process, args.term_grace_seconds
                )
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("bounded process supervisor failed: " + str(error), file=sys.stderr)
        exit_code = 125
        if process is not None:
            cleanup_succeeded = stop_owned_child(
                process, args.term_grace_seconds
            )

    # Linearize signal ownership before deciding the published status. A
    # watched signal delivered after this block remains pending only until the
    # supervisor exits; one delivered before it has already run `relay` and is
    # reflected in `caught`. Keeping the mask in place through the atomic
    # status publication closes the otherwise possible acknowledged-but-
    # omitted signal race.
    signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED_SIGNALS)
    if caught[0] is not None:
        exit_code = 128 + caught[0]
    elif exit_code < 0:
        exit_code = 128 - exit_code
    if not cleanup_succeeded:
        args.cleanup_failed_file.open("x").close()
        exit_code = 125
    atomic_text(args.status_file, str(exit_code) + "\n")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
