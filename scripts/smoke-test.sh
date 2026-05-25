#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# DeployMate smoke test
#
# Validates that a running DeployMate instance (default: localhost:8080)
# responds correctly to every REST endpoint.
#
# Requirements: curl, jq
#
# Usage:
#   ./scripts/smoke-test.sh [BASE_URL]
#
# Examples:
#   ./scripts/smoke-test.sh                          # → http://localhost:8080
#   ./scripts/smoke-test.sh http://myserver:8080
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

# ── Colours ──────────────────────────────────────────────────────────────────
GREEN="\033[32m"
RED="\033[31m"
YELLOW="\033[33m"
RESET="\033[0m"

ok()   { echo -e "${GREEN}  ✓ PASS${RESET}  $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}  ✗ FAIL${RESET}  $1"; FAIL=$((FAIL+1)); }
info() { echo -e "${YELLOW}  ▶${RESET}  $1"; }

assert_status() {
  local label="$1" expected="$2" url="$3"
  shift 3
  local actual
  actual=$(curl -s -o /dev/null -w "%{http_code}" "$@" "$url")
  if [ "$actual" -eq "$expected" ]; then
    ok "$label (HTTP $actual)"
  else
    fail "$label — expected HTTP $expected, got HTTP $actual"
  fi
}

assert_json_field() {
  local label="$1" url="$2" field="$3" expected_value="$4"
  shift 4
  local body
  body=$(curl -s "$@" "$url")
  local actual
  actual=$(echo "$body" | jq -r "$field" 2>/dev/null || echo "__JQ_ERROR__")
  if [ "$actual" = "$expected_value" ]; then
    ok "$label (field $field = $expected_value)"
  else
    fail "$label — expected $field='$expected_value', got '$actual'"
    echo "      Response body: $body"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
echo ""
info "DeployMate smoke test → $BASE"
echo "──────────────────────────────────────────────────────────────"

# ── 1. SPA — GET / returns HTML ───────────────────────────────────────────────
info "SPA root"
assert_status "GET / returns 200"  200 "$BASE/"

# ── 2. Unknown SPA routes fall back to index.html ─────────────────────────────
info "SPA fallback"
assert_status "GET /unknown-path returns 200 (SPA fallback)" 200 "$BASE/some/deep/route"

# ── 3. /api/log — empty log returns JSON array ───────────────────────────────
info "Log endpoint"
assert_status "GET /api/log returns 200" 200 "$BASE/api/log?lines=5"
LOG_BODY=$(curl -s "$BASE/api/log?lines=5")
if echo "$LOG_BODY" | jq -e 'type == "array"' > /dev/null 2>&1; then
  ok "GET /api/log returns JSON array"
else
  fail "GET /api/log did not return a JSON array — got: $LOG_BODY"
fi

# ── 4. Input validation — missing body → 400 ─────────────────────────────────
info "Input validation (missing bodies → 400)"

assert_status "POST /api/merge with no body → 400" 400 \
  "$BASE/api/merge" \
  -X POST -H "Content-Type: application/json"

assert_status "POST /api/tag with no body → 400" 400 \
  "$BASE/api/tag" \
  -X POST -H "Content-Type: application/json"

assert_status "POST /api/pipeline/trigger with no body → 400" 400 \
  "$BASE/api/pipeline/trigger" \
  -X POST -H "Content-Type: application/json"

assert_status "POST /api/jira/comment with no body → 400" 400 \
  "$BASE/api/jira/comment" \
  -X POST -H "Content-Type: application/json"

assert_status "POST /api/deploy/all with no body → 400" 400 \
  "$BASE/api/deploy/all" \
  -X POST -H "Content-Type: application/json"

# ── 5. Input validation — invalid field values → 400 ─────────────────────────
info "Input validation (bad field values → 400)"

assert_status "POST /api/merge with path-traversal repo → 400" 400 \
  "$BASE/api/merge" \
  -X POST -H "Content-Type: application/json" \
  -d '{"org":"acme","repo":"../etc/passwd","sourceBranch":"main","targetBranch":"staging","ticket":""}'

assert_status "POST /api/merge with blank repo → 400" 400 \
  "$BASE/api/merge" \
  -X POST -H "Content-Type: application/json" \
  -d '{"org":"acme","repo":"","sourceBranch":"main","targetBranch":"staging","ticket":""}'

assert_status "POST /api/tag with invalid tagName characters → 400" 400 \
  "$BASE/api/tag" \
  -X POST -H "Content-Type: application/json" \
  -d '{"org":"acme","repo":"my-svc","tagName":"invalid tag!","targetBranch":"staging","ticket":""}'

# ── 6. Error response shape ───────────────────────────────────────────────────
info "Error response shape"
ERR_BODY=$(curl -s -X POST -H "Content-Type: application/json" "$BASE/api/merge")
if echo "$ERR_BODY" | jq -e '.error' > /dev/null 2>&1; then
  ok "400 body contains 'error' field"
else
  fail "400 body missing 'error' field — got: $ERR_BODY"
fi
if echo "$ERR_BODY" | jq -e '.message' > /dev/null 2>&1; then
  ok "400 body contains 'message' field"
else
  fail "400 body missing 'message' field — got: $ERR_BODY"
fi

# ── 7. Pipeline status — missing params → 400 ────────────────────────────────
info "Pipeline status"
assert_status "GET /api/pipeline/status with no params → 400" 400 \
  "$BASE/api/pipeline/status"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "──────────────────────────────────────────────────────────────"
echo -e "Results: ${GREEN}$PASS passed${RESET}, ${RED}$FAIL failed${RESET}"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo -e "${RED}Smoke test FAILED — $FAIL check(s) did not pass.${RESET}"
  exit 1
else
  echo -e "${GREEN}All smoke tests passed ✓${RESET}"
  exit 0
fi
