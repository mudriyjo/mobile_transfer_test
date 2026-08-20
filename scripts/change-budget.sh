#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
enforce=false
base_ref="${ASSESSMENT_BASE_REF:-}"

usage() { echo "Usage: $0 [--enforce] [base-ref]"; }

for argument in "$@"; do
  case "$argument" in
    --enforce) enforce=true ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown option: $argument" >&2; usage >&2; exit 2 ;;
    *)
      if [[ -n "$base_ref" ]]; then
        echo "Only one base ref may be supplied" >&2
        exit 2
      fi
      base_ref="$argument"
      ;;
  esac
done

cd "$repository_root"

if [[ -z "$base_ref" ]]; then
  if git rev-parse --verify --quiet assessment-mobile-v1^{commit} >/dev/null; then
    base_ref="assessment-mobile-v1"
  elif git rev-parse --verify --quiet origin/main^{commit} >/dev/null; then
    base_ref="$(git merge-base HEAD origin/main)"
  elif git rev-parse --verify --quiet HEAD^ >/dev/null; then
    base_ref="HEAD^"
  else
    echo "Unable to infer an assessment base. Pass a commit/tag or set ASSESSMENT_BASE_REF." >&2
    exit 2
  fi
fi

if ! git rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null; then
  echo "Base ref does not resolve to a commit: $base_ref" >&2
  exit 2
fi

diff_file="$(mktemp "${TMPDIR:-/tmp}/mobile-bank-budget.XXXXXX")"
files_file="$(mktemp "${TMPDIR:-/tmp}/mobile-bank-files.XXXXXX")"
trap 'rm -f "$diff_file" "$files_file"' EXIT

git diff --numstat --find-renames "$base_ref" -- >"$diff_file"
git diff --name-only --find-renames "$base_ref" -- >"$files_file"

is_production_path='^(shared/src/(commonMain|androidMain|iosMain)/|composeApp/src/(commonMain|androidMain|iosMain)/|androidApp/src/main/|iosApp/|backendStub/src/main/)'
is_test_path='(^|/)(commonTest|androidUnitTest|androidInstrumentedTest|iosTest|test|androidTest)/|Tests?/|Test\.swift$'

production_files="$(awk -F '\t' -v production="$is_production_path" -v tests="$is_test_path" '$3 ~ production && $3 !~ tests { count++ } END { print count + 0 }' "$diff_file")"
shared_files="$(awk -F '\t' '$3 ~ /^shared\/src\/commonMain\// { count++ } END { print count + 0 }' "$diff_file")"
production_churn="$(awk -F '\t' -v production="$is_production_path" -v tests="$is_test_path" '$3 ~ production && $3 !~ tests && $1 != "-" && $2 != "-" { sum += $1 + $2 } END { print sum + 0 }' "$diff_file")"
test_churn="$(awk -F '\t' -v tests="$is_test_path" '$3 ~ tests && $1 != "-" && $2 != "-" { sum += $1 + $2 } END { print sum + 0 }' "$diff_file")"
migration_files="$(awk -F '\t' '$3 ~ /\.sqm$/ { count++ } END { print count + 0 }' "$diff_file")"
total_files="$(wc -l <"$files_file" | tr -d ' ')"

decisions_words=0
ai_words=0
[[ -f DECISIONS.md ]] && decisions_words="$(wc -w <DECISIONS.md | tr -d ' ')"
[[ -f AI_USAGE.md ]] && ai_words="$(wc -w <AI_USAGE.md | tr -d ' ')"

dependency_additions="$(git diff --unified=0 "$base_ref" -- gradle/libs.versions.toml '*.gradle.kts' 2>/dev/null | awk '
  /^\+\+\+/ { next }
  /^\+/ && ($0 ~ /implementation\(/ || $0 ~ /api\(/ || $0 ~ /compileOnly\(/ || $0 ~ /runtimeOnly\(/ || $0 ~ /\{[[:space:]]*module[[:space:]]*=/) { count++ }
  END { print count + 0 }
')"

echo "Change budget against: $base_ref"
printf '%-36s %8s %8s\n' "Measure" "Actual" "Guide"
printf '%-36s %8s %8s\n' "Changed files (all)" "$total_files" "info"
printf '%-36s %8s %8s\n' "Changed production files" "$production_files" "10"
printf '%-36s %8s %8s\n' "Changed shared production files" "$shared_files" "5"
printf '%-36s %8s %8s\n' "Production line churn" "$production_churn" "400"
printf '%-36s %8s %8s\n' "Test line churn" "$test_churn" "300"
printf '%-36s %8s %8s\n' "SQLDelight migration files" "$migration_files" "1"
printf '%-36s %8s %8s\n' "Dependency additions (heuristic)" "$dependency_additions" "1"
printf '%-36s %8s %8s\n' "DECISIONS.md words" "$decisions_words" "700"
printf '%-36s %8s %8s\n' "AI_USAGE.md words" "$ai_words" "300"

violations=0
check_limit() {
  local label="$1"
  local actual="$2"
  local limit="$3"
  if (( actual > limit )); then
    echo "[over] $label is $actual (guide: $limit)"
    violations=$((violations + 1))
  fi
}

check_limit "production files" "$production_files" 10
check_limit "shared production files" "$shared_files" 5
check_limit "production churn" "$production_churn" 400
check_limit "test churn" "$test_churn" 300
check_limit "database migrations" "$migration_files" 1
check_limit "dependency additions" "$dependency_additions" 1
check_limit "DECISIONS.md words" "$decisions_words" 700
check_limit "AI_USAGE.md words" "$ai_words" 300

if (( violations == 0 )); then
  echo "Within the mechanical guide. Review semantic scope separately."
elif [[ "$enforce" == true ]]; then
  echo "$violations budget limit(s) exceeded. Explain any necessary exception in DECISIONS.md." >&2
  exit 1
else
  echo "$violations budget limit(s) exceeded. This is not an automatic failure; justify necessary scope in DECISIONS.md."
fi
