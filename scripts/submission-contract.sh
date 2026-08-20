#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

failures=0
warnings=0
pass() { echo "[pass] $*"; }
warn() { echo "[warn] $*"; warnings=$((warnings + 1)); }
fail() { echo "[fail] $*" >&2; failures=$((failures + 1)); }

require_file() {
  local path="$1"
  if [[ -s "$path" ]]; then pass "$path exists"; else fail "$path is missing or empty"; fi
}

require_heading() {
  local file="$1"
  local heading="$2"
  if grep -Fqx "$heading" "$file" 2>/dev/null; then
    pass "$file contains $heading"
  else
    fail "$file is missing heading: $heading"
  fi
}

for deliverable in PLAN.md DECISIONS.md AI_USAGE.md; do require_file "$deliverable"; done

for heading in "# Critical User Flow" "# Relevant Components" "# Top Risks" "# Chosen Slice" "# Validation Plan" "# Platform Impact" "# Non-goals"; do
  require_heading PLAN.md "$heading"
done

for heading in "# Problem" "# User and Production Impact" "# Chosen Scope" "# Decisions" "# Shared vs Platform-specific Responsibility" "# Alternatives" "# Validation" "# Residual Risks" "# Non-goals" "# Release Recommendation" "# Rollout and Rollback"; do
  require_heading DECISIONS.md "$heading"
done

for heading in "# Tools and Purposes" "# Accepted Suggestions" "# Rejected or Changed Suggestion" "# Independent Verification" "# Data Boundary"; do
  require_heading AI_USAGE.md "$heading"
done

if grep -Eq '[[:alnum:]_./-]+\.(kt|kts|swift|sq|md):[0-9]+' PLAN.md; then
  pass "PLAN.md includes file-and-line evidence"
else
  fail "PLAN.md must cite repository evidence as path/File.ext:line"
fi

decision_count="$(grep -Eic '^[[:space:]]*(\*\*Decision:\*\*[[:space:]]*)?`?(GO|CONDITIONAL GO|NO-GO)`?[[:space:]]*$' DECISIONS.md || true)"
if grep -Fq '`GO | CONDITIONAL GO | NO-GO`' DECISIONS.md; then
  fail "Replace the release recommendation placeholder with exactly one decision"
elif (( decision_count == 1 )); then
  pass "Exactly one release decision was found"
else
  fail "State exactly one release recommendation: GO, CONDITIONAL GO, or NO-GO"
fi

decisions_words="$(wc -w <DECISIONS.md | tr -d ' ')"
ai_words="$(wc -w <AI_USAGE.md | tr -d ' ')"
if (( decisions_words <= 700 )); then pass "DECISIONS.md is $decisions_words words (limit 700)"; else fail "DECISIONS.md is $decisions_words words (limit 700)"; fi
if (( ai_words <= 300 )); then pass "AI_USAGE.md is $ai_words words (limit 300)"; else fail "AI_USAGE.md is $ai_words words (limit 300)"; fi

if grep -Eqi 'not run|not verified|analysis[- ]only|unable to (run|verify)|could not (run|verify)' DECISIONS.md; then
  pass "DECISIONS.md explicitly records at least one validation boundary"
else
  warn "State explicitly whether any Android/iOS/device checks were not run"
fi

tracked_noise="$(git ls-files | grep -E '(^|/)(build|\.gradle|\.gradle-user-home|DerivedData)/|(^|/)\.idea/|(^|/)local\.properties$|\.iml$|\.keystore$|\.jks$|\.p12$|\.mobileprovision$|xcuserdata|\.DS_Store$' || true)"
if [[ -n "$tracked_noise" ]]; then fail "Generated, local, or signing files are tracked:\n$tracked_noise"; else pass "No common generated/local/signing files are tracked"; fi

private_key_files="$(git grep -IlE -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' 2>/dev/null || true)"
if [[ -n "$private_key_files" ]]; then fail "A private-key marker appears in tracked content:\n$private_key_files"; else pass "No private-key marker was found in tracked content"; fi

contract_base="${ASSESSMENT_BASE_REF:-assessment-mobile-v1}"
if git rev-parse --verify --quiet "${contract_base}^{commit}" >/dev/null; then
  if git diff --check "$contract_base" --; then pass "git diff --check reports no whitespace errors"; else fail "git diff --check reported whitespace errors"; fi
else
  warn "Base ref $contract_base is unavailable; whitespace and budget checks cannot cover committed changes"
  if git diff --check; then pass "Uncommitted diff has no whitespace errors"; else fail "Uncommitted diff has whitespace errors"; fi
fi

echo
if ! ./scripts/change-budget.sh "$contract_base"; then
  warn "Change budget could not be calculated; pass ASSESSMENT_BASE_REF when the base tag is unavailable"
fi

echo
echo "Submission contract: $failures failure(s), $warnings warning(s)"
if (( failures > 0 )); then exit 1; fi
echo "Mechanical submission checks passed. This does not establish production correctness."
