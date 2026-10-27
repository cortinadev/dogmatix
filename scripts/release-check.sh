#!/usr/bin/env bash
# release-check.sh - check that an APK is the publishable Dogmatix release it claims to be, and
# write its checksums.
#
# Usage:
#   scripts/release-check.sh <apk> [--pre-tag] [--tag vX.Y] [--signer <sha256>] [--out-dir DIR]
#   DOGMATIX_DOCKER=1 scripts/release-check.sh <apk> ...   # run inside the dogmatix-build image
#
# What it checks (✓ passed, ✗ failed, ⚠ warning, · information). Only ✗ makes it fail.
#   Identity   aapt2 (or aapt) "dump badging": the package is com.cortinadev.dogmatix (a .debug
#              package or a "-debug" versionName is reported as a debug build, not publishable),
#              versionName and versionCode match app/build.gradle.kts, the APK is not
#              debuggable. minSdk, targetSdk and the native ABIs are compared too (warnings).
#   Signature  apksigner verify --print-certs: the signature verifies, there is one signer and
#              its certificate SHA-256 is the release key's (DOGMATIX_RELEASE_SIGNER or
#              --signer). An APK signed with the Android debug key is named as such: that is what
#              assembleRelease produces when keystore/dogmatix-release.jks or the RELEASE_*
#              values in .env are missing.
#   Tag        the expected tag is v<versionName> (--tag must agree with it). By default the tag
#              must exist and point to HEAD. With --pre-tag (before tagging) it must not exist
#              yet, or already point to HEAD; a tag on another commit means that version was
#              already released. Uncommitted changes are a warning.
#   Upgrade    versionCode is greater than the one in the latest other v* tag
#              (git show <tag>:app/build.gradle.kts), so Android accepts the update.
#   Checksums  writes <apk>.sha256 and <apk>.md5 (sha256sum / md5sum format, so
#              "sha256sum -c" works) next to the APK or in --out-dir, and prints them as they go
#              into the release notes ("SHA-256 <hex>").
#
# Options:
#   --pre-tag        the release tag is not expected to exist yet
#   --tag vX.Y       tag to check (must be v<versionName>); default v<versionName>
#   --signer HEX     expected signer certificate SHA-256 (colons and case are ignored)
#   --out-dir DIR    where to write the checksum files (default: the APK's directory)
#   -h, --help       print this help and exit
#
# Environment:
#   DOGMATIX_RELEASE_SIGNER  expected signer SHA-256; default is the key of the published
#                            releases, 9452f9f78dc174360f3a492d68dd06e2877e60cbe8668b76ee20c6d53c93313a
#   DOGMATIX_PACKAGE         expected package (default com.cortinadev.dogmatix)
#   DOGMATIX_BUILD_TOOLS     a build-tools directory to use instead of searching the SDK
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK searched for the newest build-tools with aapt2 and
#                            apksigner (sdk.dir in local.properties is tried too)
#   DOGMATIX_DOCKER=1        run the whole check inside the dogmatix-build image (which has the
#                            build-tools); the APK's directory is mounted, and so is the main
#                            repository's .git when this tree is a git worktree
#   DOGMATIX_DOCKER_IMAGE    image name (default dogmatix-build)
#
# Exit code: 0 when no check failed, 1 when at least one did, 2 on a usage or environment error
# (no APK, no build-tools).
#
# Requirements: bash 3.2+, sed, awk, grep, git; Android build-tools (aapt2 or aapt, apksigner,
# which needs a JDK); sha256sum or shasum or openssl, and md5sum or md5 or openssl.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEFAULT_SIGNER=9452f9f78dc174360f3a492d68dd06e2877e60cbe8668b76ee20c6d53c93313a
EXPECTED_PACKAGE="${DOGMATIX_PACKAGE:-com.cortinadev.dogmatix}"

usage() { sed -n '2,/^$/{s/^# \{0,1\}//;p;}' "$0"; }
die() { echo "release-check.sh: $*" >&2; exit 2; }

# ---- arguments --------------------------------------------------------------------------------
APK=""
TAG_ARG=""
SIGNER_ARG=""
PRE_TAG=0
OUT_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pre-tag) PRE_TAG=1; shift ;;
    --tag) [ $# -ge 2 ] || die "--tag needs a value"; TAG_ARG="$2"; shift 2 ;;
    --tag=*) TAG_ARG="${1#--tag=}"; shift ;;
    --signer) [ $# -ge 2 ] || die "--signer needs a value"; SIGNER_ARG="$2"; shift 2 ;;
    --signer=*) SIGNER_ARG="${1#--signer=}"; shift ;;
    --out-dir) [ $# -ge 2 ] || die "--out-dir needs a value"; OUT_ARG="$2"; shift 2 ;;
    --out-dir=*) OUT_ARG="${1#--out-dir=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown option: $1 (see --help)" ;;
    *) [ -z "$APK" ] || die "only one APK at a time"; APK="$1"; shift ;;
  esac
done
[ -n "$APK" ] || { usage >&2; exit 2; }
[ -f "$APK" ] || die "APK not found: $APK"
APK_DIR=$(cd "$(dirname "$APK")" && pwd)
APK_BASE=$(basename "$APK")
APK="$APK_DIR/$APK_BASE"

# ---- Docker: run this same script inside the build image ---------------------------------------
if [ "${DOGMATIX_DOCKER:-0}" = 1 ] && [ "${DOGMATIX_IN_DOCKER:-0}" != 1 ]; then
  command -v docker >/dev/null 2>&1 || die "DOGMATIX_DOCKER=1 but docker is not installed"
  IMAGE="${DOGMATIX_DOCKER_IMAGE:-dogmatix-build}"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || die "Docker image '$IMAGE' not found (docker build -t dogmatix-build docker)"
  mounts=(-v "$ROOT":/workspace -v "$APK_DIR":/apk)
  inner=("/apk/$APK_BASE")
  host_out="$APK_DIR"
  [ "$PRE_TAG" = 1 ] && inner+=(--pre-tag)
  [ -n "$TAG_ARG" ] && inner+=(--tag "$TAG_ARG")
  [ -n "$SIGNER_ARG" ] && inner+=(--signer "$SIGNER_ARG")
  if [ -n "$OUT_ARG" ]; then
    mkdir -p "$OUT_ARG"
    host_out=$(cd "$OUT_ARG" && pwd)
    mounts+=(-v "$host_out":/out)
    inner+=(--out-dir /out)
  fi
  # A git worktree's .git file points at the main repository's .git by its absolute host path:
  # mount it at that same path (read-only) so git works inside the container.
  if command -v git >/dev/null 2>&1 && common=$(git -C "$ROOT" rev-parse --git-common-dir 2>/dev/null); then
    common=$(cd "$ROOT" && cd "$common" && pwd)
    case "$common/" in
      "$ROOT"/*) ;;
      *) mounts+=(-v "$common":"$common":ro) ;;
    esac
  fi
  exec docker run --rm --memory=1g \
    -e DOGMATIX_IN_DOCKER=1 \
    -e DOGMATIX_HOST_APK="$APK" -e DOGMATIX_HOST_OUT="$host_out" \
    -e DOGMATIX_RELEASE_SIGNER="${DOGMATIX_RELEASE_SIGNER:-}" \
    -e DOGMATIX_PACKAGE="$EXPECTED_PACKAGE" \
    -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
    -e GIT_OPTIONAL_LOCKS=0 \
    "${mounts[@]}" -w /workspace "$IMAGE" bash /workspace/scripts/release-check.sh "${inner[@]}"
fi

# Paths as the user sees them (inside Docker the APK lives under /apk).
APK_SHOWN="${DOGMATIX_HOST_APK:-$APK}"
if [ -n "$OUT_ARG" ]; then
  mkdir -p "$OUT_ARG"
  OUT_DIR=$(cd "$OUT_ARG" && pwd)
else
  OUT_DIR="$APK_DIR"
fi
OUT_SHOWN="${DOGMATIX_HOST_OUT:-$OUT_DIR}"

FAILS=0
WARNS=0
pass() { echo "  ✓ $*"; }
fail() { echo "  ✗ $*"; FAILS=$((FAILS + 1)); }
warn() { echo "  ⚠ $*"; WARNS=$((WARNS + 1)); }
info() { echo "  · $*"; }
section() { echo; echo "$*"; }
lower() { printf '%s' "$1" | tr 'A-F' 'a-f'; }
short_hex() { printf '%s…%s' "$(printf '%s' "$1" | cut -c1-8)" "$(printf '%s' "$1" | sed 's/.*\(....\)$/\1/')"; }

EXPECTED_SIGNER=$(lower "$(printf '%s' "${SIGNER_ARG:-${DOGMATIX_RELEASE_SIGNER:-$DEFAULT_SIGNER}}" | tr -d ': ')")

# ---- what the project says ----------------------------------------------------------------------
GRADLE_FILE="$ROOT/app/build.gradle.kts"
[ -f "$GRADLE_FILE" ] || die "app/build.gradle.kts not found under $ROOT"
gradle_value() { # gradle_value <key> <file>: first `key = value` (quotes removed)
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[[:space:]]*\$/\1/p" "$2" | head -n 1
}
VERSION_NAME=$(gradle_value versionName "$GRADLE_FILE")
VERSION_CODE=$(gradle_value versionCode "$GRADLE_FILE")
APP_ID=$(gradle_value applicationId "$GRADLE_FILE")
MIN_SDK=$(gradle_value minSdk "$GRADLE_FILE")
TARGET_SDK=$(gradle_value targetSdk "$GRADLE_FILE")
ABIS=$(grep -m 1 'abiFilters' "$GRADLE_FILE" | grep -o '"[^"]*"' | tr -d '"' | tr '\n' ' ' | sed 's/ $//' || true)
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || die "could not read versionName/versionCode from app/build.gradle.kts"
EXPECTED_TAG="v$VERSION_NAME"

# ---- build-tools ------------------------------------------------------------------------------
sdk_dirs() {
  [ -n "${ANDROID_HOME:-}" ] && echo "$ANDROID_HOME"
  [ -n "${ANDROID_SDK_ROOT:-}" ] && echo "$ANDROID_SDK_ROOT"
  if [ -f "$ROOT/local.properties" ]; then
    sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed 's/\\:/:/g; s/\\\\/\\/g'
  fi
  return 0
}
find_tool() { # find_tool <name>: newest build-tools copy, else PATH
  local name=$1 sdk v
  if [ -n "${DOGMATIX_BUILD_TOOLS:-}" ] && [ -x "$DOGMATIX_BUILD_TOOLS/$name" ]; then
    echo "$DOGMATIX_BUILD_TOOLS/$name"; return 0
  fi
  for sdk in $(sdk_dirs); do
    [ -d "$sdk/build-tools" ] || continue
    for v in $(ls -1 "$sdk/build-tools" | sort -t. -k1,1nr -k2,2nr -k3,3nr); do
      if [ -x "$sdk/build-tools/$v/$name" ]; then echo "$sdk/build-tools/$v/$name"; return 0; fi
    done
  done
  command -v "$name" 2>/dev/null || true
}
AAPT=$(find_tool aapt2)
[ -n "$AAPT" ] || AAPT=$(find_tool aapt)
APKSIGNER=$(find_tool apksigner)
if [ -z "$AAPT" ] || [ -z "$APKSIGNER" ]; then
  die "Android build-tools not found (need aapt2 or aapt, and apksigner). Set ANDROID_HOME or
ANDROID_SDK_ROOT to an SDK with build-tools, or DOGMATIX_BUILD_TOOLS to a build-tools directory,
or run with DOGMATIX_DOCKER=1 to use the dogmatix-build image."
fi

APK_SIZE=$(wc -c < "$APK" | tr -d ' ')
APK_MB=$(awk -v b="$APK_SIZE" 'BEGIN { printf "%.1f", b / 1048576 }')

echo "Dogmatix release check"
echo "  APK:     $APK_SHOWN ($APK_MB MB)"
echo "  Project: versionName $VERSION_NAME, versionCode $VERSION_CODE (app/build.gradle.kts)"
echo "  Tools:   $(basename "$AAPT") and apksigner from $(dirname "$AAPT")"
echo "  Mode:    $( [ "$PRE_TAG" = 1 ] && echo "before tagging (--pre-tag)" || echo "after tagging" )"

# ---- identity -----------------------------------------------------------------------------------
section "Identity"
BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null || true)
PKG_LINE=$(printf '%s\n' "$BADGING" | grep -m 1 '^package:' || true)
if [ -z "$PKG_LINE" ]; then
  fail "cannot read the manifest with $(basename "$AAPT") (not an APK, or a broken one)"
  APK_PKG=""; APK_VNAME=""; APK_VCODE=""
else
  APK_PKG=$(printf '%s' "$PKG_LINE" | sed -n "s/.* name='\([^']*\)'.*/\1/p")
  APK_VCODE=$(printf '%s' "$PKG_LINE" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")
  APK_VNAME=$(printf '%s' "$PKG_LINE" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
  APK_MIN=$(printf '%s\n' "$BADGING" | sed -n "s/^\(min\)\{0,1\}[sS]dkVersion:'\([^']*\)'.*/\2/p" | head -n 1)
  APK_TARGET=$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)
  APK_ABIS=$(printf '%s\n' "$BADGING" | sed -n "s/^native-code: //p" | tr -d "'" | head -n 1)

  if [ "$APK_PKG" = "$EXPECTED_PACKAGE" ]; then
    pass "package $APK_PKG"
  else
    case "$APK_PKG" in
      "$EXPECTED_PACKAGE".debug|*.debug) fail "package $APK_PKG: debug build, not publishable" ;;
      *) fail "package $APK_PKG, expected $EXPECTED_PACKAGE" ;;
    esac
  fi
  [ -n "$APP_ID" ] && [ "$APP_ID" != "$EXPECTED_PACKAGE" ] \
    && warn "app/build.gradle.kts says applicationId $APP_ID, not $EXPECTED_PACKAGE"

  if [ "$APK_VNAME" = "$VERSION_NAME" ]; then
    pass "versionName $APK_VNAME"
  elif [ "$APK_VNAME" = "$VERSION_NAME-debug" ]; then
    fail "versionName $APK_VNAME: debug build, not publishable"
  else
    fail "versionName $APK_VNAME, but app/build.gradle.kts says $VERSION_NAME (APK from another version?)"
  fi

  if [ "$APK_VCODE" = "$VERSION_CODE" ]; then
    pass "versionCode $APK_VCODE"
  else
    fail "versionCode $APK_VCODE, but app/build.gradle.kts says $VERSION_CODE (APK from another version?)"
  fi

  if printf '%s\n' "$BADGING" | grep -q '^application-debuggable'; then
    fail "the APK is debuggable: debug build, not publishable"
  else
    pass "not debuggable"
  fi

  if [ -n "$MIN_SDK" ] && [ "$APK_MIN" != "$MIN_SDK" ]; then
    warn "minSdk $APK_MIN, app/build.gradle.kts says $MIN_SDK"
  else
    info "minSdk ${APK_MIN:-?}, targetSdk ${APK_TARGET:-?}"
  fi
  [ -n "$TARGET_SDK" ] && [ "$APK_TARGET" != "$TARGET_SDK" ] \
    && warn "targetSdk $APK_TARGET, app/build.gradle.kts says $TARGET_SDK"

  missing=""
  for abi in $ABIS; do
    case " $APK_ABIS " in *" $abi "*) ;; *) missing="$missing $abi" ;; esac
  done
  if [ -n "$missing" ]; then
    warn "native code lacks:$missing (has: ${APK_ABIS:-none})"
  else
    info "native code: ${APK_ABIS:-none}"
  fi
fi

# ---- signature ----------------------------------------------------------------------------------
section "Signature"
set +e
SIG_OUT=$("$APKSIGNER" verify --print-certs --verbose "$APK" 2>&1)
SIG_RC=$?
set -e
if [ "$SIG_RC" != 0 ]; then
  fail "apksigner verify failed: $(printf '%s\n' "$SIG_OUT" | grep -v '^WARNING' | head -n 2 | tr '\n' ' ')"
else
  schemes=$(printf '%s\n' "$SIG_OUT" | sed -n 's/^Verified using \(v[0-9.]*\) scheme.*: true$/\1/p' | tr '\n' ' ' | sed 's/ $//')
  pass "signature verifies (schemes: ${schemes:-none reported})"
  signers=$(printf '%s\n' "$SIG_OUT" | sed -n 's/^Number of signers: //p' | head -n 1)
  if [ -n "$signers" ] && [ "$signers" != 1 ]; then warn "$signers signers (expected 1)"; fi
  SIGNER=$(lower "$(printf '%s\n' "$SIG_OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1 | tr -d ': ')")
  SIGNER_DN=$(printf '%s\n' "$SIG_OUT" | sed -n 's/^Signer #1 certificate DN: //p' | head -n 1)
  if [ -z "$SIGNER" ]; then
    fail "apksigner did not print the signer's certificate"
  elif [ "$SIGNER" = "$EXPECTED_SIGNER" ]; then
    if [ "$EXPECTED_SIGNER" = "$DEFAULT_SIGNER" ]; then
      pass "signed with the key of the published releases (certificate SHA-256 $(short_hex "$SIGNER"), $SIGNER_DN)"
    else
      pass "signed with the expected key (certificate SHA-256 $(short_hex "$SIGNER"), $SIGNER_DN)"
    fi
  elif printf '%s' "$SIGNER_DN" | grep -q 'CN=Android Debug'; then
    fail "signed with the Android debug key ($SIGNER_DN, SHA-256 $SIGNER), not the release key $(short_hex "$EXPECTED_SIGNER"): the release keystore was missing (copy keystore/ and the RELEASE_* lines of .env into this tree). It would not install over a published release"
  else
    fail "signed with an unknown key ($SIGNER_DN, SHA-256 $SIGNER), not the release key $(short_hex "$EXPECTED_SIGNER")"
  fi
fi

# ---- tag and upgrade path -----------------------------------------------------------------------
section "Tag and upgrade"
if ! command -v git >/dev/null 2>&1 || ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  warn "not a git checkout (or git is missing): tag and versionCode history not checked"
else
  TAG="${TAG_ARG:-$EXPECTED_TAG}"
  if [ "$TAG" != "$EXPECTED_TAG" ]; then
    fail "--tag $TAG does not match versionName $VERSION_NAME (expected $EXPECTED_TAG)"
  fi
  HEAD_SHA=$(git -C "$ROOT" rev-parse HEAD)
  TAG_SHA=$(git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG^{commit}" 2>/dev/null || true)
  if [ "$PRE_TAG" = 1 ]; then
    if [ -z "$TAG_SHA" ]; then
      pass "tag $TAG does not exist yet (create it on $(git -C "$ROOT" rev-parse --short HEAD) after this check)"
    elif [ "$TAG_SHA" = "$HEAD_SHA" ]; then
      pass "tag $TAG already points to HEAD ($(git -C "$ROOT" rev-parse --short HEAD))"
    else
      fail "tag $TAG already exists on $(git -C "$ROOT" rev-parse --short "$TAG_SHA"), not HEAD: that version was already released; bump versionName and versionCode"
    fi
  else
    if [ -z "$TAG_SHA" ]; then
      fail "tag $TAG does not exist (tag the release commit, or use --pre-tag before tagging)"
    elif [ "$TAG_SHA" = "$HEAD_SHA" ]; then
      pass "tag $TAG points to HEAD ($(git -C "$ROOT" rev-parse --short HEAD))"
    else
      fail "tag $TAG points to $(git -C "$ROOT" rev-parse --short "$TAG_SHA"), but HEAD is $(git -C "$ROOT" rev-parse --short HEAD)"
    fi
  fi
  if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
    warn "the working tree has uncommitted changes: the APK may not match HEAD"
  fi

  PREV_TAG=""
  for t in $(git -C "$ROOT" tag -l 'v[0-9]*' --sort=-v:refname); do
    if [ "$t" != "$EXPECTED_TAG" ]; then PREV_TAG="$t"; break; fi
  done
  if [ -z "$PREV_TAG" ]; then
    info "no earlier v* tag: versionCode history not checked"
  else
    PREV_CODE=$(git -C "$ROOT" show "$PREV_TAG:app/build.gradle.kts" 2>/dev/null \
      | sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1 || true)
    if [ -z "$PREV_CODE" ]; then
      warn "could not read versionCode from $PREV_TAG:app/build.gradle.kts"
    elif [ "$VERSION_CODE" -gt "$PREV_CODE" ]; then
      pass "versionCode $VERSION_CODE > $PREV_CODE ($PREV_TAG, the latest other tag): Android accepts the update"
    else
      fail "versionCode $VERSION_CODE is not greater than $PREV_CODE ($PREV_TAG): Android would refuse the update"
    fi
  fi
fi

# ---- checksums ----------------------------------------------------------------------------------
section "Checksums"
hash_of() { # hash_of sha256|md5 <file>
  local algo=$1 f=$2
  if [ "$algo" = sha256 ]; then
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$f" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$f" | awk '{ print $1 }'
    elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 -r "$f" | awk '{ print $1 }'
    fi
  else
    if command -v md5sum >/dev/null 2>&1; then md5sum "$f" | awk '{ print $1 }'
    elif command -v md5 >/dev/null 2>&1; then md5 -q "$f"
    elif command -v openssl >/dev/null 2>&1; then openssl dgst -md5 -r "$f" | awk '{ print $1 }'
    fi
  fi
}
SHA256=$(hash_of sha256 "$APK")
MD5=$(hash_of md5 "$APK")
if [ -z "$SHA256" ] || [ -z "$MD5" ]; then
  fail "no sha256sum/shasum/openssl or md5sum/md5/openssl to hash the APK"
else
  printf '%s  %s\n' "$SHA256" "$APK_BASE" > "$OUT_DIR/$APK_BASE.sha256"
  printf '%s  %s\n' "$MD5" "$APK_BASE" > "$OUT_DIR/$APK_BASE.md5"
  pass "wrote $OUT_SHOWN/$APK_BASE.sha256 and .md5"
fi

# ---- summary ------------------------------------------------------------------------------------
echo
echo "For the release notes:"
echo "  $APK_BASE · $APK_MB MB · ${APK_PKG:-?} · versionName ${APK_VNAME:-?} · versionCode ${APK_VCODE:-?}"
[ -n "$SHA256" ] && echo "SHA-256 $SHA256"
[ -n "$MD5" ] && echo "MD5 $MD5"
[ -n "${SIGNER:-}" ] && echo "Signer certificate SHA-256 $SIGNER"
echo
if [ "$FAILS" = 0 ]; then
  echo "Result: OK, publishable ($WARNS warning(s))"
else
  echo "Result: $FAILS check(s) failed, $WARNS warning(s): do not publish this APK"
fi
[ "$FAILS" = 0 ]
