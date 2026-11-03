#!/usr/bin/env python3
"""Check owned documentation's local links and code fences, without network access."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote


def documents(root):
    paths = {root / "README.md", root / "doris-patches/PATCHES.md", root / "doris-duckbridge/compose/README.md"}
    for module in ("trino-duckbridge", "doris-duckbridge"):
        paths.update((root / module).glob("README*.md"))
        paths.add(root / module / "RELEASE-NOTES.md")
    for folder in ("dev-docs", "trino-duckbridge/dev-docs", "doris-duckbridge/dev-docs"):
        paths.update((root / folder).rglob("*.md"))
    return sorted(path for path in paths if path.is_file())


def main():
    root = Path(__file__).resolve().parent.parent
    failures = []
    checked = 0
    paths = documents(root)
    for path in paths:
        fence = None
        for number, line in enumerate(path.read_text().splitlines(), 1):
            marker = re.match(r"^\s*(?:>\s*)*(`{3,}|~{3,})", line)
            if marker:
                current = marker.group(1)
                if fence is None:
                    fence = current
                elif current[0] == fence[0] and len(current) >= len(fence):
                    fence = None
                continue
            if fence is not None:
                continue
            text = re.sub(r"`[^`]*`", "", line)
            for link in re.findall(r'\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
                if link.startswith(("#", "/")) or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", link):
                    continue
                target = unquote(link.split("#", 1)[0])
                checked += 1
                if not (path.parent / target).exists():
                    failures.append(f"{path.relative_to(root)}:{number}: missing {link}")
        if fence is not None:
            failures.append(f"{path.relative_to(root)}: unclosed {fence} code fence")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Documentation OK: {len(paths)} Markdown files, {checked} local inline links, balanced code fences")
    return 0


if __name__ == "__main__":
    sys.exit(main())
