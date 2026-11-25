#!/usr/bin/env bash
# The failures worth one more Gradle invocation, and how many attempts a leg may make.
#
# Sourced, never run, by run-loader.sh and by run-multiplayer.sh: a loader job's own build and the
# joined client build of a dedicated leg fail the same way on the same hosts, so the decision (and
# the comment below explaining it) lives in one place rather than in two copies that can drift
# (#700, #1702).

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
#
# "Could not resolve dependency" is ForgeGradle's own message when MinecraftUserRepo's manual
# download of the userdev artifact came back empty, which is one failed fetch from the Forge maven
# rather than a build the code broke, so it gets the same retry (#1173). The coordinate after it
# keeps a build script's own message from matching, as the "Could not find" pattern does.
#
# portablemc, which installs the game in a production launch, prints its own batch failure as
# "[ ERRO ] Failed to download 1 out of 3934 entries" and then the URL and reason on the following
# lines. It retries a timeout or a decode error but not a connection reset, so one reset anywhere in
# the batch ends the install. The line is the retryable part; the exited case prints the URL beside
# it (#1149).
#
# The production launch also fetches metadata itself, with `curl --fail`, for the Ornithe, Babric and
# Modrinth versions and profiles, and curl prints "curl: (22) The requested URL returned error: 503"
# on stderr when such a host answers badly. That fetch fails before the game exists, so it earns the
# same one retry. Only a 5xx is matched: a 404 is the host's final answer, and repeating it would
# only delay the job.
RETRYABLE_FAILURE_RE='\[ ERRO \] Failed to download|curl: .*The requested URL returned error: 5[0-9][0-9]|net\.fabricmc\.loom\.util\.download\.DownloadException|Failed download after [0-9]+ attempts|Failed to get asset:|Some assets failed to download or validate|Could not find [^ :]+:[^ :]+:[^ :]+\.|Could not resolve all (files|artifacts) for configuration|Could not resolve dependency: [^ :]+:[^ :]+:[^ :]+|Could not (GET|HEAD) .https?://'
retryable_failure() {
  grep -m1 -E "$RETRYABLE_FAILURE_RE" "$1" 2>/dev/null
}

# The first attempt and one retry. Never a third: an outage longer than this is not a flake for a
# single job to ride out, and each attempt repeats the job's whole build or, in a production launch,
# its whole install. run-multiplayer.sh's client build is held to the same two attempts, and its
# dedicated server build to the same two attempts inside one build window: the server's retry spends
# what is left of the first attempt's window rather than asking for a second one, as run-loader.sh's
# own retry does, so a leg cannot outlast the job on retries either.
MAX_GRADLE_ATTEMPTS=2
