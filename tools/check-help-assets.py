#!/usr/bin/env python3
#
# check-help-assets.py - do the bundled help topics still match the ones the
# catalog publishes, and refresh them when they do not.
#
# WHY THIS EXISTS.  app/src/main/assets/help/ is the offline floor under the
# help system: the seven topics, and help_index.json, a copy of the `help`
# block of romwbw_disks' index-v0.json that HelpActivity reads when neither
# the live index nor its saved copy can be.  Nothing in this tree can tell
# whether those bytes still match what romwbw_disks publishes, because the
# answer is not in this tree.  ioscpm has had tools/check-help-assets.py for
# its own copy since its copy went stale once; this is the same check for
# this one, which nothing covered (romwbw_disks' CLAUDE.md said so).
#
# HOW THIS RUNS: BY HAND.  Nothing schedules it.  Run it before cutting a
# build, and after romwbw_disks republishes a topic.
#
# It does NOT check what the app fetches at run time: HelpTopicActivity
# verifies a downloaded topic against the index's size and sha256 itself.
# This is about the copy that ships.
#
# Usage:  tools/check-help-assets.py            refresh from the live catalog
#         tools/check-help-assets.py --check    report drift, change nothing
#
# EXIT CODES, matching tools/check-store-version.sh:
#   0  in sync (or refreshed)
#   1  the bundled copy contradicts the catalog        -> RED with --check
#   2  could not verify - network, rate limit, a shape -> warn, do not fail
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

INDEX_URL = "https://github.com/avwohl/romwbw_disks/releases/latest/download/index-v0.json"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, "app", "src", "main", "assets", "help")
BUNDLED_INDEX = "help_index.json"
EX_OK, EX_CONTRADICTION, EX_UNVERIFIED = 0, 1, 2


def unverified(msg):
    print("CANNOT VERIFY: %s" % msg, file=sys.stderr)
    sys.exit(EX_UNVERIFIED)


def get(url, limit=8 << 20):
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            body = r.read(limit + 1)
    except (urllib.error.URLError, OSError) as e:
        unverified("%s: %s" % (url, getattr(e, "reason", e)))
    if len(body) > limit:
        # A help topic is a few kilobytes.  Bounded before it is read so a
        # wrong URL cannot stream a disk image into memory.
        print("%s is larger than %d bytes - is that really the catalog?"
              % (url, limit), file=sys.stderr)
        sys.exit(EX_CONTRADICTION)
    return body


def read_bundled_index():
    """The bundled help_index.json, or None if it is missing or unreadable -
    which is drift like any other, not a reason to stop."""
    path = os.path.join(DEST, BUNDLED_INDEX)
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_bundled_index(doc, block):
    """help_index.json with the live help block, keeping every other member
    of the bundled document - its comment above all - as it was."""
    out = dict(doc) if isinstance(doc, dict) else {}
    out["help"] = block
    path = os.path.join(DEST, BUNDLED_INDEX)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="report drift instead of fixing it")
    ap.add_argument("--index-url",
                    default=os.environ.get("ROMWBW_INDEX_URL", INDEX_URL))
    args = ap.parse_args()

    try:
        index = json.loads(get(args.index_url).decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        unverified("the index is not valid JSON: %s" % e)

    block = index.get("help") or {}
    base = (block.get("base_url") or "").strip().rstrip("/")
    topics = block.get("topics") or []
    if not base or not topics:
        # The client treats a missing help block as "fall back", not as an
        # error, and so does this: it is a statement about the catalog, not a
        # contradiction with anything here.
        unverified("the catalog publishes no help block")

    stale, written = [], []
    for t in sorted(topics, key=lambda x: x.get("filename", "")):
        fn = (t.get("filename") or "").strip()
        want = (t.get("sha256") or "").lower()
        if not fn or "/" in fn or fn.startswith(".") or fn == BUNDLED_INDEX:
            print("the catalog names a filename this will not write: %r" % fn,
                  file=sys.stderr)
            return EX_CONTRADICTION
        if not want:
            print("%s: the catalog publishes no sha256 for it" % fn,
                  file=sys.stderr)
            return EX_CONTRADICTION

        dest = os.path.join(DEST, fn)
        have = None
        if os.path.exists(dest):
            with open(dest, "rb") as f:
                have = hashlib.sha256(f.read()).hexdigest()
        if have == want:
            continue

        stale.append(fn)
        if args.check:
            continue

        body = get("%s/%s" % (base, fn))
        got = hashlib.sha256(body).hexdigest()
        if got != want:
            # The catalog contradicting itself, not this repository being
            # behind.  Nothing is written.
            print("%s: sha256 %s, the catalog's own index says %s"
                  % (fn, got[:12], want[:12]), file=sys.stderr)
            return EX_CONTRADICTION
        tmp = dest + ".tmp"
        with open(tmp, "wb") as f:
            f.write(body)
        os.replace(tmp, dest)
        written.append(fn)

    # The bundled list, compared as data rather than bytes: it is this
    # repository's own file, formatted here, and only its help block is the
    # catalog's.
    doc = read_bundled_index()
    index_stale = not isinstance(doc, dict) or doc.get("help") != block
    if index_stale:
        stale.append(BUNDLED_INDEX)
        if not args.check:
            write_bundled_index(doc, block)
            written.append(BUNDLED_INDEX)

    # A bundled topic the catalog no longer names is left alone: deleting a
    # file the app may still be asked for is a decision, not a refresh.
    named = {(t.get("filename") or "").strip() for t in topics}
    extra = sorted(f for f in os.listdir(DEST)
                   if f.endswith(".md") and f not in named)
    if extra:
        print("note: bundled but no longer in the catalog: %s" % ", ".join(extra))

    rel = os.path.relpath(DEST, ROOT)
    if args.check:
        if stale:
            print("STALE against the catalog: %s" % ", ".join(stale))
            print("Run tools/check-help-assets.py to refresh them.")
            return EX_CONTRADICTION
        print("PASS: %s matches all %d help topic(s) the catalog publishes, "
              "and %s matches its help block" % (rel, len(topics), BUNDLED_INDEX))
        return EX_OK

    if written:
        print("refreshed: %s" % ", ".join(written))
    else:
        print("already in sync with the catalog (%d topic(s))" % len(topics))
    return EX_OK


if __name__ == "__main__":
    sys.exit(main())
