#!/usr/bin/env bash
#
# Regenerates outertune-metrolist-engine.patch.
#
# The exclusions are the whole point of having this as a script rather than a command someone
# retypes, because getting either of them wrong produces a patch that looks fine and fails on the
# runner.
#
#   ':(exclude)*.patch'
#       The patch is tracked in the repo, because the build workflow reads it from the repo root.
#       Without this, each regeneration embeds the previous copy of itself - once measured at 4.3MB
#       becoming 8.7MB for two small commits, and it doubles every time after that.
#
#   ':(exclude).github/workflows/*'
#       The workflows live on the branch the build is dispatched from, and are uploaded there
#       directly. Shipping them through the patch as well means the patch rewrites the workflow
#       that is applying it, and any edit made on either side collides with the other. That is not
#       hypothetical: it is what made commit 284 fail to apply, on build-new-patched.yml, after a
#       line-ending normalisation rewrote the whole file on one side.
#
#       Workflow changes therefore have to be uploaded to the build branch by hand. That is a real
#       cost, and it is smaller than the alternative.
#
#   --base
#       Records the commit this was built against, so the workflow can check the checkout is
#       actually there before trying - and move if not. Without it, a base mismatch surfaces as a
#       wall of rejected diff with no hint that the base is the problem.
#
set -euo pipefail

BASE="${1:-93260340a4f9d8d275485b7784d317b875cb7d71}"
OUT="${2:-outertune-metrolist-engine.patch}"

cd "$(git rev-parse --show-toplevel)"

if ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  echo "base $BASE is not in this repository" >&2
  exit 1
fi

git format-patch "${BASE}..HEAD" --stdout --base="$BASE" \
  -- . ':(exclude)*.patch' ':(exclude).github/workflows/*' > "$OUT"

commits="$(grep -c '^From ' "$OUT" || true)"
bytes="$(wc -c < "$OUT")"
echo "$OUT"
echo "  base:    $BASE"
echo "  commits: $commits"
echo "  bytes:   $bytes"
echo "  sha256:  $(sha256sum "$OUT" | cut -d' ' -f1)"

# A patch that still carries a workflow diff would reintroduce the collision above.
if grep -q '^diff --git a/\.github/workflows' "$OUT"; then
  echo "ERROR: workflow diffs leaked into the patch" >&2
  exit 1
fi
