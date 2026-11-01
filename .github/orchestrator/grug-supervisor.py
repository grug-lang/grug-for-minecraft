#!/usr/bin/python3
"""Signal the grug orchestrator when its subagent floor or the main check slips.

This lives in the repository so it survives the machine it runs on. A systemd
user timer runs it every ten minutes; the unit files are beside it. It sends one
message into the orchestrator's own session when fewer than five subagents are
working, when the four-hourly build.yml dispatch on main is due, or when main's
newest run is red. It never dispatches, merges or edits anything itself: the
message says what the rules ask for and the orchestrator decides. A message
about the subagent floor is sent at most once per 45 minutes and backs off to
four hours while the number of working subagents does not rise; a message about
main waits two hours between sends. Nothing is sent while the orchestrator is in
the middle of a turn.

GRUG_REPO overrides the checkout whose sessions are watched; the repository root
above this file is the default, so the installed unit needs no path of its own.
"""

import datetime
import json
import os
import pathlib
import shutil
import subprocess
import time
import urllib.parse

REPO = os.environ.get("GRUG_REPO") or str(pathlib.Path(__file__).resolve().parents[2])
STATE = (
    pathlib.Path(os.environ.get("XDG_STATE_HOME", os.path.expanduser("~/.local/state")))
    / "grug-supervisor"
)
MODEL = os.environ.get("GRUG_SUPERVISOR_MODEL", "opencode-go/deepseek-v4.1-flash")


def find_opencode():
    for candidate in (
        os.environ.get("OPENCODE_BIN"),
        shutil.which("opencode"),
        os.path.expanduser("~/.opencode/bin/opencode"),
    ):
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    return "opencode"


OPENCODE = find_opencode()
FLOOR = 5
COOLDOWN = 45 * 60
MAX_COOLDOWN = 4 * 3600
MAIN_COOLDOWN = 2 * 3600
DISPATCH_AGE = 4 * 3600
SEND_TIMEOUT = 90 * 60
LIVE_MS = 2 * 3600 * 1000
BAD_CONCLUSIONS = {"failure", "timed_out", "startup_failure"}
DRY = os.environ.get("GRUG_SUPERVISOR_DRY") == "1"
IGNORE_BUSY = os.environ.get("GRUG_SUPERVISOR_IGNORE_BUSY") == "1"


def log(text):
    STATE.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(STATE / "log", "a") as handle:
        handle.write("%s %s\n" % (stamp, text))


def api(path, params=None):
    if params:
        query = "&".join(
            "%s=%s" % (key, urllib.parse.quote(str(value), safe=""))
            for key, value in params.items()
        )
        path = "%s?%s" % (path, query)
    command = [OPENCODE, "api", "get", path]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError("api %s failed: %s" % (path, result.stderr.strip()[:300]))
    return json.loads(result.stdout)


def sessions():
    """Every session, following the API's cursor, so an old working one is not missed."""
    gathered = []
    cursor = None
    for _ in range(20):
        page = api("/api/session", {"cursor": cursor} if cursor else None)
        gathered.extend(page.get("data", []))
        cursor = (page.get("cursor") or {}).get("next")
        if not cursor:
            break
    return gathered


def running(session, now_ms=None):
    """A session is working while its last update is newer than its last idle mark.

    The two-hour liveness window is what a child killed mid-turn needs: it never gets
    an idle mark back, so without the window it would count as a subagent forever.
    """
    now_ms = now_ms or time.time() * 1000
    times = session.get("time", {})
    updated = times.get("updated", 0)
    if now_ms - updated > LIVE_MS:
        return False
    idle = times.get("idle")
    return idle is None or updated > idle


def when(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def duration(seconds):
    seconds = max(int(seconds), 0)
    hours, rest = divmod(seconds, 3600)
    minutes = rest // 60
    if hours:
        return "%d h %d min" % (hours, minutes)
    return "%d min" % minutes


def gh_runs(extra):
    result = subprocess.run(
        [
            "gh",
            "run",
            "list",
            "--workflow",
            "build.yml",
            "--branch",
            "main",
            "--json",
            "databaseId,createdAt,conclusion,status,event,displayTitle",
        ]
        + extra,
        cwd=REPO,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if result.returncode != 0:
        raise RuntimeError("gh run list failed: %s" % result.stderr.strip()[:300])
    return json.loads(result.stdout)


def main_state():
    recent = gh_runs(["--limit", "10"])
    dispatches = gh_runs(["--event", "workflow_dispatch", "--limit", "1"])
    return recent, dispatches


def boot_time_ms():
    """The machine's boot time in milliseconds, so a session from before it is not resumed."""
    try:
        with open("/proc/stat") as handle:
            for line in handle:
                if line.startswith("btime "):
                    return int(line.split()[1]) * 1000
    except OSError:
        pass
    try:
        with open("/proc/uptime") as handle:
            return int(time.time() * 1000 - float(handle.read().split()[0]) * 1000)
    except (OSError, ValueError, IndexError):
        return 0


def orchestrator(sessions, boot):
    """The session the maintainer is actually using, and none from before the last boot.

    A pinned id wins only while it has been used since the boot. When another root
    session that has children is newer by more than an hour, the orchestrator moved,
    so the newer one wins even though the old id still exists. With no session used
    since the boot the check stays quiet until the maintainer continues one, which is
    what makes the timer safe to start before the interface does.
    """
    candidates = [
        session
        for session in sessions
        if not session.get("parentID")
        and session.get("location", {}).get("directory") == REPO
        and session.get("time", {}).get("updated", 0) >= boot
    ]
    if not candidates:
        return None
    children = {}
    for session in sessions:
        parent = session.get("parentID")
        if parent:
            children[parent] = children.get(parent, 0) + 1
    newest = max(candidates, key=lambda session: session["time"]["updated"])
    with_children = [session for session in candidates if children.get(session["id"], 0) > 0]
    heir = (
        max(with_children, key=lambda session: session["time"]["updated"])
        if with_children
        else None
    )
    pinned_file = STATE / "session-id"
    if boot and pinned_file.exists():
        pinned = pinned_file.read_text().strip()
        for session in candidates:
            if session["id"] != pinned:
                continue
            moved = (
                heir is not None
                and heir["id"] != session["id"]
                and heir["time"]["updated"] - session["time"]["updated"] > 3600 * 1000
            )
            return heir if moved else session
    if heir is not None:
        return heir
    return newest


def main():
    try:
        known = sessions()
    except Exception as error:  # noqa: BLE001 - the log is the only report a timer has
        log(str(error))
        return
    root = orchestrator(known, boot_time_ms())
    if root is None:
        log("no orchestrator session has been used since boot; waiting for one")
        return
    STATE.mkdir(parents=True, exist_ok=True)
    (STATE / "session-id").write_text(root["id"] + "\n")
    if running(root) and not IGNORE_BUSY:
        log("orchestrator is busy; checking again next tick")
        return

    working = [
        session for session in known if session.get("parentID") == root["id"] and running(session)
    ]
    now = datetime.datetime.now(datetime.UTC)
    now_epoch = now.timestamp()
    floor_line = None
    if len(working) < FLOOR:
        names = (
            "; ".join(
                "%s (%s)" % (session["title"][:60], session["id"][-4:]) for session in working
            )
            or "none"
        )
        floor_line = (
            "%d of %d subagent slots are working: %s. Dispatch queued tasks now; if no "
            "disjoint task can be named, record which area is blocked and why in the issue "
            "body." % (len(working), FLOOR, names)
        )

    main_lines = []
    try:
        recent, dispatches = main_state()
    except Exception as error:  # noqa: BLE001 - the log is the only report a timer has
        log(str(error))
        recent = None
    if recent is not None:
        in_flight = [run for run in recent if run.get("status") != "completed"]
        if in_flight:
            log(
                "a run on main is in flight (%s); no main action"
                % ", ".join(str(run["databaseId"]) for run in in_flight)
            )
        else:
            if not dispatches:
                main_lines.append(
                    "There is no build.yml dispatch on main. Run `gh workflow run build.yml "
                    "--ref main` and watch it with `.github/scripts/watch-run.sh <run-id>`."
                )
            else:
                newest_dispatch = max(dispatches, key=lambda run: when(run["createdAt"]))
                seconds = (now - when(newest_dispatch["createdAt"])).total_seconds()
                if seconds > DISPATCH_AGE:
                    main_lines.append(
                        "The newest build.yml dispatch on main is %s old (%s, %s). Run "
                        "`gh workflow run build.yml --ref main` and watch it with "
                        "`.github/scripts/watch-run.sh <run-id>`."
                        % (
                            duration(seconds),
                            newest_dispatch["databaseId"],
                            newest_dispatch.get("conclusion") or newest_dispatch.get("status"),
                        )
                    )
            newest = recent[0] if recent else None
            if (
                newest
                and newest.get("status") == "completed"
                and newest.get("conclusion") in BAD_CONCLUSIONS
            ):
                main_lines.append(
                    "The newest run on main finished red (%s, %s). Attribute it to its "
                    "merge and fix forward or revert."
                    % (newest["databaseId"], newest.get("displayTitle", ""))
                )

    state_file = STATE / "nudge.json"
    try:
        state = json.loads(state_file.read_text())
    except (OSError, ValueError):
        state = {}
    if floor_line is None:
        state["floor_count"] = None
        state["floor_delay"] = COOLDOWN
        state["floor_at"] = 0
    floor_delay = state.get("floor_delay") or COOLDOWN
    floor_due = floor_line is not None and now_epoch - state.get("floor_at", 0) >= floor_delay
    main_due = bool(main_lines) and now_epoch - state.get("main_at", 0) >= MAIN_COOLDOWN

    if not (floor_due or main_due):
        state_file.write_text(json.dumps(state) + "\n")
        log(
            "ok: %d of %d working; floor %s, main %s"
            % (
                len(working),
                FLOOR,
                "due" if floor_due else "quiet",
                "due" if main_due else "quiet",
            )
        )
        return

    lines = []
    if floor_due:
        lines.append(floor_line)
    if main_due:
        lines.extend(main_lines)
    message = "[automatic supervisor, not the maintainer] %s\n\n%s\n" % (
        now.strftime("%H:%MZ"),
        "\n".join("- " + line for line in lines),
    )
    if DRY:
        print(message)
        log("dry run: " + " | ".join(lines))
        return
    failure = None
    try:
        result = subprocess.run(
            [OPENCODE, "run", "-s", root["id"], "--model", MODEL, "--auto", message],
            cwd=REPO,
            capture_output=True,
            text=True,
            timeout=SEND_TIMEOUT,
        )
        if result.returncode != 0:
            failure = "rc=%d: %s %s" % (
                result.returncode,
                (result.stdout or "")[-300:],
                (result.stderr or "")[-300:],
            )
    except (subprocess.TimeoutExpired, OSError) as error:
        failure = str(error)[:300]
    # Record the attempt whether or not it landed. A rejected send must not retry every
    # ten minutes, and a message that started a turn must not be sent twice after it.
    if floor_due:
        previous = state.get("floor_count")
        if previous is None or len(working) > previous:
            state["floor_delay"] = COOLDOWN
        else:
            state["floor_delay"] = min(floor_delay * 2, MAX_COOLDOWN)
        state["floor_count"] = len(working)
        state["floor_at"] = now_epoch
    if main_due:
        state["main_at"] = now_epoch
    state_file.write_text(json.dumps(state) + "\n")
    if failure:
        log("send failed (%s): %s" % (failure, " | ".join(lines)))
    else:
        log("sent: " + " | ".join(lines))


if __name__ == "__main__":
    main()
