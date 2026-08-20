#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assessment_simulator_id="${IOS_SIMULATOR_ID:-}"

if [[ -z "$assessment_simulator_id" ]]; then
  assessment_simulator_id="$(
    xcrun simctl list devices available |
      sed -nE 's/^[[:space:]]+iPhone.*\(([0-9A-Fa-f-]{36})\)[[:space:]]+\((Booted|Shutdown)\)[[:space:]]*$/\1/p' |
      head -n 1
  )"
fi

if [[ -z "$assessment_simulator_id" ]]; then
  echo "No available iPhone simulator was found. Set IOS_SIMULATOR_ID explicitly." >&2
  exit 1
fi

cd "$repository_root"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme MobileBank \
  -destination "platform=iOS Simulator,id=$assessment_simulator_id" \
  CODE_SIGNING_ALLOWED=NO \
  test
