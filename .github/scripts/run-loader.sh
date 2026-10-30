#!/usr/bin/env bash
# scripts/ci/run-loader.sh <loader-dir-name> <build|build-run|run|update-goldens|production>
#
# build compiles the loader and writes its layer of the universal jar, without launching the game.
# build-run is build followed by run in one Gradle invocation, so the job pays one Gradle start and
# one configuration instead of two. The game's time limit starts when Gradle starts the runClient
# task, not before, and a Gradle failure before that point is reported as a failed build.
#
# GRUG_CI_BOOT_ONLY, when set, makes the game stop itself with status 0 as soon as grug has compiled
# its mods (GrugBootOnly in core prints GRUG_LOADED_MSG first), instead of waiting for the tests. It
# is for a platform that cannot give a whole run what it needs, such as OpenGL and the Linux
# goldens. The game never opens a window, and the run succeeds on that line.
#
# production runs the same tests in an installed game instead of the development run: it hands the
# universal jar named by GRUG_UNIVERSAL_JAR (which .github/scripts/universal_jar.py assembles from
# every loader's layerJar) to launch-production.sh, which installs the game and mod loader the way a
# player's launcher does and runs it with that jar alone. The installer downloads the game and its
# loader before the game exists, so that install has a limit of its own, GRUG_CI_INSTALL_TIMEOUT,
# and GRUG_CI_RUN_TIMEOUT starts at the line that reports the game has launched (#725).

set -uo pipefail

if [ "$#" -ne 2 ] || { [ "$2" != build ] && [ "$2" != build-run ] && [ "$2" != run ] && [ "$2" != update-goldens ] && [ "$2" != production ]; }; then
  echo "Usage: $0 <loader-dir-name> <build|build-run|run|update-goldens|production>" >&2
  exit 2
fi
LOADER="$1"
PHASE="$2"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# Beta/Alpha's chunk renderer sorts chunks with a comparator that is not transitive whenever two
# chunks share a distance, which makes TimSort abort the render thread with "Comparison method
# violates its general contract!" and kills the run. The legacy merge sort tolerates it. The
# property has to reach the forked game JVM, so pass it to every JVM the launcher starts.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.util.Arrays.useLegacyMergeSort=true"

# A Rust panic in grug-rs cannot unwind out of an extern "C" frame, so it aborts the JVM instead of
# failing a test. Rust's default backtrace stops at the panic hook, which leaves the report as a
# list of std frames with the grug entry point at the bottom and nothing in between, so the frame
# that was running when it aborted is missing. "full" prints the whole stack instead, which is the
# only thing that says which grug code path reached the abort. The environment variable has to
# reach the forked game JVM, which is why it is exported rather than passed to Gradle.
export RUST_BACKTRACE="${RUST_BACKTRACE:-full}"

LOADER_DIR="loaders/${LOADER}"
if [ ! -d "$LOADER_DIR" ]; then
  echo "No such loader directory: $LOADER_DIR" >&2
  exit 2
fi

# Git Bash on a Windows runner reports OSTYPE=msys. Windows has no X server to start: the game opens
# its window on the runner's desktop.
case "${OSTYPE:-}" in msys* | cygwin*) WINDOWS=true ;; *) WINDOWS=false ;; esac

if [ "$PHASE" != build ] && [ "$WINDOWS" = false ] && [ -z "${DISPLAY:-}" ]; then
  echo "DISPLAY is not set. Start an X server first, e.g. run under xvfb-run." >&2
  exit 2
fi

mkdir -p "${LOADER_DIR}/run"
LOG_FILE="${REPO_ROOT}/${LOADER_DIR}/run/ci-run-loader.log"

# The game directory: the loader's run/ for a development run, and for a production run a fresh one
# under build/ that holds nothing but what an installed game would.
GAME_DIR="${REPO_ROOT}/${LOADER_DIR}/run"
if [ "$PHASE" = production ]; then
  GAME_DIR="${REPO_ROOT}/${LOADER_DIR}/build/production-game"
  LOG_FILE="${REPO_ROOT}/${LOADER_DIR}/build/ci-production.log"
  rm -rf "$GAME_DIR"
  mkdir -p "$GAME_DIR"
fi

# Exporting as an environment variable ensures it safely passes to the forked Minecraft JVM
export GRUG_CI=true
gradle_args=(-Dgrug.activeLoader="$LOADER" --stacktrace)

# Gradle's parallel project execution, so core's tasks and the active loader's overlap in the one
# invocation a loader job runs. It goes here and not in org.gradle.parallel in gradle.properties,
# which has no way to be conditional on the environment: a plain local `./gradlew build` keeps
# Gradle's sequential default. See #702.
gradle_args+=(-Dorg.gradle.parallel=true)

# CI builds the grug-rs static library once per run and passes it to every job as an artifact,
# because it is the same file for all five loaders. Handing it over takes cloneGrugRs and buildGrugRs
# out of the task graph, which is a git fetch plus a cargo build this job would otherwise repeat. Left
# unset locally, where building grug-rs from the pinned revision is the point.
if [ -n "${GRUG_PREBUILT_GRUG_RS:-}" ]; then
  if [ ! -f "$GRUG_PREBUILT_GRUG_RS" ]; then
    echo "==> ERROR: GRUG_PREBUILT_GRUG_RS is set to '$GRUG_PREBUILT_GRUG_RS', which is not a file." >&2
    exit 1
  fi
  gradle_args+=(-Pgrug.prebuiltGrugRs="$GRUG_PREBUILT_GRUG_RS")
fi

# Accept unmatched screenshot captures into the mods' screenshots/ directories, so a contributor
# reviews the new PNGs in the working tree instead of copying them out of the artifacts directory.
if [ "$PHASE" = update-goldens ]; then
  export GRUG_UPDATE_GOLDENS=1
fi

# The universal jar a production run launches, resolved before the cd below. It is assembled from
# all five loaders, which no single loader's build produces, so it is handed in rather than built.
if [ "$PHASE" = production ]; then
  if [ -z "${GRUG_UNIVERSAL_JAR:-}" ] || [ ! -f "$GRUG_UNIVERSAL_JAR" ]; then
    echo "==> ERROR: production launches the universal jar; set GRUG_UNIVERSAL_JAR to one assembled by .github/scripts/universal_jar.py." >&2
    exit 2
  fi
  UNIVERSAL_JAR="$(cd "$(dirname "$GRUG_UNIVERSAL_JAR")" && pwd)/$(basename "$GRUG_UNIVERSAL_JAR")"
fi

# An external host answering badly is worth one more Gradle invocation, and nothing else is: Loom's
# own three attempts all land inside a single transient hiccup on resources.download.minecraft.net
# (#700), ForgeGradle's downloadAssets says to run the task again, and Maven Central or
# libraries.minecraft.net answering 404 for an artifact they serve a moment later fails the
# dependency resolution (#728). A compile error, a test failure or an unrecognised Gradle failure
# would only be delayed by a retry, and a missing artifact is retried exactly once, which is cheap
# next to a job that failed on a hiccup.
#
# The decision is a pure function of the log, so it is unit-tested case by case in
# run_loader_test.py, and it prints the line it matched so the job says why it retried.
#
# "Could not find" is matched only with a full coordinate, `group:artifact:version.`, because a build
# script that names a method that does not exist says "Could not find method foo()" and that is the
# code failing, not a host.
RETRYABLE_FAILURE_RE='net\.fabricmc\.loom\.util\.download\.DownloadException|Failed download after [0-9]+ attempts|Failed to get asset:|Some assets failed to download or validate|Could not find [^ :]+:[^ :]+:[^ :]+\.|Could not resolve all (files|artifacts) for configuration|Could not (GET|HEAD) .https?://'
retryable_failure() {
  grep -m1 -E "$RETRYABLE_FAILURE_RE" "$1" 2>/dev/null
}

# The first attempt and one retry. Never a third: an outage longer than this is not a flake for a
# single job to ride out, and each attempt costs the job its whole build again.
MAX_GRADLE_ATTEMPTS=2

# Runs a Gradle invocation whose output nothing else reads, and once more when it failed fetching
# what the build needs rather than on the code. The log of the attempt that failed is kept beside the
# one that decided the job, so a retry that passes still leaves the hiccup readable.
gradle_with_retry() {
  local log="$1" attempt=1 status reason
  shift
  while :; do
    "$@" 2>&1 | tee "$log"
    status=${PIPESTATUS[0]}
    if [ "$status" -eq 0 ]; then
      return 0
    fi
    if [ "$attempt" -ge "$MAX_GRADLE_ATTEMPTS" ] || ! reason=$(retryable_failure "$log"); then
      return "$status"
    fi
    cp "$log" "${log}.attempt-${attempt}"
    echo "==> Gradle failed fetching what it needs, not on the code: $reason" >&2
    echo "==> Retrying the same Gradle invocation, attempt $((attempt + 1)) of ${MAX_GRADLE_ATTEMPTS}." >&2
    echo "==> The failed attempt's log is ${log#"${REPO_ROOT}/"}.attempt-${attempt}" >&2
    attempt=$((attempt + 1))
  done
}

# Standalone loaders have their own Gradle wrapper and consume core from the repository the root
# build publishes it into, so that publish has to happen before the loader's own build runs, unless
# this run was handed a published copy. GRUG_CORE_MAVEN names that copy: CI publishes core once per
# run and hands the same repository to every job that loads a standalone loader (#735), because the
# artifact does not depend on the active loader. Left unset, as a developer's run leaves it, the
# publish happens here and is the only supported way to build a loader from a checkout.
# A production run builds no loader: the universal jar already holds core.
if [ -f "${LOADER_DIR}/root.gradle" ]; then
  if [ "$PHASE" != production ]; then
    if [ -n "${GRUG_CORE_MAVEN:-}" ]; then
      if [ ! -d "${GRUG_CORE_MAVEN}/net/grug/grug-core" ]; then
        echo "==> ERROR: GRUG_CORE_MAVEN is '${GRUG_CORE_MAVEN}', which holds no net/grug/grug-core." >&2
        exit 1
      fi
      echo "==> Staging the published core from ${GRUG_CORE_MAVEN} into build/maven"
      mkdir -p build
      rm -rf build/maven
      cp -a "${GRUG_CORE_MAVEN}/." build/maven/
      staged_versions=$(ls build/maven/net/grug/grug-core | tr '\n' ' ')
      echo "==> Staged grug-core: ${staged_versions% }"
    else
      echo "==> Publishing core into build/maven"
      gradle_with_retry "${REPO_ROOT}/${LOADER_DIR}/run/ci-publish.log" \
        ./gradlew :core:publishMavenJavaPublicationToGrugBuildRepository "${gradle_args[@]}" || exit 1
    fi
  fi
  cd "$LOADER_DIR"
  task_prefix=""
else
  task_prefix=":loaders:${LOADER}:"
fi

# The build also writes the loader's layer of the universal jar to build/layer/grug-<loader>.jar,
# which CI hands to universal_jar.py.
if [ "$PHASE" = build ]; then
  echo "==> Building $LOADER and its layer of the universal jar (no game launch)"
  gradle_with_retry "${REPO_ROOT}/${LOADER_DIR}/run/ci-build.log" \
    ./gradlew "${task_prefix}build" "${task_prefix}layerJar" -x test "${gradle_args[@]}"
  exit
fi

# One invocation for both: the build tasks come first, so a Gradle failure in them stops the run
# before runClient starts, and runClient follows. -x test is the same exclusion the build phase uses.
run_tasks=("${task_prefix}runClient")
if [ "$PHASE" = build-run ]; then
  run_tasks=("${task_prefix}build" "${task_prefix}layerJar" "${task_prefix}runClient" -x test)
fi

# The tests the run is about to load. A reference run points GRUG_MODS_DIR at a harness-only
# directory, so its count comes from there rather than from every mod in the repository.
#
# GRUG_CI_MODS, a comma separated list of mod directory names, runs only those mods' tests (every
# mod is still loaded). Unset, empty or blank runs all of them. The runner in core reads the same
# variable, and the count has to agree with it, so the names are checked here first: a name that is
# not a mod directory, or an empty entry, stops the script before the game starts instead of passing
# a run that quietly ran less than it was asked to.
MODS_DIR="${GRUG_MODS_DIR:-${REPO_ROOT}/mods}"
GRUG_CI_MODS="${GRUG_CI_MODS:-}"
if [ -n "${GRUG_CI_MODS//[[:space:]]/}" ]; then
  EXPECTED_TESTS=0
  selected_mods=" "
  # The comma appended here keeps read from dropping a last empty entry, such as the one in "a,".
  IFS=, read -r -a requested_mods <<< "${GRUG_CI_MODS},"
  for mod in "${requested_mods[@]}"; do
    mod="${mod#"${mod%%[![:space:]]*}"}"
    mod="${mod%"${mod##*[![:space:]]}"}"
    if [ -z "$mod" ]; then
      echo "==> ERROR: GRUG_CI_MODS has an empty entry, but each has to name a mod: '${GRUG_CI_MODS}'" >&2
      exit 2
    fi
    case "$mod" in
      */* | .*)
        echo "==> ERROR: GRUG_CI_MODS names '${mod}', which is not a mod directory name." >&2
        exit 2
        ;;
    esac
    if [ ! -d "${MODS_DIR}/${mod}" ]; then
      echo "==> ERROR: GRUG_CI_MODS names the mod '${mod}', but ${MODS_DIR} has no such directory. The mods are: $(ls "$MODS_DIR" | tr '\n' ' ')" >&2
      exit 2
    fi
    # A name repeated in the list counts once, as the runner treats it.
    case "$selected_mods" in *" ${mod} "*) continue ;; esac
    selected_mods="${selected_mods}${mod} "
    EXPECTED_TESTS=$((EXPECTED_TESTS + $(find "${MODS_DIR}/${mod}" -name "*-Test.grug" | wc -l | tr -d ' ')))
  done
  echo "==> GRUG_CI_MODS selects the tests of:${selected_mods}(${EXPECTED_TESTS} tests)"
else
  EXPECTED_TESTS=$(find "$MODS_DIR" -name "*-Test.grug" | wc -l | tr -d ' ')
fi
SUCCESS_MSG="[GRUG CI] ALL ${EXPECTED_TESTS} TESTS PASSED"
BOOT_MSG="[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL"
GRUG_LOADED_MSG="[GRUG CI] GRUG LOADED"

if [ "$PHASE" != build ]; then
  if [ ! -f "${REPO_ROOT}/${LOADER_DIR}/test-save.txt" ]; then
    echo "==> ERROR: ${LOADER_DIR}/test-save.txt is missing. Every loader must specify a test save." >&2
    exit 1
  fi

  # Read the target zip name, trimming any whitespace/newlines
  SAVE_ZIP=$(tr -d ' \n\r' < "${REPO_ROOT}/${LOADER_DIR}/test-save.txt")
  
  if [ -z "$SAVE_ZIP" ] || [ ! -f "${REPO_ROOT}/test-saves/${SAVE_ZIP}" ]; then
    echo "==> ERROR: ${LOADER_DIR}/test-save.txt specifies '${SAVE_ZIP}', but the zip was not found in test-saves/." >&2
    exit 1
  fi

  # Replace the run's saves with a pristine copy every launch. The game autosaves into run/, so
  # reusing the directory lets the world and the player's health/position drift between runs; a
  # mutated world is what made the screenshot test flaky.
  echo "==> Replacing ${GAME_DIR#"${REPO_ROOT}/"}/saves with a fresh copy of test-saves/${SAVE_ZIP}"
  rm -rf "${GAME_DIR}/saves"
  mkdir -p "${GAME_DIR}/saves/"
  # Git Bash has no unzip, so the save is unpacked by Python, which every runner has. On Windows
  # python3 can be a Microsoft Store stub that opens the store instead of running anything.
  PYTHON=python3
  [ "$WINDOWS" = true ] && PYTHON=python
  "$PYTHON" -m zipfile -e "${REPO_ROOT}/test-saves/${SAVE_ZIP}" "${GAME_DIR}/saves/"
fi

# Forge's early load window is cosmetic and can only hurt a headless CI run: it creates its GL window
# from a scheduled task with a hard 10-second deadline, and when it misses that deadline it aborts
# with "Failed to initialize graphics window" and blocks forever in a TinyFileDialogs message box
# (there is no desktop), so the game never reaches the title screen. That is the Forge CI flake. The
# switch lives in the game's config file rather than a system property, and run/ is not committed, so
# write it here before the game reads it. earlyWindowControl=false makes Forge use its DummyProvider,
# which just means there is no loading screen.
if [ "$PHASE" != build ] && grep -q "net.minecraftforge.gradle" "${LOADER_DIR}/build.gradle" 2>/dev/null; then
  FML_CONFIG="${GAME_DIR}/config/fml.toml"
  mkdir -p "$(dirname "$FML_CONFIG")"
  if [ -f "$FML_CONFIG" ] && grep -q "^earlyWindowControl" "$FML_CONFIG"; then
    sed -i 's/^earlyWindowControl.*/earlyWindowControl = false/' "$FML_CONFIG"
  elif [ -f "$FML_CONFIG" ]; then
    printf '\n#Disable the cosmetic early load window (CI has no desktop to fall back to).\nearlyWindowControl = false\n' >> "$FML_CONFIG"
  else
    printf '#Disable the cosmetic early load window (CI has no desktop to fall back to).\nearlyWindowControl = false\n' > "$FML_CONFIG"
  fi
fi

timeout_secs="${GRUG_CI_RUN_TIMEOUT:-120}"
# Each pass after the first adds this many seconds, so the watch scales with GRUG_CI_REPEATS.
timeout_secs=$((timeout_secs + ${GRUG_CI_RUN_TIMEOUT_PER_EXTRA_REPEAT:-0} * (${GRUG_CI_REPEATS:-1} - 1)))
# A production launch installs the game and its mod loader before the game exists: the launcher
# downloads the client, its libraries and its assets, and Forge additionally fetches its installer
# libraries and generates MCP data. All of that is network-bound, and a slow network there must not
# spend the game's budget, which is what made a slow install read as a game that never started
# (#725). The install has its own limit, the same 600s guard against a hang that a build-run's build
# gets, while the game's own limit above is unchanged.
install_timeout_secs="${GRUG_CI_INSTALL_TIMEOUT:-600}"
# Fabric Loader runs the Alpha and Beta init entrypoints, and the block registration they trigger, on
# the applet's AWT thread, so a mod that fails there leaves the game thread waiting rather than
# crashing it. A broken grug invariant (Grug.fatal) is a crash wherever it is thrown.
crash_re='Uncaught exception in thread "Minecraft main thread"|Exception in thread "Minecraft main thread"|Game crashed! Crash report saved to|Could not execute entrypoint stage|Broken grug invariant'
# A Rust panic in grug-rs, which aborts the process rather than failing a test. Every Rust panic
# location ends in .rs:<line>:<col>, so match that rather than the bare words. See
# report-native-abort.sh for why that is worth its own status.
ABORT_RE='panicked at .*\.rs:[0-9]+:[0-9]+'

gradle_pid=""
tail_pid=""

cleanup() {
  [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null || true
  [ -n "$gradle_pid" ] || return 0
  kill -TERM -- "-${gradle_pid}" 2>/dev/null || true
  for _ in $(seq 1 10); do
    kill -0 "$gradle_pid" 2>/dev/null || break
    sleep 1
  done
  kill -KILL -- "-${gradle_pid}" 2>/dev/null || true
  # Git Bash has no process groups to signal, and the game is a Windows process it cannot reach
  # through its own pid, so the runner's Java processes are ended by name.
  if [ "$WINDOWS" = true ]; then taskkill //F //T //IM java.exe >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$PHASE" = build-run ]; then
  echo "==> Building and launching $LOADER in one Gradle invocation, waiting up to ${timeout_secs}s from the game's start for the tests to pass..."
elif [ "$PHASE" = production ]; then
  echo "==> Installing $LOADER, waiting up to ${install_timeout_secs}s for the install and then up to ${timeout_secs}s for the tests to pass..."
else
  echo "==> Launching $LOADER, waiting up to ${timeout_secs}s for the tests to pass..."
fi
SETSID=setsid
[ "$WINDOWS" = true ] && SETSID=

# The task is :runClient in a standalone loader's own build, which has no task prefix.
game_start_re="^> Task ${task_prefix:-:}runClient( |\$)"

# The end of a production install, which is where the game's own limit starts. portablemc 5.0.6
# prints "Launched" once it has spawned the game process, and only then: its install events end
# with "Loader installed, retrying to start the game", a word "Launched" does not contain. A loader
# whose production launch installs nothing (1.2.5-forge runs the game from its Gradle task) prints
# the harness line instead, in launch-production.sh, so its limit starts at the launch as before.
production_game_start_re='Launched|\[GRUG CI\] GAME LAUNCHING'

# One attempt, and one more when the first failed fetching what the build needs rather than on the
# code. The game
# never started in that case, so repeating the whole invocation repeats nothing the game did; any
# other outcome ends the job here, with the log of the attempt that decided it.
attempt=1
while :; do
  : > "$LOG_FILE"

  # Follow the log in the background so we get real-time output in the CI console
  tail -f "$LOG_FILE" &
  tail_pid=$!

  # setsid gives the launch its own process group so cleanup can kill Gradle and the game together.
  # Windows has none, and cleanup ends its Java processes by name instead.
  if [ "$PHASE" = production ]; then
    $SETSID "${REPO_ROOT}/.github/scripts/launch-production.sh" "$LOADER" "$GAME_DIR" "$UNIVERSAL_JAR" >"$LOG_FILE" 2>&1 &
  else
    $SETSID ./gradlew "${run_tasks[@]}" "${gradle_args[@]}" --no-daemon >"$LOG_FILE" 2>&1 &
  fi
  gradle_pid=$!

  # Where the game's own output starts in the log, as a byte offset for tail -c. A run phase starts
  # with the game, so that is the whole log. In build-run the log first holds the build, whose output
  # (a compiler warning that mentions a failure, say) must not be read as the game's, so the game is
  # only looked for after the line Gradle prints when it starts the runClient task, and the time limit
  # starts there too: a slow build never eats the game's budget. The build has a limit of its own, a
  # guard against a hang that is well inside the job's. A production launch starts the same way with
  # its installer's output, so its game is looked for at the line that reports the launch, and that
  # is where its limit starts; the install's own limit runs before it.
  log_offset=1
  game_started=true
  deadline=$((SECONDS + timeout_secs))
  if [ "$PHASE" = build-run ]; then
    game_started=false
    deadline=$((SECONDS + ${GRUG_CI_BUILD_TIMEOUT:-600}))
  elif [ "$PHASE" = production ]; then
    game_started=false
    deadline=$((SECONDS + install_timeout_secs))
  fi

  # grep over the game's part of the log.
  game_log_grep() {
    if [ "$log_offset" -le 1 ]; then
      grep "$@" "$LOG_FILE"
    else
      grep "$@" < <(tail -c "+${log_offset}" "$LOG_FILE")
    fi
  }

  status=timeout
  reached_title=false
  while [ "$SECONDS" -lt "$deadline" ]; do
    # Sampled before the log is read, never after: a process that dies between the reads and a
    # liveness test written last would leave its final line unread and be reported as a bare exit.
    # A game that was already dead here wrote everything it ever will before this point, so the
    # reads below see all of it. A boot-only game prints GRUG LOADED and exits at once, so it hit
    # exactly that window (#597).
    alive=true
    kill -0 "$gradle_pid" 2>/dev/null || alive=false

    if [ "$game_started" = false ]; then
      if [ "$PHASE" = production ]; then
        # The launcher is installing the game and its mod loader. Once it reports the launch, the
        # game's own limit replaces the install's, and the game's checks below run from there.
        if grep -q -E "$production_game_start_re" "$LOG_FILE"; then
          game_started=true
          deadline=$((SECONDS + timeout_secs))
          echo "==> The install of $LOADER finished and its game starts. Waiting up to ${timeout_secs}s for the tests to pass..."
        elif [ "$alive" = false ]; then
          # The launcher ended before it reported a launch, so the install failed and its log says
          # why. That is an exit, the same classification a failed install had before it had a
          # limit of its own.
          status=exited
          break
        else
          sleep 1
          continue
        fi
      else
        start_line=$(grep -b -m1 -E "$game_start_re" "$LOG_FILE" || true)
        if [ -n "$start_line" ]; then
          game_started=true
          log_offset=$(( ${start_line%%:*} + 1 ))
          deadline=$((SECONDS + timeout_secs))
          echo "==> The build of $LOADER finished and its game starts. Waiting up to ${timeout_secs}s for the tests to pass..."
        elif [ "$alive" = false ]; then
          # Gradle ended before it started the game, so the build failed. A compile error is that, and
          # the log above says which; it is not a game that exited.
          status=buildfailed
          break
        else
          sleep 1
          continue
        fi
      fi
    fi

    if [ "$reached_title" = false ] && grep -q -F "$BOOT_MSG" "$LOG_FILE"; then
      echo "==> $LOADER reached the title screen. Waiting for tests to complete..."
      reached_title=true
    fi

    if grep -q -F "$SUCCESS_MSG" "$LOG_FILE"; then status=success; break; fi
    # A boot-only run is done once grug has loaded: the native loaded, the VM started and the mods
    # compiled, which is everything specific to grug on the platform. A crash before that is still
    # caught by the checks below.
    if [ -n "${GRUG_CI_BOOT_ONLY:-}" ] && grep -q -F "$GRUG_LOADED_MSG" "$LOG_FILE"; then status=success; break; fi
    if game_log_grep -q "FAIL "; then status=failed; break; fi
    if game_log_grep -q -E "$crash_re"; then status=crashed; break; fi
    # Checked before the liveness test below, because a Rust panic is written to the log a moment
    # before the process dies: on the poll that sees both, the liveness test would report a bare
    # "exited" and the panic, which is the whole diagnosis, would go unread.
    if game_log_grep -q -E "$ABORT_RE"; then status=aborted; break; fi
    if [ "$alive" = false ]; then status=exited; break; fi
    sleep 1
  done

  # Kill tail manually so it doesn't leak into the next attempt
  [ -n "$tail_pid" ] && kill "$tail_pid" 2>/dev/null || true
  tail_pid=""

  if [ "$attempt" -ge "$MAX_GRADLE_ATTEMPTS" ]; then
    break
  fi
  # Only a failure before the game started can be a download: buildfailed is Gradle ending before
  # runClient, and exited is the same in a run phase, which has no build to tell it from a game that
  # exited. A test failure, a crash, an abort and a timeout are never retried. The production phase
  # never retries either: its launcher downloads with curl's own retries, its log holds no Gradle
  # runClient line to tell a failed install from a game that ran, and a second installed launch is
  # the most expensive thing this script could repeat.
  if [ "$PHASE" = production ] || { [ "$status" != buildfailed ] && [ "$status" != exited ]; }; then
    break
  fi
  # The run phase reads the whole log as the game's, so a line in it that looks like a download
  # failure could be the game's own. Gradle's runClient line is what says the game started, and a
  # game that ran is never repeated.
  if [ "$status" = exited ] && grep -q -E "$game_start_re" "$LOG_FILE"; then
    break
  fi
  if ! reason=$(retryable_failure "$LOG_FILE"); then
    break
  fi
  cp "$LOG_FILE" "${LOG_FILE}.attempt-${attempt}"
  echo "==> Gradle failed fetching what it needs, not on the code: $reason" >&2
  echo "==> Retrying $LOADER, attempt $((attempt + 1)) of ${MAX_GRADLE_ATTEMPTS}." >&2
  echo "==> The failed attempt's log is ${LOG_FILE#"${REPO_ROOT}/"}.attempt-${attempt}" >&2
  attempt=$((attempt + 1))
done

# Prints the grug-rs abort report for the log, if it holds one, and puts the same text in the job
# summary so the panic is readable from the checks list instead of only from the raw log.
report_native_abort() {
  local report
  report=$("${REPO_ROOT}/.github/scripts/report-native-abort.sh" "$LOG_FILE" "$1" || true)
  [ -n "$report" ] || return 0

  printf '%s\n' "$report" >&2

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### $LOADER aborted inside grug-rs"
      echo
      echo 'A Rust panic in grug-rs cannot unwind out of an \`extern "C"\` frame, so it aborts the'
      echo 'JVM rather than failing a test.'
      echo
      echo '```'
      printf '%s\n' "$report"
      echo '```'
    } >>"$GITHUB_STEP_SUMMARY"
  fi
}

case "$status" in
  success)
    if [ -n "${GRUG_CI_BOOT_ONLY:-}" ]; then
      echo "==> $LOADER loaded grug. The tests were not run: this is a boot-only run."
    else
      echo "==> $LOADER successfully ran and passed all tests."
    fi
    ;;
  failed)
    echo "==> $LOADER tests failed." >&2
    game_log_grep -n -B2 -A10 "FAIL " >&2 || true
    # The loop classifies on the first FAIL line, so a panic that lands after it, during shutdown
    # for example, would otherwise be reported as a plain test failure and never surfaced.
    report_native_abort ""
    ;;
  crashed)
    if [ "$reached_title" = true ]; then
      echo "==> $LOADER's game thread crashed AFTER reaching the title screen." >&2
    else
      echo "==> $LOADER's game thread crashed BEFORE reaching the title screen." >&2
    fi
    echo "==> First fatal error in $LOG_FILE:" >&2
    game_log_grep -n -m1 -B2 -A14 -E "$crash_re" >&2 || true
    ;;
  aborted)
    # The poll loop matched a panic that aborts the process, so this normally returns at once. Stop
    # at the loop's deadline anyway: a log string that only looks like a panic must not make the
    # script wait past the timeout it promises.
    while kill -0 "$gradle_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
      sleep 1
    done
    exit_code=""
    if ! kill -0 "$gradle_pid" 2>/dev/null; then
      wait "$gradle_pid"
      exit_code=$?
    fi
    if [ "$reached_title" = true ]; then
      echo "==> $LOADER's game process aborted inside grug-rs after reaching the title screen." >&2
    else
      echo "==> $LOADER's game process aborted inside grug-rs before reaching the title screen." >&2
    fi
    report_native_abort "$exit_code"
    ;;
  buildfailed)
    wait "$gradle_pid"
    exit_code=$?
    echo "==> Gradle failed to build $LOADER (exit code $exit_code), so the game was never launched." >&2
    # The compiler's complaints, then Gradle's own account of what went wrong.
    grep -n -m20 "error:" "$LOG_FILE" >&2 || true
    grep -n -m1 -A12 "^\* What went wrong:" "$LOG_FILE" >&2 || true
    echo "==> See $LOG_FILE" >&2
    ;;
  exited)
    wait "$gradle_pid"
    exit_code=$?
    if [ "$reached_title" = true ]; then
      echo "==> $LOADER's build/game process exited (exit code $exit_code) after reaching the title screen but before passing tests." >&2
    else
      echo "==> $LOADER's build/game process exited (exit code $exit_code) before reaching the title screen." >&2
    fi
    # A panic written to the log after the loop's own check still lands here, so the report is
    # attempted here too rather than only in the aborted branch above.
    report_native_abort "$exit_code"
    echo "==> See $LOG_FILE" >&2
    ;;
  timeout)
    if [ "$PHASE" = production ] && [ "$game_started" = false ]; then
      # The install never reported a launch, so the game has not started and its own limit has not
      # been spent. Saying it timed out "waiting to reach the title screen" would blame the game
      # for the installer's network.
      echo "==> Timed out after ${install_timeout_secs}s installing $LOADER, before its game started." >&2
      echo "==> The game's own ${timeout_secs}s limit had not started." >&2
      echo "==> Check $LOG_FILE, and raise GRUG_CI_INSTALL_TIMEOUT if the installer was still making progress." >&2
    elif [ "$game_started" = false ]; then
      echo "==> Timed out after ${GRUG_CI_BUILD_TIMEOUT:-600}s building $LOADER, before its game started." >&2
      echo "==> Check $LOG_FILE, and raise GRUG_CI_BUILD_TIMEOUT if it was still making progress." >&2
    elif [ "$reached_title" = true ]; then
      echo "==> Timed out after ${timeout_secs}s waiting for $LOADER tests to pass (but it DID reach the title screen)." >&2
      echo "==> Check $LOG_FILE, and raise GRUG_CI_RUN_TIMEOUT if it was still making progress." >&2
    else
      echo "==> Timed out after ${timeout_secs}s waiting for $LOADER to reach the title screen." >&2
      echo "==> Check $LOG_FILE, and raise GRUG_CI_RUN_TIMEOUT if it was still making progress." >&2
    fi

    if command -v jcmd >/dev/null 2>&1; then
      for jp in $(jcmd -l 2>/dev/null | awk '!/[Gg]radle|JCmd/ {print $1}'); do
        echo "==> Thread dump of JVM $jp (game main thread and AWT event thread):" >&2
        jcmd "$jp" Thread.print 2>&1 \
          | grep -A20 -E '^"(Minecraft main thread|AWT-EventQueue-0)"' >&2 || true
      done
    fi
    ;;
esac

[ "$status" = success ]
