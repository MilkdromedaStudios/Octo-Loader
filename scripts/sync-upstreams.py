#!/usr/bin/env python3
"""Refresh Octo's vendored loader sources into the single `upstream/octo` tree.

Octo is not Forge, Fabric, Quilt or NeoForge, and it stopped being four things
some time ago. What it needs from those projects is one thing: the mod-facing
source that mods are actually compiled against. So the four checkouts are merged
here, at sync time, into one source root laid out by package rather than by
project — `upstream/octo/src/main/java/net/fabricmc/...` next to
`upstream/octo/src/main/java/net/neoforged/...` — and the build reads from that
one place.

Where two projects ship the same file (Quilt vendors a copy of Fabric's API, for
instance) the first project in PRECEDENCE wins and the collision is recorded in
`upstream/octo/ORIGINS.md`, so a file's provenance is never a guess.
"""

from __future__ import annotations

from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MERGED = ROOT / "upstream" / "octo"

UPSTREAMS = {
    "fabric-loader": "https://github.com/FabricMC/fabric-loader.git",
    "quilt-loader": "https://github.com/QuiltMC/quilt-loader.git",
    "neoforge": "https://github.com/neoforged/FancyModLoader.git",
    "forge": "https://github.com/MinecraftForge/MinecraftForge.git",
}

# Earlier wins when the same path is produced by more than one project. Fabric
# is first because both Quilt and Octo present Fabric's API as the canonical
# one; Forge is last because its FML sources overlap NeoForge's and NeoForge is
# the maintained fork.
PRECEDENCE = ["fabric-loader", "quilt-loader", "neoforge", "forge"]

# The source roots that carry shipped code, in the order they are merged. Test
# fixtures, benchmarks and build plumbing are not vendored: nothing downstream
# reads them and they are the bulk of the four checkouts.
SOURCE_ROOTS = {
    "fabric-loader": [
        ("src/main/java", "java"),
        ("src/main/legacyJava", "java"),
        ("src/java17/java", "java"),
        ("src/main/resources", "resources"),
        ("minecraft/src/main/java", "java"),
        ("minecraft/src/main/legacyJava", "java"),
        ("minecraft/src/main/resources", "resources"),
    ],
    "quilt-loader": [
        ("src/main/java", "java"),
        ("src/fabric/api/java", "java"),
        ("src/fabric/impl/java", "java"),
        ("src/fabric/legacy/java", "java"),
        ("src/main/resources", "resources"),
        ("minecraft/src/main/java", "java"),
        ("minecraft/src/main/resources", "resources"),
    ],
    "neoforge": [
        ("loader/src/main/java", "java"),
        ("loader/src/main/resources", "resources"),
        ("earlydisplay/src/main/java", "java"),
        ("earlydisplay/src/main/resources", "resources"),
    ],
    "forge": [
        ("fmlcore/src/main/java", "java"),
        ("fmlloader/src/main/java", "java"),
        ("fmlloader/src/main/resources", "resources"),
        ("forge-transformers/src/main/java", "java"),
        ("forge-transformers/src/main/resources", "resources"),
        ("javafmllanguage/src/main/java", "java"),
        ("javafmllanguage/src/main/resources", "resources"),
        ("lowcodelanguage/src/main/java", "java"),
        ("lowcodelanguage/src/main/resources", "resources"),
        ("mclanguage/src/main/java", "java"),
        ("mclanguage/src/main/resources", "resources"),
    ],
}

# Licences travel with the code they cover; this is the one place the four
# projects stay separate, because their terms are not interchangeable.
LICENCES = {
    "fabric-loader": "LICENSE",
    "quilt-loader": "LICENSE",
    "neoforge": "LICENSE.txt",
    "forge": "LICENSE.txt",
}


def merge(checkouts: dict[str, Path], target: Path) -> tuple[dict[str, str], list[str]]:
    """Copies every source root into one tree. Returns (owner per path, collisions)."""
    shutil.rmtree(target, ignore_errors=True)
    owners: dict[str, str] = {}
    collisions: list[str] = []

    for name in PRECEDENCE:
        checkout = checkouts.get(name)
        if checkout is None:
            continue

        for relative, kind in SOURCE_ROOTS[name]:
            source = checkout / relative
            if not source.is_dir():
                continue

            for item in sorted(source.rglob("*")):
                if not item.is_file():
                    continue

                path = f"src/main/{kind}/{item.relative_to(source).as_posix()}"
                if path in owners:
                    if owners[path] != name:
                        collisions.append(f"{path} — kept {owners[path]}, dropped {name}")
                    continue

                owners[path] = name
                destination = target / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, destination)

    for name, licence in LICENCES.items():
        checkout = checkouts.get(name)
        if checkout is None or not (checkout / licence).is_file():
            continue
        destination = target / "LICENSES" / f"{name}.LICENSE"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(checkout / licence, destination)

    return owners, collisions


def write_origins(target: Path, owners: dict[str, str], collisions: list[str],
                  commits: dict[str, str]) -> None:
    """Records where each package came from, so the merge stays auditable."""
    packages: dict[str, dict[str, int]] = {}

    for path, owner in owners.items():
        parts = path.split("/")
        # src/main/<kind>/a/b/c/File.java -> a.b.c, truncated to three segments.
        package = ".".join(parts[3:6]) if len(parts) > 6 else ".".join(parts[3:-1])
        packages.setdefault(package or "(root)", {}).setdefault(owner, 0)
        packages[package or "(root)"][owner] += 1

    lines = [
        "# Where the merged sources came from",
        "",
        "`upstream/octo` is one tree built from four upstream checkouts by",
        "`scripts/sync-upstreams.py`. Do not edit it by hand — run the script.",
        "",
        "## Upstream revisions",
        "",
    ]

    for name in PRECEDENCE:
        commit = commits.get(name)
        lines.append(f"- **{name}** — `{commit}`" if commit else f"- **{name}**")

    lines += ["", "## Packages", "", "| Package | Files | From |", "| --- | --- | --- |"]

    for package in sorted(packages):
        sources = packages[package]
        total = sum(sources.values())
        origin = ", ".join(f"{name} ({count})" for name, count in sorted(sources.items()))
        lines.append(f"| `{package}` | {total} | {origin} |")

    lines += ["", "## Files supplied by more than one project", ""]
    lines += [f"- {collision}" for collision in collisions] or ["None."]
    lines.append("")

    (target / "ORIGINS.md").write_text("\n".join(lines), encoding="utf-8")


def update_notices(commits: dict[str, str]) -> None:
    notices = ROOT / "THIRD-PARTY-NOTICES.md"
    text = notices.read_text(encoding="utf-8")

    for name, commit in commits.items():
        pattern = rf"(\*\*{re.escape(name)}\*\*[^\n]*?`)[a-f0-9]{{40}}(`)"
        text, count = re.subn(pattern, rf"\g<1>{commit}\g<2>", text)
        if count != 1:
            raise RuntimeError(f"could not update the recorded commit for {name}")

    notices.write_text(text, encoding="utf-8")


def main() -> None:
    commits: dict[str, str] = {}

    with tempfile.TemporaryDirectory(prefix="octo-upstreams-") as temporary:
        temp = Path(temporary)
        checkouts: dict[str, Path] = {}

        for name, remote in UPSTREAMS.items():
            checkout = temp / name
            subprocess.run(
                ["git", "clone", "--depth=1", "--filter=blob:none", remote, str(checkout)], check=True
            )
            commits[name] = subprocess.check_output(
                ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True
            ).strip()
            checkouts[name] = checkout

        owners, collisions = merge(checkouts, MERGED)

    write_origins(MERGED, owners, collisions, commits)
    update_notices(commits)
    print(f"merged {len(owners)} file(s) into {MERGED.relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
