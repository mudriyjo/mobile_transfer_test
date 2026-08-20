#!/usr/bin/env bash

set -Eeuo pipefail

strict=false
if [[ "${1:-}" == "--strict" ]]; then
  strict=true
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--strict]" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failures=0
warnings=0

pass() { echo "[pass] $*"; }
warn() { echo "[warn] $*"; warnings=$((warnings + 1)); }
fail() { echo "[fail] $*" >&2; failures=$((failures + 1)); }

version_major() {
  local version="$1"
  version="${version#\"}"
  if [[ "$version" == 1.* ]]; then
    version="${version#1.}"
  fi
  echo "${version%%[._-]*}"
}

echo "Mobile Bank assessment environment"
echo "Repository: $repository_root"

if [[ -x "$repository_root/gradlew" ]]; then
  pass "Gradle wrapper is executable"
else
  fail "gradlew is missing or not executable"
fi

if command -v java >/dev/null 2>&1; then
  java_version="$(java -version 2>&1 | awk -F '"' 'NR == 1 { print $2 }')"
  java_major="$(version_major "$java_version")"
  if [[ "$java_major" =~ ^[0-9]+$ ]] && (( java_major >= 21 )); then
    pass "Java $java_version is available"
  else
    fail "JDK 21 or newer is required; found ${java_version:-unknown}"
  fi
else
  fail "Java was not found on PATH"
fi

android_sdk_location=""
if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
  android_sdk_location="$ANDROID_HOME"
  pass "ANDROID_HOME points to $android_sdk_location"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
  android_sdk_location="$ANDROID_SDK_ROOT"
  pass "ANDROID_SDK_ROOT points to $android_sdk_location"
elif [[ -f "$repository_root/local.properties" ]]; then
  android_sdk_location="$(sed -n 's/^sdk\.dir=//p' "$repository_root/local.properties" | head -n 1)"
  android_sdk_location="${android_sdk_location//\\:/:}"
  android_sdk_location="${android_sdk_location//\\\\/\\}"
  if [[ -d "$android_sdk_location" ]]; then
    pass "local.properties points to $android_sdk_location"
  else
    android_sdk_location=""
    warn "local.properties does not point to an available Android SDK"
  fi
else
  warn "Android SDK is not configured through the environment or local.properties"
fi

if command -v adb >/dev/null 2>&1; then
  pass "adb is available"
else
  warn "adb is not on PATH; device/emulator validation will be unavailable"
fi

if command -v curl >/dev/null 2>&1; then
  pass "curl is available for backend fault scenarios"
else
  warn "curl is unavailable; use another HTTP client for backend fault scenarios"
fi

if [[ "$(uname -s)" == "Darwin" ]]; then
  if command -v xcodebuild >/dev/null 2>&1; then
    xcode_summary="$(xcodebuild -version 2>/dev/null | tr '\n' ' ')"
    pass "${xcode_summary% }"
  else
    fail "xcodebuild is required for iOS verification on macOS"
  fi

  if command -v xcrun >/dev/null 2>&1 && xcrun --find simctl >/dev/null 2>&1; then
    pass "iOS simulator tools are available"
  else
    warn "simctl is unavailable; simulator checks cannot run"
  fi
else
  warn "Non-macOS host detected; iOS checks require a macOS runner"
fi

if command -v git >/dev/null 2>&1 && git -C "$repository_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  pass "Git work tree is available"
else
  fail "The assessment must run in a Git work tree"
fi

if [[ -x "$repository_root/gradlew" ]] && command -v java >/dev/null 2>&1; then
  if (cd "$repository_root" && ./gradlew --version >/dev/null); then
    pass "Gradle wrapper starts successfully"
  else
    fail "Gradle wrapper could not start"
  fi
fi

echo
echo "Summary: $failures failure(s), $warnings warning(s)"
if (( failures > 0 )); then
  exit 1
fi
if [[ "$strict" == true ]] && (( warnings > 0 )); then
  exit 1
fi
echo "Environment is ready for the checks supported by this host."
