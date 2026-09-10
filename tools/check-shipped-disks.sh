#!/bin/sh
# check-shipped-disks.sh - does the disk image users actually download carry the
# current R8/W8?
#
# WHY THIS EXISTS.  On 2026-09-03 the R8 wildcard-erasure bug was fixed in
# romwbw_emu/src/r8.asm, built into a published ioscpm release (v1.4.12), and
# reached NO USER OF ANY PORT.  Every repository was telling the truth about its
# own layer - the source was fixed, the image was published, and each port's
# catalog pin still named an older release - and no check spanned the layers, so
# the same "it is fixed" / "no it is not" exchange ran five times.
#
# The specific failure this is built to catch happened here, in z80cpmw: the pin
# was bumped in the tree, the changelog and the commit both said in as many words
# that the packages predated it, and 1.0.23 shipped with the old pin anyway.  A
# note that has to be read at the right moment is not a gate.  So this checks the
# BUILT ARTIFACT as well as the tree, because that is the gap that won.
#
# Copy: this script checks every port in the table below, not just the one it
# is sitting in, so no repository can report "fixed" while its neighbour's
# users are on an old pin.  That is the whole reason it is duplicated.
#
# WHERE THE COPIES ARE.  Four repositories carry it - cpmdroid, ioscpm,
# romwbw_emu and z80cpmw.  romwbw_disks, which publishes the catalog this
# script is about, has never had one.  cpmemu had one and deleted it on
# 2026-09-10 (294ee01): it is not a row in the table below, tracks no disk
# image, and ran the script from no workflow, so its copy only ever reported
# on its neighbours.  Do not re-add it there.  Re-measure rather than trust
# this paragraph:
#     ls /Users/wohl/src/*/tools/check-shipped-disks.sh
#
# THE FOUR ARE NOT ONE SCRIPT, and have not been since 2026-09-07:
#     md5 -q /Users/wohl/src/*/tools/check-shipped-disks.sh | sort | uniq -c
# expects four lines, not one.  No hash is written here on purpose - every
# note that quoted one was wrong within a day.  No copy is a superset of the
# others either, so "edit one, copy to the rest" is a merge and not a cp, and
# taking any single copy wholesale loses something:
#   ioscpm      roms_in_tree(), and the only *.app arms in scan_artifact() and
#               scan_artifact_for() - the other three find an .app bundle and
#               cannot open it.
#   cpmdroid    detect_kind(), the only copy that reads a checkout to decide a
#               port's kind instead of trusting the table's kind column.
#   romwbw_emu  the only ports row for romwbw_emu itself, and the only check
#               that the v0 index publishes at least one release.
#   z80cpmw     nothing the other three lack.
# romwbw_disks/docs/RELEASING.md carries the family-wide inventory; correct it
# there when this changes, rather than in four headers that drift apart.
#
# THAT FALSE ALARM IS FIXED HERE, and here only so far.  Measured 2026-09-10 in
# all four copies: every one exited 1 reporting NO v0 INDEX URL for ioscpm and
# for cpmdroid, and both of those ports were correct.  Each had moved its index
# URL behind a re-export - ioscpm's EmulatorViewModel returns
# CatalogMigration.indexURL, cpmdroid's DiskCatalogRepository returns
# SettingsRepository.DEFAULT_INDEX_URL - and index_url_of() grepped only the one
# file the table names.  It was the table's file column that was stale, not the
# ports.  index_url_of() falls back to the checkout now when the named file holds
# no literal, which answers for both ports and needs no table edit the next time
# a constant moves.  Carry it to the other three copies as a merge and not a cp -
# see the paragraph above on what each of them holds that the others do not.
# Nothing runs this script in CI in any of the four repositories.
#
# WHY MIGRATED PORTS ARE ASKED A DIFFERENT QUESTION.  cpmdroid no longer pins an
# ioscpm release tag: it fetches romwbw_disks' index-v0.json and takes every URL
# out of the documents it names.  So `pin_of` finds no vX.Y.Z in its source, and
# before that was accounted for the cpmdroid row printed NO PIN FOUND and exited
# 1 for a port that was working correctly - a gate that cries wolf is a gate
# that stops being read, which is the exact failure this script was written
# after.
# Migrated ports carry kind "index-v0" in the table below, and the question
# changes with them: not "does the pin name the newest tag" but "does the source
# still name the v0 index, does that index answer, and is the legacy pin gone".
#
# z80cpmw HAS migrated and belongs in that row; ioscpm has NOT, and was moved
# into it by mistake on 2026-09-06.  That move was made to stop this script
# printing NO PIN FOUND at ports that were working correctly, and for z80cpmw it
# was right - note its file, the v0 index URL is in CatalogV0.cpp and NOT in
# DiskCatalog.cpp, where the old pin lived and where this table used to point.
# ioscpm was swept along with it without being read: as of 2026-09-07
# iOSCPM/Views/EmulatorViewModel.swift still says releaseTag = "v1.4.12" and
# still builds every URL as .../releases/download/<releaseTag>/disks.xml, and
# there is no index-v0.json anywhere in its Swift.  So the fix printed NO v0
# INDEX URL at ioscpm instead of NO PIN FOUND - the same false alarm at the same
# port in a different message.
#
# So ioscpm's row no longer states an answer: it says `auto`, and detect_kind()
# reads the checkout.  Twice in two days a hand-edited kind described the port
# wrongly and the gate cried wolf, and ioscpm's migration is expected within the
# day, so a third hand-edit was going to be wrong in whichever direction it was
# made.  The rule the two mistakes share is the same one: the row is a claim
# about the port, so either read the port before writing the row, or have the
# script read it for you.
#
# THE BUNDLED-ROM CHECK IS GONE, and this says what it was and was not covering
# so that removing it is not mistaken for coverage quietly dropped.  Until
# 2026-09-07 the index-v0 arm below also read the RomWBW release out of the
# port's bundled ROM - marker and version bytes, straight from the binary - and
# failed the port when the v0 index no longer published that release, because an
# offline first launch boots the bundled ROM and can then download nothing that
# matches it.  Then both ports that reach that arm stopped bundling a ROM on the
# same day: cpmdroid deleted app/src/main/assets/emu_avw.rom and z80cpmw deleted
# its roms/ directory, 2026-09-07.  The check had no file to read for either, so
# it printed CANNOT READ and exited 1 for two ports that were correct, and no
# port could pass it.  A check no port can pass is not a check.  ioscpm does
# still ship iOSCPM/Resources/emu_avw.rom, but it is a `tag` port and never
# reached this arm, so nothing that was being checked has been given up.  If a
# migrated port bundles a ROM again, this belongs back.
#
#   sh check-shipped-disks.sh              tree pins + any artifacts found
#   sh check-shipped-disks.sh --tree-only  skip artifact scanning
#
# Exit 0 = every port's pin names the newest published release, and every
#          artifact found agrees with its own tree.
# Exit 1 = a port is behind, or a built artifact disagrees with its tree.
# Exit 2 = could not verify (no network, no parser).  A gate that cannot verify
#          must not say yes.

set -u

CATALOG_REPO="avwohl/ioscpm"
API="https://api.github.com/repos/$CATALOG_REPO/releases"
DL="https://github.com/$CATALOG_REPO/releases/download"

TREE_ONLY=0
[ "${1:-}" = "--tree-only" ] && TREE_ONLY=1

fail=0
tmp=$(mktemp -d 2>/dev/null || mktemp -d -t pins)
trap 'rm -rf "$tmp"' EXIT INT TERM

# --- where the sibling checkouts are ------------------------------------------
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here" && git rev-parse --show-toplevel 2>/dev/null) || root="$here"
SRC=$(dirname "$root")

# --- fetching -----------------------------------------------------------------
get() { # $1 url, $2 dest
    if command -v curl >/dev/null 2>&1; then
        curl -sSfL -o "$2" "$1" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$2" "$1" 2>/dev/null
    else
        return 127
    fi
}

# --- parsing (awk, not GNU sed: these repos are read on Windows and macOS too) --
combo_sha() { # $1 = disks.xml
    tr -d '\r\n' < "$1" | awk '{
        n = split($0, part, "<disk>")
        for (i = 1; i <= n; i++)
            if (part[i] ~ /hd1k_combo\.img/ &&
                match(part[i], /<sha256>[0-9a-f]+<\/sha256>/)) {
                print substr(part[i], RSTART + 8, RLENGTH - 17); exit
            }
    }'
}

highest_version() { # tags on stdin
    awk '
    function vnum(s,   a, n, i, r) {
        sub(/^v/, "", s); n = split(s, a, "."); r = 0
        for (i = 1; i <= 4; i++) r = r * 1000 + (i <= n ? a[i] + 0 : 0)
        return r
    }
    { v = vnum($0); if (v > best) { best = v; bt = $0 } }
    END { if (bt != "") print bt }'
}

# --- the ports that pin a catalog ---------------------------------------------
# port | file relative to its checkout | grep pattern for the line | kind
#
# kind "tag"      - still pins an ioscpm release tag and downloads disks.xml.
# kind "index-v0" - migrated to romwbw_disks' two-level catalog; there is no tag
#                   in its source to compare, and looking for one is how this
#                   script would silently stop covering it.
#
# kind "auto"     - decide by reading the checkout, for a port that is mid-move.
#                   ioscpm is on the tag arrangement today and is expected to
#                   migrate imminently, and a hardcoded kind reports whichever
#                   side of that move it is not on as broken - the same cry-wolf
#                   failure this table has now produced twice at the same port,
#                   in two different messages. Deciding at run time costs one
#                   grep and needs no edit on the day it lands.
ports='ioscpm|iOSCPM/Views/EmulatorViewModel.swift|releaseTag[[:space:]]*=|auto
cpmdroid|app/src/main/java/com/awohl/cpmdroid/data/DiskCatalogRepository.kt|INDEX_URL[[:space:]]*=|index-v0
z80cpmw|z80cpmw/CatalogV0.cpp|INDEX_URL[[:space:]]*=|index-v0'

# Which arrangement a checkout is actually on. Answered from the source rather
# than from the table, and only for kind "auto". A port counts as migrated once
# any of its sources names romwbw_disks' index: the constant may be called
# anything and may sit in a file this table does not name, so the search is the
# whole checkout minus the noise. Prose is excluded deliberately - every one of
# these repos has a README or a CHANGELOG describing the migration, and matching
# those would call a port migrated for talking about it.
detect_kind() { # $1 = port dir -> prints tag | index-v0
    if grep -rlE 'romwbw_disks/releases/download/catalog-v0|index-v0[.]json' "$1" \
         --exclude-dir=.git --exclude-dir=build --exclude-dir=DerivedData \
         --exclude='*.md' --exclude='*.txt' >/dev/null 2>&1
    then
        echo index-v0
    else
        echo tag
    fi
}

pin_of() { # $1 = port dir, $2 = file, $3 = pattern -> prints vX.Y.Z
    f="$1/$2"
    [ -f "$f" ] || return 1
    grep -E "$3" "$f" 2>/dev/null |
        grep -v '^[[:space:]]*[/*#]' |
        sed -n 's/.*"\(v[0-9][0-9.]*\)".*/\1/p' | head -1
}

# --- migrated ports -----------------------------------------------------------
# The whole file rather than one line: the URL is a multi-line constant in
# Kotlin, so grepping the line that names it finds the name and not the value.
# Comment lines are dropped for both of these, because the file that replaced
# the pin explains in prose what it replaced - "RELEASE_TAG = \"v1.4.12\"" is
# still written there, and matching it would report a leftover pin forever.
index_url_of() { # $1 = port dir -> prints the v0 index URL the port compiles in
    # ASK THE CHECKOUT, NOT THE TABLE.  This used to grep one file named in the
    # ports table, and every port that moved its literal - ioscpm behind
    # CatalogMigration, cpmdroid behind SettingsRepository - was reported as
    # "NO v0 INDEX URL ... repointed back, or renamed?" for having exactly one
    # source of truth.  The table's file column was what had gone stale.
    #
    # TESTS ARE EXCLUDED, and that is not tidiness.  cpmdroid's IndexUrlTest
    # asserts the default names no release tag, and to do it carries fixture
    # URLs on a deliberately fake fork - "github.com/someone/romwbw_disks/...".
    # grep -r reaches it before the real source, so the checker fetched the
    # fake one and reported the port's index UNREACHABLE.  That is the
    # cry-wolf failure this file's header spends thirty lines warning about,
    # arriving through the scan that was meant to end it.
    #
    # This script is excluded too: it quotes the URL pattern itself.
    #
    # Prose is excluded for the same reason detect_kind excludes it: every one
    # of these repositories documents the URL in a README or a changelog, and
    # matching that would report an index URL for a port that compiles in none.
    for f in $(grep -rlE '"https://[^"]*index-v0[.]json"' "$1" \
            --exclude-dir=.git --exclude-dir=build --exclude-dir=DerivedData \
            --exclude-dir=.gradle --exclude-dir=node_modules \
            --exclude='*.md' --exclude='*.txt' --exclude='*.json' \
            --exclude='check-shipped-disks.sh' 2>/dev/null |
            grep -vE '/([Tt]ests?|androidTest)/' | sort); do
        u=$(grep -hE '"https://[^"]*index-v0[.]json"' "$f" 2>/dev/null |
            grep -v '^[[:space:]]*[/*#]' |
            sed -n 's|.*"\(https://[^"]*index-v0\.json\)".*|\1|p' | head -1)
        if [ -n "$u" ]; then printf '%s\n' "$u"; return 0; fi
    done
    return 1
}

legacy_pin_in() { # $1 = port dir, $2 = file -> prints a vX.Y.Z still in the source
    f="$1/$2"
    [ -f "$f" ] || return 1
    grep -v '^[[:space:]]*[/*#]' "$f" 2>/dev/null |
        sed -n 's/.*"\(v[0-9]\{1,\}\.[0-9]\{1,\}\.[0-9]\{1,\}\)".*/\1/p' | head -1
}

# --- artifacts: what users actually got ---------------------------------------
# A pin is a wide string on Windows and a UTF-8 one elsewhere, so look for both.
scan_artifact() { # $1 = file -> prints every vN.N.N found
    f="$1"
    case "$f" in
        *.msix|*.apk|*.zip|*.aab)
            command -v unzip >/dev/null 2>&1 || return 1
            unzip -o -qq "$f" -d "$tmp/x" 2>/dev/null || return 1
            find "$tmp/x" -type f 2>/dev/null | while read -r m; do scan_bytes "$m"; done
            rm -rf "$tmp/x" ;;
        *) scan_bytes "$f" ;;
    esac
}

scan_bytes() {
    # Both encodings, always.  z80cpmw's RELEASE_TAG is a std::wstring, so it
    # sits in the binary as UTF-16LE and a plain byte grep does not see it - the
    # first version of this script silently skipped the one artifact that was
    # wrong.  Stripping NULs collapses ASCII UTF-16LE to ASCII, which is enough
    # to find a tag and needs no strings(1).
    {
        grep -a -oE 'v1\.[0-9]+\.[0-9]+' "$1" 2>/dev/null
        tr -d '\000' < "$1" 2>/dev/null | grep -a -oE 'v1\.[0-9]+\.[0-9]+' 2>/dev/null
    }
}

# Does a built artifact contain this pattern anywhere inside it?  For a migrated
# port the evidence is presence, not equality: a build made before the repoint
# carries the old tag and none of the new URL, so "does not name index-v0.json"
# is what identifies a stale package.  The variable is spat, not pat, because sh
# has no locals and pat belongs to the caller's read loop.
scan_artifact_for() { # $1 = file, $2 = ERE -> exit 0 when found
    spat="$2"
    case "$1" in
        *.msix|*.apk|*.zip|*.aab)
            command -v unzip >/dev/null 2>&1 || return 1
            rm -rf "$tmp/y"
            mkdir -p "$tmp/y"
            unzip -o -qq "$1" -d "$tmp/y" 2>/dev/null || { rm -rf "$tmp/y"; return 1; }
            rm -f "$tmp/y.hit"
            find "$tmp/y" -type f 2>/dev/null | while read -r m; do
                if grep -a -qE "$spat" "$m" 2>/dev/null ||
                   tr -d '\000' < "$m" 2>/dev/null | grep -a -qE "$spat" 2>/dev/null; then
                    : > "$tmp/y.hit"
                fi
            done
            rm -rf "$tmp/y"
            [ -f "$tmp/y.hit" ] ;;
        *)
            grep -a -qE "$spat" "$1" 2>/dev/null && return 0
            tr -d '\000' < "$1" 2>/dev/null | grep -a -qE "$spat" 2>/dev/null ;;
    esac
}

artifacts_for() { # $1 = port name, $2 = checkout
    # Only artifacts for the version the tree currently claims.  An older
    # package SHOULD carry an older pin - it was right when it was built - and
    # failing on those would make this noisy enough to be ignored, which is how
    # the last check stopped being read.
    case "$1" in
        z80cpmw)
            v=$(awk '/^[[:space:]]*#define[[:space:]]+VERSION_(MAJOR|MINOR|PATCH)[[:space:]]/ {print $3}' \
                    "$2/z80cpmw/Version.h" 2>/dev/null | paste -sd. - 2>/dev/null)
            # z80cpmw.msix is the Store package and carries no version in its
            # name, so it is always a candidate: it is whatever was built last.
            ls "$2/dist/z80cpmw.msix" 2>/dev/null
            [ -n "$v" ] && ls "$2/dist/z80cpmw-$v-beta.msix" 2>/dev/null
            ls "$2/bin/Release/z80cpmw.exe" 2>/dev/null ;;
        cpmdroid)
            # build/outputs is overwritten by each build, so what is there is current.
            find "$2/app/build/outputs" \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null ;;
        ioscpm)
            find "$2/build" "$2/DerivedData" -name '*.app' -prune 2>/dev/null ;;
    esac
}

echo "Disk catalog pins vs what $CATALOG_REPO publishes"
echo

# --- newest published release --------------------------------------------------
if ! get "$API?per_page=100" "$tmp/rel.json"; then
    echo "CANNOT VERIFY: no network, or neither curl nor wget is installed."
    echo "This gate does not pass when it cannot check."
    exit 2
fi

newest=$(grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' "$tmp/rel.json" |
         sed 's/.*"\([^"]*\)"$/\1/' | highest_version)

if [ -z "$newest" ]; then
    echo "CANNOT VERIFY: could not read a release tag out of the GitHub API."
    echo "Rate-limited (60/hr unauthenticated), or the response shape changed."
    exit 2
fi

if ! get "$DL/$newest/disks.xml" "$tmp/newest.xml"; then
    echo "CANNOT VERIFY: $newest publishes no disks.xml."
    exit 2
fi
newest_sha=$(combo_sha "$tmp/newest.xml")
if [ -z "$newest_sha" ]; then
    echo "CANNOT VERIFY: no hd1k_combo.img sha256 in $newest's disks.xml."
    exit 2
fi

echo "newest published release: $newest"
echo "  hd1k_combo.img          $(echo "$newest_sha" | cut -c1-16)..."
echo

# --- each port -----------------------------------------------------------------
echo "$ports" | while IFS='|' read -r port file pat kind; do
    [ -n "$port" ] || continue
    dir="$SRC/$port"

    if [ ! -d "$dir" ]; then
        printf '%-10s NOT CHECKED OUT beside this repo - cannot verify its pin\n' "$port"
        echo 1 > "$tmp/fail"
        continue
    fi

    if [ "$kind" = "auto" ]; then
        kind=$(detect_kind "$dir")
    fi

    # --- migrated ports ---------------------------------------------------------
    # A different question, asked because the old one has no answer here.  All
    # four checks fail loudly; none of them can pass by finding nothing, which is
    # what a grep for a deleted constant does.
    if [ "$kind" = "index-v0" ]; then
        idx=$(index_url_of "$dir" "$file")
        if [ -z "$idx" ]; then
            printf '%-10s NO v0 INDEX URL in %s - repointed back, or renamed?\n' "$port" "$file"
            echo 1 > "$tmp/fail"
            continue
        fi

        leftover=$(legacy_pin_in "$dir" "$file")
        if [ -n "$leftover" ]; then
            printf '%-10s LEFTOVER PIN %s in %s alongside the v0 index\n' "$port" "$leftover" "$file"
            echo 1 > "$tmp/fail"
            continue
        fi

        if ! get "$idx" "$tmp/$port-index.json"; then
            printf '%-10s v0 INDEX UNREACHABLE: %s\n' "$port" "$idx"
            echo 1 > "$tmp/fail"
            continue
        fi

        # Reachable is not the same as right, and this is the one assertion the
        # deleted bundled-ROM check was making for free: it grepped this document
        # for a romwbw_version, which incidentally proved the URL served a
        # catalog index and not something a server was merely willing to hand
        # back with a 200.  Asked directly now, so that removing that check did
        # not quietly downgrade this row to "the host answered".  Whitespace is
        # stripped first, so it does not depend on how the generator indents.
        if tr -d ' \n' < "$tmp/$port-index.json" |
                grep -q '"romwbw_versions":\['; then
            printf '%-10s v0 index answers and names its releases, no legacy pin\n' "$port"
        else
            printf '%-10s v0 INDEX IS NOT A CATALOG INDEX: %s\n' "$port" "$idx"
            printf '%-10s   it fetched, but carries no romwbw_versions[] in it\n' ""
            echo 1 > "$tmp/fail"
            continue
        fi

        # And what shipped?  A build made before the migration still carries the
        # old tag and none of the new URL, so the presence of the index URL is
        # the thing to look for - an artifact that does not name it is stale.
        [ "$TREE_ONLY" = "1" ] && continue
        artifacts_for "$port" "$dir" 2>/dev/null | while read -r a; do
            [ -f "$a" ] || continue
            echo x >> "$tmp/scanned"
            if scan_artifact_for "$a" 'index-v0\.json'; then
                printf '%-10s   artifact %s names the v0 index, agrees with the tree\n' \
                       "" "$(basename "$a")"
            else
                printf '%-10s   ARTIFACT PREDATES THE MIGRATION: %s does not name index-v0.json\n' \
                       "" "$(basename "$a")"
                printf '%-10s   that artifact was built before the repoint. Rebuild before shipping.\n' ""
                echo 1 > "$tmp/fail"
            fi
        done
        continue
    fi

    pin=$(pin_of "$dir" "$file" "$pat")
    if [ -z "$pin" ]; then
        printf '%-10s NO PIN FOUND in %s\n' "$port" "$file"
        echo 1 > "$tmp/fail"
        continue
    fi

    # What does that pin actually serve?  Compare the image, not the tag: two
    # tags can carry identical bytes (v1.4.5 and v1.4.11 do), and a port on an
    # older tag serving the same image is not behind in any way a user can feel.
    if get "$DL/$pin/disks.xml" "$tmp/$port.xml"; then
        sha=$(combo_sha "$tmp/$port.xml")
    else
        sha=""
    fi

    if [ -z "$sha" ]; then
        printf '%-10s pin %-9s BUT THAT TAG SERVES NO CATALOG - users get a 404\n' "$port" "$pin"
        echo 1 > "$tmp/fail"
    elif [ "$sha" = "$newest_sha" ]; then
        printf '%-10s pin %-9s current (same hd1k_combo.img as %s)\n' "$port" "$pin" "$newest"
    else
        printf '%-10s pin %-9s BEHIND - serves a different hd1k_combo.img than %s\n' \
               "$port" "$pin" "$newest"
        printf '%-10s   users of this port download %s...\n' "" "$(echo "$sha" | cut -c1-16)"
        printf '%-10s   the current image is        %s...\n' "" "$(echo "$newest_sha" | cut -c1-16)"
        printf '%-10s   fix: set the pin in %s\n' "" "$file"
        echo 1 > "$tmp/fail"
    fi

    # --- and what shipped? ------------------------------------------------------
    # The tree being right is not the same as the artifact being right, which is
    # exactly how z80cpmw 1.0.23 went out with the old pin while its source had
    # the new one.
    [ "$TREE_ONLY" = "1" ] && continue
    artifacts_for "$port" "$dir" 2>/dev/null | while read -r a; do
        [ -f "$a" ] || continue
        echo x >> "$tmp/scanned"
        found=$(scan_artifact "$a" | sort -u | tr '\n' ' ')
        case " $found " in
            *" $pin "*)
                printf '%-10s   artifact %s carries %s, agrees with the tree\n' \
                       "" "$(basename "$a")" "$pin" ;;
            "  ")
                : ;;  # nothing version-shaped in it; not evidence either way
            *)
                printf '%-10s   ARTIFACT DISAGREES: %s carries [%s], tree says %s\n' \
                       "" "$(basename "$a")" "$(echo "$found" | sed 's/ *$//')" "$pin"
                printf '%-10s   that artifact was built before the pin moved. Rebuild before shipping.\n' ""
                echo 1 > "$tmp/fail" ;;
        esac
    done
done

echo
if [ -f "$tmp/fail" ]; then
    echo "A port serves an image older than the newest published one, or shipped a"
    echo "binary built before its own pin moved."
    echo
    echo "Bumping a pin is not shipping it: the edit reaches users only in a release"
    echo "that carries it. Check this again after building, not only after editing."
    exit 1
fi

# FOUND NOTHING AND CHECKED NOTHING MUST NOT READ THE SAME.  The whole stated
# reason this script exists is checking the built artifact, and artifacts_for()
# globs local build paths only - dist/, bin/Release/, app/build/outputs, build/,
# DerivedData.  On a machine that cannot build a given port, and that is every
# machine for at least two of the three, those paths are simply absent: the loop
# runs zero times, prints nothing, and the run used to end on "every artifact
# found agrees with its own tree".  True, and it reads as though the packages
# were inspected and passed.  The count below is what tells the two apart.
scanned=0
[ -f "$tmp/scanned" ] && scanned=$(wc -l < "$tmp/scanned" | tr -d ' ')

echo "Every port's tree names the current catalog."
if [ "$TREE_ONLY" = "1" ]; then
    echo
    echo "NO PACKAGE WAS INSPECTED: --tree-only was given, so the half of this"
    echo "check that looks at what users actually got did not run.  The trees"
    echo "being right is not the same as the artifacts being right - that is the"
    echo "gap z80cpmw 1.0.23 went out through."
elif [ "$scanned" -gt 0 ]; then
    echo "All $scanned artifact(s) found agree with their own tree."
else
    echo
    echo "NO PACKAGE WAS INSPECTED, and this is NOT a pass of the artifact half."
    echo "artifacts_for() found nothing under dist/, bin/Release/,"
    echo "app/build/outputs, build/ or DerivedData in any port, which is the"
    echo "normal state on a machine that cannot build them - MSVC, the Android"
    echo "SDK and Xcode are three different machines.  Nothing here has looked"
    echo "at a byte any user will run.  Re-run this where a package was just"
    echo "built, or unpack a published one by hand."
fi
exit 0
