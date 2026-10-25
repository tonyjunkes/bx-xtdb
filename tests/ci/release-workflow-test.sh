#!/usr/bin/env bash
# Execute the workflow's actual run blocks with isolated files and fake external commands.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for block in release-state release-package release-github release-forgebox; do
  awk -v begin="# BEGIN $block" -v end="# END $block" '
    index($0, begin) { capture=1; next }
    index($0, end) { capture=0 }
    capture { sub(/^          /, ""); print }
  ' "$root/.github/workflows/release.yml" > "$work/$block.sh"
  test -s "$work/$block.sh"
done

# These functions replace only network/publication commands in child shells.
git() {
  printf 'git %s\n' "$*" >> "$COMMAND_LOG"
  case "$1" in
    rev-parse) echo head ;;
    tag) if [[ "${2:-}" == --list && -n "$TAG" ]]; then echo "$VERSION"; fi ;;
    rev-list) echo "$TAG" ;;
    merge-base) [[ "${3:-}" != other ]] ;;
  esac
}
curl() {
  if [[ "$REGISTRY_STATUS" == network-error ]]; then return 7; fi
  local output=''
  while (( $# )); do
    if [[ "$1" == --output ]]; then output="$2"; shift; fi
    shift
  done
  if [[ "$REGISTRY_PUBLISHED" == true ]]; then
    printf '{"error":false,"data":{"versions":[{"version":"%s"}]}}' "$VERSION" > "$output"
  else
    printf '{"error":false,"data":{"versions":[]}}' > "$output"
  fi
  printf '%s' "$REGISTRY_STATUS"
}
gh() {
  printf 'gh %s\n' "$*" >> "$COMMAND_LOG"
  case "$1 $2" in
    'release list')
      if [[ "$GH_FAILURE" == true ]]; then return 1; fi
      if [[ "$RELEASE_EXISTS" == true ]]; then
        printf '[{"tagName":"%s","isDraft":%s}]' "$VERSION" "$RELEASE_DRAFT"
      else
        echo '[]'
      fi
      ;;
    'release view')
      if [[ "$ASSETS" == complete ]]; then
        printf '{"assets":[{"name":"%s"},{"name":"%s.sha256"}]}' "$ARCHIVE" "$ARCHIVE"
      else
        echo '{"assets":[]}'
      fi
      ;;
    'release download')
      local name='' directory=''
      while (( $# )); do
        case "$1" in
          --pattern) name="$2"; shift ;;
          --dir) directory="$2"; shift ;;
        esac
        shift
      done
      if [[ "$CHANGED_ASSET" == true ]]; then
        printf changed > "$directory/$name"
      else
        cp "build/distributions/$name" "$directory/$name"
      fi
      ;;
  esac
}
box() {
  # Record the operation only; credential-bearing arguments must not appear in logs.
  printf 'box %s %s\n' "$1" "$2" >> "$COMMAND_LOG"
}
export -f git curl gh box

case_number=0
setup_case() {
  case_number=$((case_number + 1))
  export GITHUB_WORKSPACE="$work/case-$case_number"
  export RUNNER_TEMP="$GITHUB_WORKSPACE/tmp" GITHUB_OUTPUT="$GITHUB_WORKSPACE/output"
  export COMMAND_LOG="$GITHUB_WORKSPACE/commands"
  export GITHUB_REF=refs/heads/main GITHUB_SHA=head GH_REPO=tonyjunkes/bx-xtdb
  # Keep fixtures independent of the package under development. A deliberately
  # different version also catches accidental reuse of the real package metadata.
  export VERSION=9.8.7 SLUG=bx-xtdb FORGEBOX_API_TOKEN=fixture
  export ARCHIVE="$SLUG-$VERSION.zip"
  export TAG='' REGISTRY_STATUS=404 REGISTRY_PUBLISHED=false GH_FAILURE=false
  export RELEASE_EXISTS=false RELEASE_DRAFT=false ASSETS=none CHANGED_ASSET=false
  mkdir -p "$RUNNER_TEMP" "$GITHUB_WORKSPACE/build/distributions" \
    "$GITHUB_WORKSPACE/package/libs" "$GITHUB_WORKSPACE/src/main/bx"
  jq -n --arg slug "$SLUG" --arg version "$VERSION" \
    --arg location "https://github.com/$GH_REPO/releases/download/$VERSION/$ARCHIVE" \
    '{slug: $slug, version: $version, location: $location}' > "$GITHUB_WORKSPACE/box.json"
  printf 'class {\n    this.version = "%s";\n}\n' "$VERSION" > "$GITHUB_WORKSPACE/src/main/bx/ModuleConfig.bx"
  cp "$GITHUB_WORKSPACE/box.json" "$GITHUB_WORKSPACE/package/box.json"
  cp "$GITHUB_WORKSPACE/src/main/bx/ModuleConfig.bx" "$GITHUB_WORKSPACE/package/ModuleConfig.bx"
  printf fixture > "$GITHUB_WORKSPACE/package/libs/$SLUG-$VERSION.jar"
  (cd "$GITHUB_WORKSPACE/package" && zip -qr "$GITHUB_WORKSPACE/build/distributions/$ARCHIVE" .)
  (cd "$GITHUB_WORKSPACE/build/distributions" && sha256sum "$ARCHIVE" > "$ARCHIVE.sha256")
  : > "$GITHUB_OUTPUT"
  : > "$COMMAND_LOG"
}
step() {
  (cd "$GITHUB_WORKSPACE" && bash -eo pipefail "$work/$1.sh") > "$RUNNER_TEMP/step.log" 2>&1
}
pass() { echo "PASS: $1"; }
expect_failure() {
  if "$@"; then
    echo "Expected failure: $*" >&2
    exit 1
  fi
}

setup_case
step release-state
step release-package
step release-github
export PACKAGE_DIR
PACKAGE_DIR="$(sed -n 's/^package-dir=//p' "$GITHUB_OUTPUT")"
step release-forgebox
grep -Fxq "git push origin refs/tags/$VERSION" "$COMMAND_LOG"
[[ "$(grep -c '^gh release upload ' "$COMMAND_LOG")" == 2 ]]
grep -Fxq "gh release edit $VERSION --draft=false" "$COMMAND_LOG"
grep -q '^box forgebox publish$' "$COMMAND_LOG"
pass 'first publication creates tag, assets, and ForgeBox entry'

setup_case
TAG=head RELEASE_EXISTS=true ASSETS=complete
step release-state
step release-package
step release-github
expect_failure grep -Eq '^(git push|gh release (create|upload|edit)) ' "$COMMAND_LOG"
pass 'rerun verifies existing assets without replacing them or moving the tag'

setup_case
REGISTRY_STATUS=200 REGISTRY_PUBLISHED=true FORGEBOX_API_TOKEN=''
step release-package
grep -q '^forgebox-published=true$' "$GITHUB_OUTPUT"
pass 'an existing ForgeBox version needs no token or republication'

setup_case
TAG=ancestor
step release-state
grep -q '^publish=false$' "$GITHUB_OUTPUT"
pass 'later main commits with the same version are skipped'

setup_case
TAG=other
expect_failure step release-state
pass 'unrelated tags stop publication'

setup_case
REGISTRY_STATUS=503
expect_failure step release-package
expect_failure grep -q '^git push ' "$COMMAND_LOG"
REGISTRY_STATUS=404 GH_FAILURE=true
expect_failure step release-package
pass 'registry and GitHub API failures stop publication'

setup_case
printf corrupt >> "$GITHUB_WORKSPACE/build/distributions/$ARCHIVE"
expect_failure step release-package
expect_failure grep -q '^git push ' "$COMMAND_LOG"
pass 'checksum mismatch stops publication before tagging'

setup_case
TAG=head RELEASE_EXISTS=true ASSETS=complete CHANGED_ASSET=true
expect_failure step release-github
expect_failure grep -q '^gh release upload ' "$COMMAND_LOG"
pass 'different existing assets are never overwritten'

setup_case
FORGEBOX_API_TOKEN=''
expect_failure step release-package
expect_failure grep -q '^git push ' "$COMMAND_LOG"
pass 'first publication requires credentials before tagging'
