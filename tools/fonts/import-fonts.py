#!/usr/bin/env python3
"""Copy Wallpep's font library out of a local google/fonts checkout.

The companion FONTS.md inventory is the source of truth: it lists, per file,
the family, the library category, the upstream path inside github.com/google/fonts
and the license. This script never downloads anything; point it at a checkout:

    git clone --filter=blob:none --depth 1 https://github.com/google/fonts.git /tmp/google-fonts
    git -C /tmp/google-fonts sparse-checkout set <the 112 family directories>
    python3 tools/fonts/import-fonts.py FONTS.md /tmp/google-fonts app/src/main/assets/fonts

Upstream commit used for the committed library: 6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec.
"""
import re
import shutil
import sys
from pathlib import Path

# Google Fonts commits its fonts as variable files with bracketed axes names such as
# "Outfit[wght].ttf". Brackets are legal in an asset path but are avoided here so the
# files stay portable; the axis list is replaced by "-Variable".
def asset_name(name: str) -> str:
    stem, dot, extension = name.partition("[")
    if not dot:
        return name
    return f"{stem}-Variable{Path(name).suffix}"


def parse(markdown: Path):
    for line in markdown.read_text().splitlines():
        if not line.startswith("| ") or "https://github.com/google/fonts" not in line:
            continue
        family, _style, _path, license_name, url = [c.strip() for c in line.strip().strip("|").split("|")]
        category, family_dir, file_name = _path.split("/")
        upstream = url.split("/main/", 1)[1]
        yield family, category, family_dir, file_name, license_name, upstream


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    inventory, checkout, destination = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    copied = 0
    for family, category, family_dir, file_name, license_name, upstream in parse(inventory):
        source = checkout / upstream
        if not source.is_file():
            raise SystemExit(f"Missing upstream file: {upstream}")
        target_dir = destination / category / family_dir
        target_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target_dir / asset_name(file_name))
        copied += 1
        license_source = checkout / Path(upstream).parent / ("OFL.txt" if license_name == "OFL" else "LICENSE.txt")
        license_target = target_dir / ("OFL.txt" if license_name == "OFL" else "LICENSE-Apache-2.0.txt")
        if not license_target.exists():
            shutil.copyfile(license_source, license_target)
    print(f"Copied {copied} font files into {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
