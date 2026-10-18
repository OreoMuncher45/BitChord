#!/usr/bin/env python3
"""
UI parity checker.

Three shells now exist — Apple Music, Classipod, BitChord Legacy — and the
failure mode that matters is not a crash, it is a button that exists in one and
does nothing in another. That failure is silent, survives the whole test suite,
and is only ever found by a person tapping every control on every screen.

So this is the check: inventory every destination, overlay, top-bar action and
callback the legacy shell can reach, then confirm the Apple shell can reach each
one too. Anything unreported as PORTED is a gap, and `--strict` turns gaps into
a non-zero exit so CI can hold the line.

    python3 tools/verify_ui_parity.py            # report
    python3 tools/verify_ui_parity.py --strict   # exit 1 on any gap

Deliberately source-level rather than runtime. Composable calls cannot be
reflected on, and a UI test that had to boot the app to ask "is Settings
reachable" would be far slower and far less honest than reading the branch that
decides it.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MAIN = REPO / "app/src/main/java/com/music/bitchord/MainActivity.kt"

# The branch that switches shells. Everything before it is shared; the Apple
# shell is the first branch, Classipod the second, legacy the `else`.
APPLE_MARKER = "com.music.bitchord.data.settings.AppUi.APPLE_MUSIC"
CLASSIPOD_MARKER = "com.music.bitchord.data.settings.AppUi.CLASSIPOD"


@dataclass
class Gap:
    kind: str
    name: str
    detail: str
    legacy_line: int = 0


@dataclass
class Report:
    ported: list[str] = field(default_factory=list)
    gaps: list[Gap] = field(default_factory=list)
    shared: list[str] = field(default_factory=list)

    def mark(self, kind: str, name: str, detail: str, ok: bool, line: int = 0) -> None:
        if ok:
            self.ported.append(f"{kind}: {name}")
        else:
            self.gaps.append(Gap(kind, name, detail, line))


def read_source() -> str:
    if not MAIN.exists():
        sys.exit(f"cannot find {MAIN}")
    return MAIN.read_text(encoding="utf-8")


def split_shells(src: str) -> tuple[str, str, str]:
    """
    Returns (shared_prefix, apple_branch, classipod_and_legacy).

    The Apple branch is delimited by its own marker up to the Classipod `else
    if`, because that is the only boundary in the file that means "the Apple
    shell's implementation ends here".
    """
    apple_at = src.find(APPLE_MARKER)
    if apple_at == -1:
        sys.exit("Apple shell branch not found — has MainActivity been restructured?")
    # walk back to the start of the `if (` that introduced it
    line_start = src.rfind("\n", 0, apple_at)
    apple_at = apple_at if line_start == -1 else line_start + 1

    classipod_at = src.find(CLASSIPOD_MARKER, apple_at)
    if classipod_at == -1:
        sys.exit("Classipod branch not found after the Apple branch")
    line_start = src.rfind("\n", 0, classipod_at)
    classipod_at = classipod_at if line_start == -1 else line_start + 1

    return src[:apple_at], src[apple_at:classipod_at], src[classipod_at:]


def destinations(src: str) -> list[tuple[str, int]]:
    """
    Keys of the destination ladder.

    Lives in the hoisted `sharedDestinations` body rather than inside an
    AnimatedContent's `targetState`, because the ladder is shared by every
    shell — that hoist is what made the destinations reachable at all.
    """
    out: list[tuple[str, int]] = []
    m = re.search(r"val key = when \{", src)
    if not m:
        return out
    at = m.start()
    # Take the text of the `when` by brace matching, string- and comment-aware.
    body, base_line = _block(src, src.index("{", at + len("val key = when") - 1))
    for k in re.finditer(r'->\s*"([a-z0-9_]+)"', body):
        out.append((k.group(1), base_line + body[: k.start()].count("\n")))
    return out


def _block(src: str, start: int) -> tuple[str, int]:
    """Text of the brace-delimited block beginning at `start`, and its line."""
    depth = 0
    j = start
    in_str = in_lc = in_bc = False
    while j < len(src):
        c = src[j]
        n = src[j + 1] if j + 1 < len(src) else ""
        if in_lc:
            if c == "\n":
                in_lc = False
        elif in_bc:
            if c == "*" and n == "/":
                in_bc = False
                j += 1
        elif in_str:
            if c == "\\":
                j += 1
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "/" and n == "/":
                in_lc = True
            elif c == "/" and n == "*":
                in_bc = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return src[start : j + 1], src[:start].count("\n") + 1
        j += 1
    return src[start:], src[:start].count("\n") + 1


def rendered_keys(src: str) -> set[str]:
    """Destination keys the legacy shell actually has a body for."""
    keys = set()
    for pattern in (r'key == "([a-z0-9_]+)"', r'key\.startsWith\("([a-z0-9_]+)"'):
        keys.update(re.findall(pattern, src))
    return keys


def overlay_flags(src: str) -> list[tuple[str, int]]:
    """Every `show…` boolean, which is how a destination or sheet is reached."""
    return [
        (m.group(1), src[: m.start()].count("\n") + 1)
        # `var`, not `val`: these are destination flags, reassigned on every open.
        for m in re.finditer(r"\b(?:val|var) (show[A-Za-z0-9]+) by remember", src)
    ]


def sheet_calls(src: str) -> list[tuple[str, int]]:
    """Every ModalBottomSheet/Dialog mounted in the activity, by composable name."""
    out = []
    for m in re.finditer(r"\b([A-Z][A-Za-z0-9]*Sheet|[A-Z][A-Za-z0-9]*Dialog)\s*\(", src):
        out.append((m.group(1), src[: m.start()].count("\n") + 1))
    seen: set[str] = set()
    unique = []
    for name, line in out:
        if name not in seen:
            seen.add(name)
            unique.append((name, line))
    return unique


def topbar_actions(src: str) -> list[str]:
    """Actions wired into the legacy top bar, which the Apple shell must answer."""
    at = src.find("FrostedTopBar(")
    if at == -1:
        return []
    depth = 0
    i = at + len("FrostedTopBar") - 1
    while i < len(src):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return sorted(set(re.findall(r"\b(on[A-Z][A-Za-z0-9]*)\s*=", src[at:i])))


def action_callbacks(src: str) -> list[str]:
    """Every `on…Click` / `on…` lambda the legacy shell passes to shared components."""
    return sorted(set(re.findall(r"\b(on[A-Z][A-Za-z0-9]*)\s*=\s*(?:\{|::|viewModel|[A-Za-z_])", src)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    src = read_source()
    shared, apple, rest = split_shells(src)
    legacy = rest  # Classipod + legacy; Classipod is not the comparison target

    # Everything below the shell branch is the overlay layer — sheets, dialogs,
    # the player, notices — mounted once and reachable from whichever shell is
    # being worn. The file marks the boundary itself; without it the checker
    # would report every one of those as a gap, which is how a tool teaches
    # people to ignore it.
    tail_marker = "} // end else: everything above is one shell or the other"
    tail = legacy[legacy.find(tail_marker):] if tail_marker in legacy else ""
    report = Report()

    # ── 1. Destinations ────────────────────────────────────────────────────
    # The ladder is hoisted, so it lives above the shell branch: scan the file.
    dests = destinations(src)
    rendered = rendered_keys(legacy)
    # The ladder is hoisted, so its destinations are reachable from both shells
    # by construction — but only if the Apple shell actually *calls* it.
    mounted = "sharedDestinations(" in apple
    for key, line in dests:
        if key.startswith("tab"):
            report.shared.append(f"destination: {key} (tab bar, owned by the shell)")
            continue
        report.mark(
            "destination",
            key,
            "the shared destination layer is never called from the Apple shell",
            mounted,
            line,
        )

    # ── 2. Overlay flags ───────────────────────────────────────────────────
    # Declared above the branch point — they are shared state — so the scan is
    # over the whole file and only *reachability* is judged per shell.
    # Anything the hoisted destination layer sets is reachable from both.
    shared_start = src.find("val sharedDestinations")
    # Anchor the end of the shared layer *after* its start. Searching for the
    # terminator from position 0 makes the slice depend on whether some earlier
    # line happens to contain the same text: hoisting the shell-selection read
    # above the shells once truncated this to an empty slice and invented a gap.
    shared_end = src.find("val appUi by", shared_start if shared_start != -1 else 0)
    if shared_start == -1:
        shared_start, shared_end = 0, -1
    shared_body = src[shared_start:shared_end]
    for flag, line in overlay_flags(src):
        if flag in shared_body:
            report.shared.append(f"overlay: {flag} (raised inside the shared destination layer)")
            continue
        if flag in tail:
            report.shared.append(f"overlay: {flag} (raised in the overlay layer below the shells)")
            continue
        if flag in apple:
            report.mark("overlay", flag, "", True, line)
        else:
            report.mark(
                "overlay",
                flag,
                f"`{flag}` is never set or cleared in the Apple shell: "
                "its sheet cannot be opened, and its Back handler is unreachable",
                False,
                line,
            )

    # ── 3. Sheets and dialogs ──────────────────────────────────────────────
    for name, line in sheet_calls(legacy):
        # Sheets mounted *below* the shell branch are shared by construction.
        mounted_shared = name in shared or name in tail
        if mounted_shared:
            report.shared.append(f"sheet: {name} (mounted below the shell branch)")
        elif name in apple:
            report.mark("sheet", name, "", True, line)
        else:
            report.mark("sheet", name, "no mount in the Apple shell branch", False, line)

    # ── 4. Top-bar actions ─────────────────────────────────────────────────
    for action in topbar_actions(legacy):
        if action in shared or action in tail:
            report.shared.append(f"topbar-action: {action} (shared: defined outside the shell branches)")
        else:
            report.mark(
                "topbar-action",
                action,
                "the Apple shell has no top bar, so this action has no trigger",
                action in apple,
            )

    # ── 5. Player ──────────────────────────────────────────────────────────
    for symbol, why in (
        ("NowPlayingScreen", "full-screen player"),
        ("showNowPlaying", "the flag that raises the player"),
    ):
        raised = symbol in apple or symbol in tail or symbol in shared
        report.mark("player", symbol, f"{why} is not raised from the Apple shell", raised)

    # ── report ─────────────────────────────────────────────────────────────
    print(f"\nUI parity — Apple Music shell vs BitChord Legacy\n")
    print(f"  destinations checked : {len(dests)}")
    print(f"  overlays checked     : {len(overlay_flags(src))}")
    print(f"  sheets checked       : {len(sheet_calls(legacy))}")
    print(f"  top-bar actions      : {len(topbar_actions(legacy))}\n")

    by_kind: dict[str, list[Gap]] = {}
    for g in report.gaps:
        by_kind.setdefault(g.kind, []).append(g)

    if report.gaps:
        print(f"GAPS ({len(report.gaps)})\n")
        for kind in sorted(by_kind):
            print(f"  {kind} — {len(by_kind[kind])} missing")
            for g in by_kind[kind]:
                line = f"    :{g.legacy_line}" if g.legacy_line else ""
                print(f"      • {g.name}{line}")
                if g.detail:
                    print(f"        {g.detail}")
            print()
        print(f"  ported : {len(report.ported)}")
        print(f"  shared : {len(report.shared)} (below the shell branch — available to both)")
    else:
        print("no gaps: every destination, overlay, sheet, action and the player are reachable\n")

    # A section that checked nothing is not a passing section. This exists
    # because the checker once reported "no gaps" while counting zero
    # destinations, which is exactly the shape of a green build that means
    # nothing.
    vacuous = [
        name
        for name, count in (
            ("destinations", len(dests)),
            ("overlays", len(overlay_flags(src))),
            ("sheets", len(sheet_calls(legacy))),
            ("top-bar actions", len(topbar_actions(legacy))),
        )
        if count == 0
    ]
    if vacuous:
        print(f"VACUOUS: {', '.join(vacuous)} checked nothing — the pattern is stale, not the code clean\n")
        if args.strict:
            return 1

    if args.strict and report.gaps:
        print(f"FAIL: {len(report.gaps)} parity gap(s)\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
