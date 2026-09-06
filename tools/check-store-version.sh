#!/bin/sh
# check-store-version.sh - what does Google Play actually serve, and does
# anything in this tree claim otherwise?
#
# WHY THIS EXISTS.  A versionName in build.gradle.kts, a CHANGELOG heading and a
# shipped: field in z80cpmw/FEATURE_PARITY.md all describe the TREE.  None of
# them knows what a user can install.  ioscpm has measured its store since
# 2026-09-03 and z80cpmw since 2026-09-06 - and z80cpmw's first measurement found
# its own changelog wrong by two releases, which had already sent a column re-read
# to the wrong commit.  This port was the last one still asserting.
#
#   sh tools/check-store-version.sh
#
# Exit 0 = measured, and nothing recorded here claims a version Play does not
#          serve.  The tree being AHEAD is normal - you always build before you
#          ship - and is reported, not failed.
# Exit 1 = something records a shipped state that contradicts the measurement.
# Exit 2 = could not verify (no network, no curl/wget, listing unreadable).
#          A gate that cannot verify must not say yes.
#
# HOW IT MEASURES, and why it is the weakest of the three.  Play has no public
# version API, so this reads the store listing HTML and pulls the version out of
# the page's own data blob - the "141" key, which is where the Play web client
# keeps it.  That is a scrape and it will break when Google changes the page;
# when it does, this exits 2 rather than guessing.  Apple gives ioscpm a real
# lookup endpoint and Microsoft gives z80cpmw DisplayCatalog; this one is a
# best effort at the same question.

set -u

APP_ID="com.awohl.cpmdroid"
DEVELOPER="Aaron Wohl"
LISTING="https://play.google.com/store/apps/details?id=$APP_ID&hl=en&gl=US"
UA="Mozilla/5.0 (X11; Linux x86_64)"

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here" && git rev-parse --show-toplevel 2>/dev/null) || root=$(dirname "$here")

GRADLE="$root/app/build.gradle.kts"
CHANGELOG="$root/CHANGELOG.md"
PARITY="$root/../z80cpmw/FEATURE_PARITY.md"

tmp=$(mktemp -d 2>/dev/null || mktemp -d -t play)
trap 'rm -rf "$tmp"' EXIT INT TERM

status=0

get() {
    if command -v curl >/dev/null 2>&1; then
        curl -sSfL -m 60 -A "$UA" -o "$2" "$1" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -qT 60 -U "$UA" -O "$2" "$1" 2>/dev/null
    else
        return 127
    fi
}

# --- what Play serves ----------------------------------------------------------
if ! get "$LISTING" "$tmp/play.html"; then
    echo "CANNOT VERIFY: no network, or neither curl nor wget is installed."
    echo "This gate does not pass when it cannot check."
    exit 2
fi

# Identity first.  A listing that is not ours cannot answer for us, and a
# redirect to the store front page would otherwise be parsed for any number
# that happens to look like a version.
if ! grep -q "$APP_ID" "$tmp/play.html"; then
    echo "CANNOT VERIFY: the listing fetched does not mention $APP_ID."
    echo "Redirected, region-blocked, or the app is unpublished."
    exit 2
fi
if ! grep -q "$DEVELOPER" "$tmp/play.html"; then
    echo "CANNOT VERIFY: the listing for $APP_ID does not name $DEVELOPER."
    echo "Either the page shape changed or this id now belongs to someone else."
    exit 2
fi

live=$(tr '\n' ' ' < "$tmp/play.html" |
       grep -o '"141":\[\[\["[0-9][0-9.]*"\]\]' |
       head -1 | sed 's/.*\["//; s/"\]\]//')

if [ -z "$live" ]; then
    echo "CANNOT VERIFY: no version in the listing's data blob for $APP_ID."
    echo "Play shows 'Varies with device', or the page shape changed."
    echo "Do not replace this with a guess - fix the reader or leave it at 2."
    exit 2
fi

echo "Google Play, $APP_ID ($DEVELOPER)"
echo "  serves           $live"

# --- what the tree claims to be ------------------------------------------------
vname=$(grep -o 'versionName *= *"[^"]*"' "$GRADLE" 2>/dev/null | sed 's/.*"\(.*\)"/\1/' | head -1)
vcode=$(grep -o 'versionCode *= *[0-9]*' "$GRADLE" 2>/dev/null | sed 's/.*= *//' | head -1)
if [ -z "$vname" ] || [ -z "$vcode" ]; then
    echo "CANNOT VERIFY: no versionName/versionCode in $GRADLE."
    exit 2
fi
echo "  this tree        $vname (versionCode $vcode)"

# Play reports a versionName; every other record here is keyed by versionCode,
# and comparing the two is how z80cpmw's sibling-readings field went wrong in
# the first place.  CHANGELOG.md carries the mapping in its headings:
#   ## Version 1.25 (versionCode 27)
livecode=$(awk -v v="$live" '
    $0 ~ "^## Version " v " \\(versionCode [0-9]+\\)" {
        match($0, /versionCode [0-9]+/)
        print substr($0, RSTART + 12, RLENGTH - 12); exit }' "$CHANGELOG" 2>/dev/null)

if [ -n "$livecode" ]; then
    echo "  which is         versionCode $livecode  (CHANGELOG.md maps it)"
else
    echo "  which is         versionCode unknown - no CHANGELOG heading for $live"
fi

# --- the gap -------------------------------------------------------------------
echo
if [ -n "$livecode" ] && [ "$vcode" -lt "$livecode" ] 2>/dev/null; then
    echo "TREE IS BEHIND PLAY: versionCode $vcode < shipped $livecode."
    echo "  Not a normal state.  A release went out from another checkout, or"
    echo "  somebody edited the number downward."
    status=1
elif [ -n "$livecode" ] && [ "$vcode" = "$livecode" ]; then
    echo "The tree and Play are on the same versionCode."
    echo "  Play refuses an upload at a versionCode it has seen, so the tree as"
    echo "  it stands cannot be shipped: the number has to move first."
else
    echo "The tree is ahead of Play. That is normal - you build before you ship."
    echo "  What is NOT normal is writing versionCode $vcode into anything that"
    echo "  records what USERS have.  Uploaded is not released."
fi

# --- what the family records ---------------------------------------------------
if [ -f "$PARITY" ]; then
    claim=$(awk '/^cpmdroid[[:space:]]/ { for (i = 1; i <= NF; i++)
                    if ($i ~ /^shipped:/) { print substr($i, 9); exit } }' "$PARITY")
    echo
    if [ -z "$claim" ]; then
        echo "z80cpmw/FEATURE_PARITY.md  no shipped: field on the cpmdroid line"
    elif [ -z "$livecode" ]; then
        echo "z80cpmw/FEATURE_PARITY.md  shipped:$claim - cannot be checked, no versionCode for $live"
    elif [ "$claim" = "$livecode" ]; then
        echo "z80cpmw/FEATURE_PARITY.md  shipped:$claim agrees with what Play serves"
    else
        echo "z80cpmw/FEATURE_PARITY.md  CLAIMS shipped:$claim, BUT Play serves $live = versionCode $livecode"
        echo "  That field is compared against a versionCode, so a versionName in"
        echo "  it reads as a wildly older build.  Re-read the column at the"
        echo "  shipped commit, then set this."
        status=1
    fi
fi

echo
if [ "$status" != 0 ]; then
    echo "Something records a shipped state Play does not support."
    exit 1
fi
exit 0
