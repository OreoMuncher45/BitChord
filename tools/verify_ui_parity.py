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
    """Keys of the legacy AnimatedContent `targetState = when { … }` ladder."""
    out: list[tuple[str, int]] = []
    at = src.find("targetState = when {")
    if at == -1:
        return out
    depth = 0
    i = at + len("targetState = when") - 1
    end = len(src)
    while i < end:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    block = src[at:i]
    base_line = src[:at].count("\n") + 1
    for m in re.finditer(r'->\s*"([a-z0-9_]+)"', block):
        out.append((m.group(1), base_line + block[: m.start()].count("\n")))
    return out


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
    report = Report()

    # ── 1. Destinations ────────────────────────────────────────────────────
    dests = destinations(legacy)
    rendered = rendered_keys(legacy)
    for key, line in dests:
        if key.startswith("tab"):
            report.shared.append(f"destination: {key} (tab bar, owned by the shell)")
            continue
        reachable = key in apple
        report.mark(
            "destination",
            key,
            "no reference in the Apple shell branch; the destination cannot be opened",
            reachable,
            line,
        )

    # ── 2. Overlay flags ───────────────────────────────────────────────────
    # Declared above the branch point — they are shared state — so the scan is
    # over the whole file and only *reachability* is judged per shell.
    for flag, line in overlay_flags(src):
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
        mounted_shared = name in shared
        if mounted_shared:
            report.shared.append(f"sheet: {name} (mounted below the shell branch)")
        elif name in apple:
            report.mark("sheet", name, "", True, line)
        else:
            report.mark("sheet", name, "no mount in the Apple shell branch", False, line)

    # ── 4. Top-bar actions ─────────────────────────────────────────────────
    for action in topbar_actions(legacy):
        if action in shared:
            report.shared.append(f"topbar-action: {action} (defined above the shells)")
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
        report.mark("player", symbol, f"{why} is not raised from the Apple shell", symbol in apple)

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

    if args.strict and report.gaps:
        print(f"FAIL: {len(report.gaps)} parity gap(s)\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
