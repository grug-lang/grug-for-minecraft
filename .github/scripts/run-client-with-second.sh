#!/usr/bin/env bash
# .github/scripts/run-client-with-second.sh <the java command Gradle's runClient would run>
#
# The second player of the 1.20.6 dedicated-server leg (#1027). Gradle runs one runClient, so
# loaders/1.20.6-forge/build.gradle hands this wrapper the exact command that task would have run.
# The wrapper starts the second client first, on its own X display and game directory, waits for it
# to be in the world, and only then runs the client Gradle asked for.
#
# That order is what makes the test deterministic: the second player joins first, so it is the first
# entry in the server's player list, but it holds its START until run-multiplayer.sh writes the file
# it watches. The client Gradle launches therefore becomes the starter, and the second client's real
# START arrives while a starter exists, which the server has to ignore. From then on the second
# client answers the first request ids, which it was never asked; the starter's answers are the ones
# that must complete the calls.
#
# With GRUG_SECOND_CLIENT_DIR unset this is exactly the client Gradle asked for, so a local run or
# another leg is unchanged. It is used only by the 1.20.6 leg.
set -uo pipefail

java_bin="${JAVA_HOME:+${JAVA_HOME}/bin/java}"
java_bin="${java_bin:-java}"

if [ -z "${GRUG_SECOND_CLIENT_DIR:-}" ]; then
  exec "$java_bin" "$@"
fi

second_dir="$GRUG_SECOND_CLIENT_DIR"
second_log="${second_dir}/second-client-launch.log"
mkdir -p "$second_dir"
: > "$second_log"

echo "==> Starting the second client on ${GRUG_SECOND_CLIENT_DISPLAY:?} in ${second_dir}"
(
  cd "$second_dir" || exit 1
  export DISPLAY="${GRUG_SECOND_CLIENT_DISPLAY}"
  # The start is held until the run script says the server accepted the first one, and the answers
  # this client sends are the stranger's. Neither variable reaches the client Gradle asked for.
  export GRUG_CI_CONTROL_START_FILE="${GRUG_SECOND_CLIENT_START_FILE:?}"
  export GRUG_CI_CONTROL_STRANGER_ANSWER=1
  # Forge's dev client defaults to the name Dev, and an offline-mode server kicks the player it
  # already has when the same name joins again, so the second player needs a name of its own. The
  # command Gradle gives this wrapper carries no username of its own.
  setsid "$java_bin" "$@" --username SecondPlayer >>"$second_log" 2>&1 &
  echo $! > "${second_dir}/second-client.pid"
)
second_pid="$(cat "${second_dir}/second-client.pid" 2>/dev/null || true)"

# The client says this once it is in the world and its own chunks have arrived, which is the point
# the server can accept a player from. A client that never gets there must not leave the starter
# waiting forever, so the wait has a deadline of its own.
deadline=$((SECONDS + ${GRUG_CI_SECOND_JOIN_TIMEOUT:-240}))
while :; do
  if grep -qF '[GRUG MULTIPLAYER] the second client is in the world' "$second_log" 2>/dev/null; then
    echo "==> The second client joined; starting the client Gradle asked for."
    break
  fi
  if [ -n "$second_pid" ] && ! kill -0 "$second_pid" 2>/dev/null; then
    echo "==> The second client exited before it joined: ${second_log#"$PWD"/}" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "==> The second client was not in the world after ${GRUG_CI_SECOND_JOIN_TIMEOUT:-240}s: ${second_log#"$PWD"/}" >&2
    exit 1
  fi
  sleep 1
done

exec "$java_bin" "$@"
