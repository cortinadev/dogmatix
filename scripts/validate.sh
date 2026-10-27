#!/usr/bin/env bash
# validate.sh - run Dogmatix's validation steps and write a summary report.
#
# Usage (from anywhere; the script works on the repository it lives in):
#   scripts/validate.sh                         # every step, in order
#   scripts/validate.sh --only unit,lint,build  # just these steps
#   scripts/validate.sh --skip release,apkcheck # everything but these
#   scripts/validate.sh --instrumented          # also run instrumented tests on any device
#   DOGMATIX_DOCKER=1 scripts/validate.sh ...   # run the Gradle steps inside Docker
#
# Steps, always run in this order (STEPS is a comma-separated list of these names):
#   changelog     CHANGELOG.md has a "## [<versionName>] - YYYY-MM-DD" section with at least one
#                 entry under "### Added", "### Changed" or "### Fixed". versionName is read from
#                 app/build.gradle.kts. Pure shell, no Gradle.
#   unit          JVM unit tests: testDebugUnitTest.
#   lint          Android lint on the debug variant: lintDebug. It fails only on errors that are
#                 not listed in app/lint-baseline.xml (warnings never fail it).
#   build         Debug APK and instrumented-test APK: assembleDebug assembleDebugAndroidTest.
#   release       Release APK (R8): assembleRelease. Without keystore/dogmatix-release.jks and the
#                 RELEASE_* values in .env the APK is signed with the debug key; apkcheck says so.
#   apkcheck      scripts/release-check.sh --pre-tag on the release APK, copied first to
#                 build/validate/dogmatix-<versionName>.apk so the checksum files carry the name
#                 the release asset will have.
#   instrumented  Instrumented tests: connectedDebugAndroidTest. Without --instrumented it only
#                 runs when every device adb sees is an emulator, because the test run installs
#                 the .debug app over whatever is on the device. With --instrumented it runs on
#                 whatever is connected (ANDROID_SERIAL limits it to one device). The .debug
#                 app is always left installed afterwards (Gradle would otherwise uninstall it and
#                 wipe its data); only the test APK (com.cortinadev.dogmatix.debug.test) is
#                 removed.
#
# Options:
#   --only STEPS      run only these steps
#   --skip STEPS      skip these steps
#   --instrumented    run the instrumented step whatever device is connected
#   --fail-fast       stop at the first failing step (default: run them all and report)
#   --list            print the step names and exit
#   -h, --help        print this help and exit
#
# Environment:
#   DOGMATIX_DOCKER=1             run each Gradle step in a throw-away container of the
#                                 dogmatix-build image (docker/Dockerfile) instead of the local
#                                 JDK/SDK. Without it Gradle runs natively (CI).
#   DOGMATIX_DOCKER_IMAGE         image name (default: dogmatix-build)
#   DOGMATIX_GRADLE_VOLUME        Docker volume mounted as ~/.gradle (default: dogmatix-gradle)
#   DOGMATIX_DOCKER_MEMORY        container memory limit (default: 3300m)
#   DOGMATIX_DOCKER_GRADLE_ARGS   Gradle flags used only in Docker (default:
#                                 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx1700m
#                                 -Pkotlin.compiler.execution.strategy=in-process", so a build
#                                 fits in 3.3 GB)
#   DOGMATIX_ANDROID_VOLUME       Docker only: volume mounted as /root/.android in every Gradle
#                                 step (debug keystore, adb keys). Without it each container makes
#                                 a new debug key, so its debug APK will not "adb install -r" over
#                                 one signed elsewhere. Default: none (dogmatix-android is the
#                                 volume of the dogmatix-adb container in CLAUDE.md).
#   DOGMATIX_ADB_CONNECT          Docker only: host:port to "adb connect" inside the container
#                                 before the instrumented step (the container has its own adb).
#   DOGMATIX_GRADLE_ARGS          extra flags for every Gradle call, in both modes
#   DOGMATIX_RELEASE_SIGNER       expected release signer SHA-256, passed to release-check.sh
#   ANDROID_SERIAL                limits the instrumented step to one device
#
# Output:
#   build/validate/report.txt     summary: step, result, duration, details; test counts come from
#                                 app/build/test-results/testDebugUnitTest/*.xml and lint counts
#                                 from app/build/reports/lint-results-debug.xml
#   build/validate/<step>.log     full output of each step
#   Exit code 0 when every selected step passed (or was skipped), 1 when one failed, 2 on a
#   usage or environment error.
#
# Requirements: bash 3.2+, awk, sed, grep. Native mode: JDK 17 and an Android SDK
# (ANDROID_HOME). Docker mode: docker and the dogmatix-build image
# (docker build -t dogmatix-build docker).
# Gradle always runs with --no-daemon --no-watch-fs.

set -euo pipefail

ALL_STEPS="changelog unit lint build release apkcheck instrumented"

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
OUT_DIR="$ROOT/build/validate"
REPORT="$OUT_DIR/report.txt"

usage() { sed -n '2,/^$/{s/^# \{0,1\}//;p;}' "$0"; }
die() { echo "validate.sh: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------------------------
ONLY=""
SKIP=""
FORCE_INSTRUMENTED=0
FAIL_FAST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only) [ $# -ge 2 ] || die "--only needs a list of steps"; ONLY="$2"; shift 2 ;;
    --only=*) ONLY="${1#--only=}"; shift ;;
    --skip) [ $# -ge 2 ] || die "--skip needs a list of steps"; SKIP="$2"; shift 2 ;;
    --skip=*) SKIP="${1#--skip=}"; shift ;;
    --instrumented) FORCE_INSTRUMENTED=1; shift ;;
    --fail-fast) FAIL_FAST=1; shift ;;
    --list) echo "$ALL_STEPS"; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

contains() { # contains "<space separated list>" <word>
  case " $1 " in *" $2 "*) return 0 ;; *) return 1 ;; esac
}
normalise_list() { # "a,b c" -> "a b c", checking every name
  local list item out=""
  list=$(printf '%s' "$1" | tr ',' ' ')
  for item in $list; do
    contains "$ALL_STEPS" "$item" || die "unknown step '$item' (steps: $ALL_STEPS)"
    out="$out $item"
  done
  printf '%s' "$out"
}
ONLY=$(normalise_list "$ONLY")
SKIP=$(normalise_list "$SKIP")

selected() {
  local step=$1
  if [ "$step" = instrumented ] && [ "$FORCE_INSTRUMENTED" = 1 ] && ! contains "$SKIP" instrumented; then
    return 0
  fi
  if [ -n "$ONLY" ] && ! contains "$ONLY" "$step"; then return 1; fi
  if contains "$SKIP" "$step"; then return 1; fi
  return 0
}

# ---- environment ------------------------------------------------------------------------------
DOCKER_MODE=0
[ "${DOGMATIX_DOCKER:-0}" = 1 ] && DOCKER_MODE=1
IMAGE="${DOGMATIX_DOCKER_IMAGE:-dogmatix-build}"
GRADLE_VOLUME="${DOGMATIX_GRADLE_VOLUME:-dogmatix-gradle}"
DOCKER_MEMORY="${DOGMATIX_DOCKER_MEMORY:-3300m}"
DOCKER_GRADLE_ARGS_STR="${DOGMATIX_DOCKER_GRADLE_ARGS---max-workers=2 -Dorg.gradle.jvmargs=-Xmx1700m -Pkotlin.compiler.execution.strategy=in-process}"
GRADLE_ARGS_STR="${DOGMATIX_GRADLE_ARGS:-}"
DOCKER_GRADLE_ARGS=()
GRADLE_ARGS=()
ANDROID_MOUNT=()
[ -n "${DOGMATIX_ANDROID_VOLUME:-}" ] && ANDROID_MOUNT=(-v "${DOGMATIX_ANDROID_VOLUME}:/root/.android")
[ -n "$DOCKER_GRADLE_ARGS_STR" ] && read -r -a DOCKER_GRADLE_ARGS <<< "$DOCKER_GRADLE_ARGS_STR"
[ -n "$GRADLE_ARGS_STR" ] && read -r -a GRADLE_ARGS <<< "$GRADLE_ARGS_STR"

GRADLE_FILE="$ROOT/app/build.gradle.kts"
[ -f "$GRADLE_FILE" ] || die "app/build.gradle.kts not found under $ROOT"
VERSION_NAME=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -n 1)
VERSION_CODE=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n 1)
[ -n "$VERSION_NAME" ] || die "could not read versionName from app/build.gradle.kts"

needs_gradle=0
for s in unit lint build release instrumented; do
  if selected "$s"; then needs_gradle=1; fi
done
if [ "$needs_gradle" = 1 ]; then
  if [ "$DOCKER_MODE" = 1 ]; then
    command -v docker >/dev/null 2>&1 || die "DOGMATIX_DOCKER=1 but docker is not installed"
    docker image inspect "$IMAGE" >/dev/null 2>&1 \
      || die "Docker image '$IMAGE' not found (docker build -t dogmatix-build docker)"
  else
    if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
      die "no JDK found (JAVA_HOME / java). Install JDK 17 or run with DOGMATIX_DOCKER=1"
    fi
  fi
fi

mkdir -p "$OUT_DIR"

# ---- helpers ----------------------------------------------------------------------------------
fmt_secs() { # 75 -> 1m15s
  local s=$1
  if [ "$s" -ge 60 ]; then printf '%dm%02ds' $((s / 60)) $((s % 60)); else printf '%ds' "$s"; fi
}

STEP_NAMES=()
STEP_RESULTS=()
STEP_TIMES=()
STEP_DETAILS=()
EXTRA_SECTIONS=""
FAILED=0
record() { # record <step> <PASS|FAIL|SKIP> <seconds or -> <details>
  STEP_NAMES+=("$1")
  STEP_RESULTS+=("$2")
  if [ "$3" = "-" ]; then STEP_TIMES+=("-"); else STEP_TIMES+=("$(fmt_secs "$3")"); fi
  STEP_DETAILS+=("$4")
  [ "$2" = FAIL ] && FAILED=1
  printf '\n[validate] %-12s %s  %s\n' "$1" "$2" "$4"
  return 0
}

# Runs a Gradle invocation, natively or in Docker, logging to build/validate/<step>.log.
# Usage: gradle_run <step> <extra docker flags as one string, may be empty> <gradle args...>
gradle_run() {
  local step=$1 docker_extra=$2 log rc
  shift 2
  log="$OUT_DIR/$step.log"
  local cmd=()
  if [ "$DOCKER_MODE" = 1 ]; then
    local extra=()
    [ -n "$docker_extra" ] && read -r -a extra <<< "$docker_extra"
    cmd=(docker run --rm --memory="$DOCKER_MEMORY" --name "dogmatix-validate-$step-$$"
         -v "$ROOT":/workspace -v "$GRADLE_VOLUME":/root/.gradle -w /workspace
         ${ANDROID_MOUNT[@]+"${ANDROID_MOUNT[@]}"} ${extra[@]+"${extra[@]}"} "$IMAGE")
  fi
  cmd+=(./gradlew --no-daemon --no-watch-fs)
  [ "$DOCKER_MODE" = 1 ] && cmd+=(${DOCKER_GRADLE_ARGS[@]+"${DOCKER_GRADLE_ARGS[@]}"})
  cmd+=(${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} "$@")
  echo "[validate] $step: ${cmd[*]}" | tee "$log"
  set +e
  "${cmd[@]}" 2>&1 | tee -a "$log"
  rc=${PIPESTATUS[0]}
  set -e
  return "$rc"
}

# Same as gradle_run but the container first runs "adb connect" (instrumented step in Docker).
gradle_run_with_adb() {
  local step=$1 docker_extra=$2 log rc
  shift 2
  log="$OUT_DIR/$step.log"
  local extra=()
  [ -n "$docker_extra" ] && read -r -a extra <<< "$docker_extra"
  local gradle=(./gradlew --no-daemon --no-watch-fs
                ${DOCKER_GRADLE_ARGS[@]+"${DOCKER_GRADLE_ARGS[@]}"}
                ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} "$@")
  local inner="adb start-server >/dev/null 2>&1; adb connect \"\$DOGMATIX_ADB_CONNECT\" && adb wait-for-device || exit 1; ${gradle[*]}; rc=\$?; adb uninstall $TEST_PACKAGE >/dev/null 2>&1; exit \$rc"
  local cmd=(docker run --rm --memory="$DOCKER_MEMORY" --name "dogmatix-validate-$step-$$"
             -e DOGMATIX_ADB_CONNECT="${DOGMATIX_ADB_CONNECT}" ${ANDROID_SERIAL:+-e ANDROID_SERIAL="$ANDROID_SERIAL"}
             -v "$ROOT":/workspace -v "$GRADLE_VOLUME":/root/.gradle -w /workspace
             ${ANDROID_MOUNT[@]+"${ANDROID_MOUNT[@]}"} ${extra[@]+"${extra[@]}"} "$IMAGE" bash -c "$inner")
  echo "[validate] $step: ${cmd[*]}" | tee "$log"
  set +e
  "${cmd[@]}" 2>&1 | tee -a "$log"
  rc=${PIPESTATUS[0]}
  set -e
  return "$rc"
}

xml_attr() { # xml_attr <attr> <text>: value of attr="..." in text
  printf '%s' "$2" | sed -n "s/.* $1=\"\([^\"]*\)\".*/\1/p" | head -n 1
}

unit_summary() {
  local dir="$ROOT/app/build/test-results/testDebugUnitTest" f line t fl er sk
  local tests=0 failures=0 errors=0 skipped=0 classes=0 failing=""
  for f in "$dir"/*.xml; do
    [ -f "$f" ] || continue
    line=$(grep -m 1 -o '<testsuite [^>]*>' "$f" || true)
    [ -n "$line" ] || continue
    t=$(xml_attr tests "$line"); fl=$(xml_attr failures "$line")
    er=$(xml_attr errors "$line"); sk=$(xml_attr skipped "$line")
    classes=$((classes + 1))
    tests=$((tests + ${t:-0})); failures=$((failures + ${fl:-0}))
    errors=$((errors + ${er:-0})); skipped=$((skipped + ${sk:-0}))
    if [ "${fl:-0}" -gt 0 ] || [ "${er:-0}" -gt 0 ]; then
      failing="$failing $(xml_attr name "$line" | sed 's/.*\.//')"
    fi
  done
  if [ "$classes" = 0 ]; then
    printf 'no results in app/build/test-results/testDebugUnitTest'
    return
  fi
  printf '%d tests in %d classes, %d failures, %d errors, %d skipped' \
    "$tests" "$classes" "$failures" "$errors" "$skipped"
  [ -n "$failing" ] && printf ' (failing:%s)' "$(echo "$failing" | tr ' ' '\n' | head -n 10 | tr '\n' ' ' | sed 's/ $//')"
  return 0
}

lint_summary() {
  local xml="$ROOT/app/build/reports/lint-results-debug.xml" counts baseline
  if [ ! -f "$xml" ]; then
    printf 'no report at app/build/reports/lint-results-debug.xml'
    return
  fi
  # Counts by severity; LintBaseline is lint's own "N issues were filtered" notice.
  counts=$(awk '
    /^[[:space:]]*id="/ { id = $0; sub(/^[[:space:]]*id="/, "", id); sub(/".*/, "", id) }
    /^[[:space:]]*severity="/ {
      if (id == "LintBaseline") next
      sev = $0; sub(/^[[:space:]]*severity="/, "", sev); sub(/".*/, "", sev)
      if (sev == "Error" || sev == "Fatal") e++; else if (sev == "Warning") w++; else h++
    }
    END { printf "%d errors, %d warnings, %d hints", e, w, h }
  ' "$xml")
  printf '%s outside the baseline' "$counts"
  # "10 errors, 223 warnings and 1 hint were filtered out because they are listed in the baseline file, ..."
  baseline=$(grep -A 3 'id="LintBaseline"' "$xml" | sed -n 's/^[[:space:]]*message="\([^"]*\) were filtered out because.*/\1/p' | head -n 1)
  [ -n "$baseline" ] && printf ' (baseline: %s)' "$baseline"
  return 0
}

lint_top_ids() { # the 10 most frequent issue ids in the lint report
  local xml="$ROOT/app/build/reports/lint-results-debug.xml"
  [ -f "$xml" ] || return 0
  awk '
    /^[[:space:]]*id="/ { id = $0; sub(/^[[:space:]]*id="/, "", id); sub(/".*/, "", id) }
    /^[[:space:]]*severity="/ {
      if (id == "LintBaseline") next
      sev = $0; sub(/^[[:space:]]*severity="/, "", sev); sub(/".*/, "", sev); n[id " (" sev ")"]++
    }
    END { for (k in n) printf "%6d  %s\n", n[k], k }
  ' "$xml" | sort -rn | head -n 10
}

apk_line() { # apk_line <path>: "name (size)" or nothing
  local f=$1 size
  [ -f "$f" ] || return 0
  size=$(wc -c < "$f" | tr -d ' ')
  printf '%s (%s MB)' "$(basename "$f")" "$(awk -v b="$size" 'BEGIN { printf "%.1f", b / 1048576 }')"
}

instrumented_summary() {
  local dir="$ROOT/app/build/outputs/androidTest-results/connected" f line t fl er
  local tests=0 failures=0 errors=0 files=0
  [ -d "$dir" ] || { printf 'no results in app/build/outputs/androidTest-results/connected'; return; }
  while IFS= read -r f; do
    line=$(grep -m 1 -o '<testsuite [^>]*>' "$f" || true)
    [ -n "$line" ] || continue
    t=$(xml_attr tests "$line"); fl=$(xml_attr failures "$line"); er=$(xml_attr errors "$line")
    files=$((files + 1)); tests=$((tests + ${t:-0}))
    failures=$((failures + ${fl:-0})); errors=$((errors + ${er:-0}))
  done < <(find "$dir" -name '*.xml' -type f)
  printf '%d tests on %d device(s), %d failures, %d errors' "$tests" "$files" "$failures" "$errors"
}

adb_path() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then echo "$ANDROID_HOME/platform-tools/adb"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then echo "$ANDROID_SDK_ROOT/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then echo adb
  fi
  return 0
}

# Prints the adb serials (one per line) the instrumented step would use, natively.
adb_serials() {
  local adb
  adb=$(adb_path)
  [ -n "$adb" ] || return 0
  "$adb" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }' | while read -r s; do
    if [ -z "${ANDROID_SERIAL:-}" ] || [ "$s" = "$ANDROID_SERIAL" ]; then echo "$s"; fi
  done
}

# ---- steps ------------------------------------------------------------------------------------
step_changelog() {
  local file="$ROOT/CHANGELOG.md" start out found dated entries first header
  start=$(date +%s)
  if [ ! -f "$file" ]; then
    record changelog FAIL $(( $(date +%s) - start )) "CHANGELOG.md not found"
    return 1
  fi
  out=$(awk -v ver="$VERSION_NAME" '
    BEGIN { head = "## [" ver "]"; found = 0; dated = 0; entries = 0; first = 0; sections = 0; in_sec = 0; ok = 0; header = "" }
    /^## \[/ {
      sections++
      in_sec = 0
      if (index($0, head) == 1) {
        found++; in_sec = 1; ok = 0
        if (sections == 1) first = 1
        if ($0 ~ /[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/) dated = 1
        if (header == "") header = $0
      }
      next
    }
    in_sec && /^### / { ok = ($2 == "Added" || $2 == "Changed" || $2 == "Fixed"); next }
    in_sec && /^## / { in_sec = 0; next }
    in_sec && ok && /^[ \t]*[-*] / { entries++ }
    END { printf "%d\t%d\t%d\t%d\t%s\n", found, dated, entries, first, header }
  ' "$file")
  found=$(printf '%s' "$out" | cut -f1); dated=$(printf '%s' "$out" | cut -f2)
  entries=$(printf '%s' "$out" | cut -f3); first=$(printf '%s' "$out" | cut -f4)
  header=$(printf '%s' "$out" | cut -f5-)
  local secs=$(( $(date +%s) - start ))
  if [ "$found" = 0 ]; then
    record changelog FAIL "$secs" "no '## [$VERSION_NAME]' section in CHANGELOG.md"
    return 1
  elif [ "$found" -gt 1 ]; then
    record changelog FAIL "$secs" "'## [$VERSION_NAME]' appears $found times in CHANGELOG.md"
    return 1
  elif [ "$dated" = 0 ]; then
    record changelog FAIL "$secs" "'$header' has no YYYY-MM-DD date"
    return 1
  elif [ "$entries" = 0 ]; then
    record changelog FAIL "$secs" "'$header' has no entry under Added, Changed or Fixed"
    return 1
  fi
  local note="$header, $entries entries under Added/Changed/Fixed"
  [ "$first" = 1 ] || note="$note; warning: it is not the first version section"
  record changelog PASS "$secs" "$note"
}

step_unit() {
  local start rc=0
  start=$(date +%s)
  gradle_run unit "" testDebugUnitTest || rc=$?
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  record unit "$res" $(( $(date +%s) - start )) "$(unit_summary)"
  return "$rc"
}

step_lint() {
  local start rc=0 top
  start=$(date +%s)
  gradle_run lint "" lintDebug || rc=$?
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  record lint "$res" $(( $(date +%s) - start )) "$(lint_summary)"
  top=$(lint_top_ids)
  if [ -n "$top" ]; then
    EXTRA_SECTIONS="$EXTRA_SECTIONS
Lint issues outside the baseline, most frequent ids (count  id (severity)):
$top
"
  fi
  return "$rc"
}

step_build() {
  local start rc=0 apks
  start=$(date +%s)
  rm -f "$ROOT"/app/build/outputs/apk/debug/*.apk "$ROOT"/app/build/outputs/apk/androidTest/debug/*.apk
  gradle_run build "" assembleDebug assembleDebugAndroidTest || rc=$?
  apks="$(apk_line "$ROOT/app/build/outputs/apk/debug/app-debug.apk")"
  local t
  t="$(apk_line "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")"
  [ -n "$t" ] && apks="${apks:+$apks, }$t"
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  record build "$res" $(( $(date +%s) - start )) "${apks:-no APK produced}"
  return "$rc"
}

RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
TEST_PACKAGE=com.cortinadev.dogmatix.debug.test
step_release() {
  local start rc=0 note
  start=$(date +%s)
  rm -f "$RELEASE_APK"
  gradle_run release "" assembleRelease || rc=$?
  note="$(apk_line "$RELEASE_APK")"
  if [ ! -f "$ROOT/keystore/dogmatix-release.jks" ]; then
    note="${note:+$note; }no keystore/dogmatix-release.jks: signed with the debug key"
  fi
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  record release "$res" $(( $(date +%s) - start )) "${note:-no APK produced}"
  return "$rc"
}

step_apkcheck() {
  local start rc=0 target log fails sha
  start=$(date +%s)
  log="$OUT_DIR/apkcheck.log"
  if [ ! -f "$RELEASE_APK" ]; then
    record apkcheck FAIL 0 "no app/build/outputs/apk/release/app-release.apk (run the release step)"
    return 1
  fi
  target="$OUT_DIR/dogmatix-$VERSION_NAME.apk"
  cp "$RELEASE_APK" "$target"
  echo "[validate] apkcheck: scripts/release-check.sh $target --pre-tag" | tee "$log"
  set +e
  bash "$ROOT/scripts/release-check.sh" "$target" --pre-tag 2>&1 | tee -a "$log"
  rc=${PIPESTATUS[0]}
  set -e
  fails=$(grep -c '✗' "$log" || true)
  sha=$(sed -n 's/^SHA-256 \([0-9a-f]*\).*/\1/p' "$log" | head -n 1)
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  local note="$fails failed check(s)"
  [ -n "$sha" ] && note="$note; SHA-256 $sha"
  selected release || note="$note; the release step did not run: the APK may be stale"
  record apkcheck "$res" $(( $(date +%s) - start )) "$note"
  return "$rc"
}

step_instrumented() {
  local start rc=0 serials docker_extra=""
  start=$(date +%s)
  if [ "$DOCKER_MODE" = 1 ]; then
    if [ "$FORCE_INSTRUMENTED" != 1 ]; then
      record instrumented SKIP - "Docker mode: needs --instrumented and DOGMATIX_ADB_CONNECT"
      return 0
    fi
    [ -n "${DOGMATIX_ADB_CONNECT:-}" ] || {
      record instrumented FAIL 0 "Docker mode needs DOGMATIX_ADB_CONNECT=host:port (the container has its own adb)"
      return 1
    }
    gradle_run_with_adb instrumented "$docker_extra" connectedDebugAndroidTest \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || rc=$?
  else
    serials=$(adb_serials)
    if [ -z "$serials" ]; then
      if [ "$FORCE_INSTRUMENTED" = 1 ]; then
        record instrumented FAIL 0 "--instrumented given but adb sees no device"
        return 1
      fi
      record instrumented SKIP - "adb sees no device"
      return 0
    fi
    if [ "$FORCE_INSTRUMENTED" != 1 ] && printf '%s\n' "$serials" | grep -qv '^emulator-'; then
      record instrumented SKIP - "a physical device is connected ($(printf '%s' "$serials" | tr '\n' ' ')): use --instrumented to run on it"
      return 0
    fi
    gradle_run instrumented "" connectedDebugAndroidTest \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || rc=$?
    local s adb
    adb=$(adb_path)
    for s in $serials; do "$adb" -s "$s" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true; done
  fi
  local res=PASS
  [ "$rc" = 0 ] || res=FAIL
  record instrumented "$res" $(( $(date +%s) - start )) "$(instrumented_summary)"
  return "$rc"
}

# ---- run --------------------------------------------------------------------------------------
STARTED_AT=$(date '+%Y-%m-%d %H:%M:%S %z')
TOTAL_START=$(date +%s)
STOPPED=0
for step in $ALL_STEPS; do
  if ! selected "$step"; then
    record "$step" SKIP - "not selected"
    continue
  fi
  if [ "$STOPPED" = 1 ]; then
    record "$step" SKIP - "--fail-fast: an earlier step failed"
    continue
  fi
  echo
  echo "[validate] ===== $step ====="
  if ! "step_$step"; then
    [ "$FAIL_FAST" = 1 ] && STOPPED=1
  fi
done

# ---- report -----------------------------------------------------------------------------------
GIT_LINE="not a git checkout"
if command -v git >/dev/null 2>&1 && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  GIT_LINE="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null) @ $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null)"
  if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then GIT_LINE="$GIT_LINE (uncommitted changes)"; fi
fi
if [ "$DOCKER_MODE" = 1 ]; then
  MODE_LINE="Docker ($IMAGE, volume $GRADLE_VOLUME, memory $DOCKER_MEMORY)"
else
  MODE_LINE="native Gradle"
fi
OVERALL=PASS
[ "$FAILED" = 1 ] && OVERALL=FAIL

{
  echo "Dogmatix validation report"
  echo "Started:    $STARTED_AT"
  echo "Repository: $ROOT"
  echo "Git:        $GIT_LINE"
  echo "Version:    $VERSION_NAME (versionCode ${VERSION_CODE:-?})"
  echo "Mode:       $MODE_LINE"
  echo
  printf '%-13s %-6s %-8s %s\n' STEP RESULT TIME DETAILS
  i=0
  while [ "$i" -lt "${#STEP_NAMES[@]}" ]; do
    printf '%-13s %-6s %-8s %s\n' "${STEP_NAMES[$i]}" "${STEP_RESULTS[$i]}" "${STEP_TIMES[$i]}" "${STEP_DETAILS[$i]}"
    i=$((i + 1))
  done
  [ -n "$EXTRA_SECTIONS" ] && printf '%s' "$EXTRA_SECTIONS"
  echo
  echo "Logs:       build/validate/<step>.log"
  echo "Total:      $(fmt_secs $(( $(date +%s) - TOTAL_START )))"
  echo "Result:     $OVERALL"
} > "$REPORT"

echo
echo "================================================================"
cat "$REPORT"
echo "Report written to build/validate/report.txt"

[ "$FAILED" = 0 ]
