#!/usr/bin/env python3
"""Run one command with bounded process-group cleanup and atomic evidence.

This is the reusable subset of the supervision protocol already exercised by
run-forensic-gate.sh. It always owns one exact child. On hosts with waitid
WNOWAIT it can additionally retain the session leader as a non-reusable group
anchor while cleaning every descendant that remains in that process group.
"""

import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


WATCHED_SIGNALS = (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)


def atomic_text(path, text):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("x", encoding="ascii") as handle:
        handle.write(text)
    os.replace(temporary, path)


def append_lifecycle(path, event, leader_pid=None, pgid=None, detail=None):
    if path is None:
        return
    fields = ["monotonic-ns=" + str(time.monotonic_ns()), "event=" + event]
    if leader_pid is not None:
        fields.append("leader-pid=" + str(leader_pid))
    if pgid is not None:
        fields.append("pgid=" + str(pgid))
    if detail is not None:
        fields.append("detail=" + str(detail).replace("\n", "\\n"))
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(descriptor, (" ".join(fields) + "\n").encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def signal_group(pgid, signum, lifecycle_path, leader_pid):
    append_lifecycle(
        lifecycle_path,
        "signal-group",
        leader_pid,
        pgid,
        signal.Signals(signum).name,
    )
    try:
        os.killpg(pgid, signum)
    except ProcessLookupError:
        pass


def group_exists(pgid):
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def group_has_live_members(pgid):
    if not group_exists(pgid):
        return False
    try:
        listing = subprocess.check_output(
            ["ps", "-axo", "pgid=,stat="],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        for line in listing.splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[0] == str(pgid):
                if not fields[1].startswith("Z"):
                    return True
        return False
    except (OSError, subprocess.SubprocessError):
        return group_exists(pgid)


def await_no_live_members(pgid, seconds):
    deadline = time.monotonic() + seconds
    while group_has_live_members(pgid) and time.monotonic() < deadline:
        time.sleep(0.05)
    return not group_has_live_members(pgid)


def stop_owned_group(process, pgid, grace_seconds, lifecycle_path):
    """Stop the fresh session owned by this still-running supervisor."""
    if not group_has_live_members(pgid):
        append_lifecycle(
            lifecycle_path, "group-already-dead", process.pid, pgid
        )
        return True
    signal_group(pgid, signal.SIGTERM, lifecycle_path, process.pid)
    if await_no_live_members(pgid, grace_seconds):
        append_lifecycle(lifecycle_path, "group-dead", process.pid, pgid, "TERM")
        return True
    signal_group(pgid, signal.SIGKILL, lifecycle_path, process.pid)
    stopped = await_no_live_members(pgid, grace_seconds)
    append_lifecycle(
        lifecycle_path,
        "group-dead" if stopped else "group-cleanup-failed",
        process.pid,
        pgid,
        "KILL",
    )
    return stopped


def await_leader_exit_unreaped(process, seconds):
    """Observe leader exit while retaining its PID/PGID as an ownership anchor.

    A reaped session leader releases its numeric PID and therefore its equal
    PGID for reuse. Descendant cleanup must finish before that can happen, or a
    later killpg could target an unrelated newly-created group. waitid WNOWAIT
    observes termination but deliberately leaves the leader as a zombie until
    stop_owned_group has performed its final membership check and signal.
    """
    options = os.WEXITED | os.WNOHANG | os.WNOWAIT
    deadline = time.monotonic() + seconds
    while True:
        if os.waitid(os.P_PID, process.pid, options) is not None:
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(0.05, remaining))


def have_nonreaping_group_anchor():
    required = ("P_PID", "WEXITED", "WNOHANG", "WNOWAIT")
    return hasattr(os, "waitid") and all(
        hasattr(os, name) for name in required
    )


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--deadline-seconds", type=float, required=True)
    parser.add_argument("--term-grace-seconds", type=float, default=2.0)
    parser.add_argument("--stdin", type=Path, required=True)
    parser.add_argument("--stdout", type=Path, required=True)
    parser.add_argument("--stderr", type=Path, required=True)
    parser.add_argument("--pid-file", type=Path, required=True)
    parser.add_argument("--pgid-file", type=Path)
    parser.add_argument("--lifecycle-file", type=Path)
    parser.add_argument("--status-file", type=Path, required=True)
    parser.add_argument("--timeout-file", type=Path, required=True)
    parser.add_argument("--cleanup-failed-file", type=Path, required=True)
    parser.add_argument(
        "--require-process-group-cleanup",
        action="store_true",
        help=(
            "fail before launch unless the host can retain an unreaped "
            "leader while cleaning all members of its process group"
        ),
    )
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
    process_group = None
    group_signalable = [False]
    old_mask = None
    exit_code = 125
    cleanup_succeeded = True
    leader_exit_observed = False
    leader_reaped = False
    group_anchor_available = have_nonreaping_group_anchor()

    if args.require_process_group_cleanup and not group_anchor_available:
        print(
            "bounded process supervisor requires waitid WNOWAIT for "
            "process-group cleanup",
            file=sys.stderr,
        )
        atomic_text(args.status_file, "125\n")
        append_lifecycle(
            args.lifecycle_file,
            "group-anchor-unavailable",
            detail=sys.platform,
        )
        return 125

    if hasattr(signal, "pthread_sigmask"):
        old_mask = signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED_SIGNALS)

    def restore_child_mask():
        if old_mask is not None:
            signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)

    def relay(signum, _frame):
        if caught[0] is None:
            caught[0] = signum
        if (process is not None and process_group is not None
                and group_signalable[0]):
            append_lifecycle(
                args.lifecycle_file,
                "supervisor-signal",
                process.pid,
                process_group,
                signal.Signals(signum).name,
            )
            signal_group(
                process_group, signal.SIGTERM, args.lifecycle_file, process.pid
            )
        elif (process is not None and not group_anchor_available
              and process.poll() is None):
            append_lifecycle(
                args.lifecycle_file,
                "signal-child",
                process.pid,
                process_group,
                signal.Signals(signum).name,
            )
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
                start_new_session=True,
                preexec_fn=restore_child_mask if old_mask is not None else None,
            )
            process_group = process.pid
            group_signalable[0] = group_anchor_available
            atomic_text(args.pid_file, str(process.pid) + "\n")
            if args.pgid_file is not None:
                atomic_text(args.pgid_file, str(process_group) + "\n")
            append_lifecycle(
                args.lifecycle_file,
                "started",
                process.pid,
                process_group,
                "argv0=" + args.command[0],
            )
            for watched_signal in WATCHED_SIGNALS:
                signal.signal(watched_signal, relay)
            if old_mask is not None:
                signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)
                old_mask = None
            if group_anchor_available:
                if await_leader_exit_unreaped(process, args.deadline_seconds):
                    leader_exit_observed = True
                    append_lifecycle(
                        args.lifecycle_file,
                        "leader-exit-observed-unreaped",
                        process.pid,
                        process_group,
                    )
                else:
                    args.timeout_file.open("x").close()
                    append_lifecycle(
                        args.lifecycle_file,
                        "deadline-expired",
                        process.pid,
                        process_group,
                        args.deadline_seconds,
                    )
                    exit_code = 124
            else:
                try:
                    exit_code = process.wait(timeout=args.deadline_seconds)
                    leader_reaped = True
                    append_lifecycle(
                        args.lifecycle_file,
                        "leader-reaped-no-group-anchor",
                        process.pid,
                        process_group,
                        "exit-code=" + str(exit_code),
                    )
                except subprocess.TimeoutExpired:
                    args.timeout_file.open("x").close()
                    append_lifecycle(
                        args.lifecycle_file,
                        "deadline-expired",
                        process.pid,
                        process_group,
                        args.deadline_seconds,
                    )
                    exit_code = 124
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("bounded process supervisor failed: " + str(error), file=sys.stderr)
        exit_code = 125
        append_lifecycle(
            args.lifecycle_file,
            "supervisor-error",
            process.pid if process is not None else None,
            process_group,
            error,
        )
    finally:
        if process is not None and process_group is not None and not leader_reaped:
            cleanup_succeeded = stop_owned_group(
                process,
                process_group,
                args.term_grace_seconds,
                args.lifecycle_file,
            )
            # The unreaped leader still anchors its numeric PID/PGID here.
            # Disable relay-side killpg before process.wait releases that
            # identity; a signal immediately before this assignment is safe,
            # and one immediately after records `caught` without signaling a
            # potentially recycled group.
            group_signalable[0] = False
            try:
                leader_exit_code = process.wait(timeout=1)
                append_lifecycle(
                    args.lifecycle_file,
                    "leader-reaped",
                    process.pid,
                    process_group,
                    "exit-code=" + str(leader_exit_code),
                )
                if leader_exit_observed and exit_code != 124:
                    exit_code = leader_exit_code
            except subprocess.TimeoutExpired:
                signal_group(
                    process_group,
                    signal.SIGKILL,
                    args.lifecycle_file,
                    process.pid,
                )
                try:
                    leader_exit_code = process.wait(
                        timeout=args.term_grace_seconds
                    )
                    append_lifecycle(
                        args.lifecycle_file,
                        "leader-reaped",
                        process.pid,
                        process_group,
                        "exit-code=" + str(leader_exit_code),
                    )
                    if leader_exit_observed and exit_code != 124:
                        exit_code = leader_exit_code
                except subprocess.TimeoutExpired:
                    cleanup_succeeded = False
        elif process is not None and process_group is not None:
            append_lifecycle(
                args.lifecycle_file,
                "group-cleanup-skipped-no-anchor",
                process.pid,
                process_group,
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
    append_lifecycle(
        args.lifecycle_file,
        "status-published",
        process.pid if process is not None else None,
        process_group,
        "exit-code=" + str(exit_code),
    )
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
