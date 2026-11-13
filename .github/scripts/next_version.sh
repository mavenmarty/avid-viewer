#!/usr/bin/env bash
# Works out the app's semantic version (major.minor.patch) from the commits since the last release.
#
#   feat: …            a new feature        → minor  (1.1.0 → 1.2.0)
#   fix: … (anything else: perf, docs, ci, test, refactor, chore, or no type)
#                                           → patch  (1.1.0 → 1.1.1)
#   feat!: … / fix!: … or a "BREAKING CHANGE:" line in the message
#                                           → major  (1.1.0 → 2.0.0)
#
# Release tags are v<major>.<minor>.<patch>.<build>, e.g. v1.1.0.190. The build number is the
# Android versionCode (the CI run number). Avid Viewer 1.0.179 and older read the build number
# from the tag's last part to find updates, so it has to stay there. Tags from before semantic
# versions (v1.0.179) count as version 1.0.179.
#
# Prints "version=<x.y.z>" and "tag=v<x.y.z>.<build>" (also to $GITHUB_OUTPUT when set).
# Usage: next_version.sh <build number>    (needs full history and tags)
set -euo pipefail
build="${1:?build number}"
last=$(git tag --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$' | sort -t. -k1.2,1n -k2,2n -k3,3n -k4,4n | tail -1 || true)
if [ -z "$last" ]; then
  major=1; minor=0; patch=0; range="HEAD"
else
  IFS=. read -r major minor patch _ <<< "${last#v}"
  range="$last..HEAD"
fi
log=$(git log --no-merges --format='%s%n%b%n--' $range)
subjects=$(git log --no-merges --format='%s' $range)
if [ -z "$subjects" ] && [ -n "$last" ]; then
  echo "No commits since $last" >&2
fi
if grep -qE '^[a-z]+(\([^)]*\))?!:' <<< "$subjects" || grep -q '^BREAKING CHANGE:' <<< "$log"; then
  major=$((major + 1)); minor=0; patch=0
elif grep -qE '^feat(\([^)]*\))?:' <<< "$subjects"; then
  minor=$((minor + 1)); patch=0
else
  patch=$((patch + 1))
fi
version="$major.$minor.$patch"
tag="v$version.$build"
echo "version=$version"
echo "tag=$tag"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  { echo "version=$version"; echo "tag=$tag"; } >> "$GITHUB_OUTPUT"
fi
