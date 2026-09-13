#!/bin/sh
# unreleased.sh - what is finished here but not yet in anybody's hands?
#
# WHY THIS EXISTS.  "Done" means four different things and the gap between them
# is where the mistakes come from: written, built, uploaded, served.
# build.gradle.kts describes the TREE.  Only what Play serves is what a user
# has, and this port's shipped figure is the weakest-evidenced of the family:
# nothing here queries Play's serving version directly, so
# check-store-version.sh reads the listing and CHANGELOG.md maps it to a
# versionCode.
#
# THIS IS ONE OF SIX AND THEY ARE DELIBERATELY DIFFERENT.  Every port ships on
# its own channel, so each repository's unreleased.sh is written for its own
# channel rather than copied.  check-shipped-disks.sh was "one file in five
# repos", diverged into four that no two of which agreed, and "the check
# passed" came to mean four different things.  Do not try to unify these.
#
#   sh tools/unreleased.sh
#
# HOW THIS RUNS: BY HAND, AND IT MUST STAY THAT WAY.  Not wired to any
# workflow, and the exit codes are shaped so it cannot usefully become one.
#
# Exit 0 = it measured, INCLUDING when the answer is "eleven commits
#          unreleased".  That is the normal state of a working repository.
#          Four jobs in this family went red daily for the normal state and all
#          four were deleted on 2026-09-13; do not rebuild one out of this.
# Exit 2 = could not measure.  Nothing is asserted when nothing was read.
#
# There is no exit 1.

set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here" && git rev-parse --show-toplevel 2>/dev/null) || {
    echo "CANNOT MEASURE: $here is not inside a git checkout."; exit 2; }

echo "cpmdroid - Google Play"
echo

store_out=$(sh "$here/check-store-version.sh" 2>&1)
store_rc=$?
echo "$store_out" | sed 's/^/  /'
echo
if [ "$store_rc" = 2 ]; then
    echo "  Play could not be measured, so nothing below would mean anything."
    exit 2
fi

live=$(echo "$store_out" | sed -n 's/^  serves  *\([0-9][0-9.]*\).*/\1/p' | head -1)
if [ -z "${live:-}" ]; then
    echo "  CANNOT MEASURE: no 'serves' line in the store output."
    exit 2
fi

# The Play channel leaves no git tag behind - this repository's tags stop at
# v1.24 and are not upload markers - so the anchor is the commit that first set
# versionName to the served value.
anchor=$(git -C "$root" log --format=%H --reverse -S"versionName = \"$live\"" \
             -- app/build.gradle.kts 2>/dev/null | head -1)
if [ -z "${anchor:-}" ]; then
    echo "  CANNOT MEASURE: no commit sets versionName to \"$live\"."
    exit 2
fi

short=$(git -C "$root" rev-parse --short "$anchor")
code=$(git -C "$root" show "$anchor:app/build.gradle.kts" 2>/dev/null |
       sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' | head -1)
echo "  $live first appears at $short (versionCode ${code:-unknown}) - $(git -C "$root" log -1 --format=%s "$anchor")"
echo

n=$(git -C "$root" rev-list --count "$anchor..HEAD" 2>/dev/null)
if [ "${n:-0}" = "0" ]; then
    echo "  Nothing since.  The tree is what Play serves."
else
    echo "  $n commit(s) since that build:"
    echo
    git -C "$root" log --format='    %h  %ad  %s' --date=short "$anchor..HEAD"
    echo
    app_n=$(git -C "$root" rev-list --count "$anchor..HEAD" -- app/ 2>/dev/null)
    echo "  Of those, $app_n touch app/ - the application itself."
    if [ "${app_n:-0}" != "0" ]; then
        git -C "$root" log --format='      %h  %s' "$anchor..HEAD" -- app/
        echo
        echo "  Those are features and fixes no Play user has.  Play refuses an"
        echo "  upload at a versionCode it has seen, so shipping them starts"
        echo "  with moving the number."
    fi
fi
echo

exit 0
