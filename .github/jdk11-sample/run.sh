#!/usr/bin/env bash
# Runs `test pitestDiff` on this sample through the init script in <dist>, with the Gradle command
# given, and fails unless the output carries the mutant and the coverage counts this sample leaves.
# Gradle and the tests run on the JDK that JAVA_HOME points at.
#
# Usage: run.sh <gradle command> <dist directory>
set -euo pipefail

gradle=$1
dist=$(cd "$2" && pwd)
sample=$(cd "$(dirname "$0")" && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -R "$sample/." "$work"
rm "$work/run.sh"
cd "$work"

# Every line of the sample is a changed line against an empty first commit.
git init -q
git -c user.name=ci -c user.email=ci@localhost commit -q --allow-empty -m base
git add -A
git -c user.name=ci -c user.email=ci@localhost commit -q -m change

"$gradle" --version
"$gradle" --no-daemon --init-script "$dist/cribro.init.gradle" --quiet \
  test pitestDiff -Pcribro.diffBase=HEAD~1 | tee output.txt

status=0
for expected in \
  'survived: changed conditional boundary, in max' \
  '2/4 changed lines executed, 1/2 branches covered'; do
  if ! grep -qF "$expected" output.txt; then
    echo "The output of test pitestDiff lacks: $expected" >&2
    status=1
  fi
done
exit $status
