#!/usr/bin/env python3
"""Refresh Octo's vendored loader sources from their default branches."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
UPSTREAMS = {
    "fabric-loader": "https://github.com/FabricMC/fabric-loader.git",
    "quilt-loader": "https://github.com/QuiltMC/quilt-loader.git",
    "neoforge": "https://github.com/neoforged/FancyModLoader.git",
    "forge": "https://github.com/MinecraftForge/MinecraftForge.git",
}
EXCLUDED = {".git", ".github", ".gradle", "build", "gradlew", "gradlew.bat", "gradle-wrapper.jar"}
FORGE_PATHS = {
    "fmlcore", "fmlloader", "forge-transformers", "javafmllanguage",
    "lowcodelanguage", "mclanguage", "LICENSE.txt", "LICENSE-header.txt",
    "gradle.properties",
}


def copy_tree(source: Path, target: Path) -> None:
    shutil.rmtree(target, ignore_errors=True)
    shutil.copytree(source, target, ignore=shutil.ignore_patterns(*EXCLUDED))


def copy_forge(source: Path, target: Path) -> None:
    """Keep only Forge's loader modules; never vendor Minecraft-derived trees."""
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    for name in FORGE_PATHS:
        item = source / name
        destination = target / name
        if item.is_dir():
            shutil.copytree(item, destination, ignore=shutil.ignore_patterns(*EXCLUDED))
        elif item.is_file():
            shutil.copy2(item, destination)


def main() -> None:
    commits = {}
    with tempfile.TemporaryDirectory(prefix="octo-upstreams-") as temporary:
        temp = Path(temporary)
        for name, remote in UPSTREAMS.items():
            checkout = temp / name
            subprocess.run(
                ["git", "clone", "--depth=1", "--filter=blob:none", remote, str(checkout)], check=True
            )
            commits[name] = subprocess.check_output(
                ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True
            ).strip()
            target = ROOT / "upstream" / name
            copy_forge(checkout, target) if name == "forge" else copy_tree(checkout, target)

    notices = ROOT / "THIRD-PARTY-NOTICES.md"
    text = notices.read_text()
    for name, commit in commits.items():
        pattern = rf"(`upstream/{re.escape(name)}`[^\n]*?`)[a-f0-9]{{40}}(`)"
        text, count = re.subn(pattern, rf"\g<1>{commit}\g<2>", text)
        if count != 1:
            raise RuntimeError(f"could not update the recorded commit for {name}")
    notices.write_text(text)


if __name__ == "__main__":
    main()
