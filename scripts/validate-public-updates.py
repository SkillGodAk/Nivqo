#!/usr/bin/env python3
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_JSON = (
    ROOT / "app-release.json",
    ROOT / "source" / "morphe-manager" / "app-release.json",
    ROOT / "updates" / "hushfacebook.json",
    ROOT / "updates" / "hushmessenger.json",
)


def load_public_json(path: Path):
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise ValueError("UTF-8 BOM is not allowed")
    text = data.decode("utf-8")
    value = json.loads(text)
    if not isinstance(value, dict):
        raise ValueError("top-level JSON value must be an object")
    for key in ("created_at", "description", "download_url", "version"):
        if key not in value:
            raise ValueError(f"missing required key: {key}")
    return value


def main() -> int:
    parsed = {}
    errors = []
    for path in PUBLIC_JSON:
        try:
            parsed[path] = load_public_json(path)
            print(f"OK  {path.relative_to(ROOT)}")
        except Exception as exc:
            errors.append(f"{path.relative_to(ROOT)}: {exc}")

    root_release = parsed.get(ROOT / "app-release.json")
    source_release = parsed.get(ROOT / "source" / "morphe-manager" / "app-release.json")
    if root_release is not None and source_release is not None and root_release != source_release:
        errors.append("app-release.json and source/morphe-manager/app-release.json differ")

    if errors:
        for error in errors:
            print(f"ERROR  {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
