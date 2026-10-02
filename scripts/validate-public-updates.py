#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
COMBINED_BUNDLE = ROOT / "updates" / "bundles" / "nivqo-patches.mpp"
COMBINED_DOWNLOAD_URL_RE = re.compile(
    r"^https://raw\.githubusercontent\.com/SkillGodAk/Nivqo/[0-9a-f]{40}/updates/bundles/nivqo-patches\.mpp$"
)
PUBLIC_JSON = (
    ROOT / "patches-bundle.json",
    ROOT / "app-release.json",
    ROOT / "source" / "morphe-manager" / "app-release.json",
    ROOT / "updates" / "hushfacebook.json",
    ROOT / "updates" / "hushmessenger.json",
)
PUBLIC_CHANGELOGS = (
    (ROOT / "updates" / "hushfacebook-changelog.md", "Facebook"),
    (ROOT / "updates" / "hushmessenger-changelog.md", "Messenger"),
)
SCOPE_RE = re.compile(r"^\*\s+\*\*(.+?):\*\*", re.MULTILINE)
HEADING_RE = re.compile(r"^#\s+", re.MULTILINE)
MORPHE_CREATED_AT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$")
MORPHE_VERSION_HEADING_RE = re.compile(
    r"^#{1,3}\s+(?:\S+\s+)?(?:\[([^]]+)]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)",
    re.MULTILINE | re.IGNORECASE,
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
    if path.name == "patches-bundle.json":
        created_at = value["created_at"]
        if not isinstance(created_at, str) or not MORPHE_CREATED_AT_RE.fullmatch(created_at):
            raise ValueError(
                "created_at must be Morphe LocalDateTime format YYYY-MM-DDTHH:MM:SS without Z or timezone offset"
            )
        if not str(value["download_url"]).lower().endswith(".mpp"):
            raise ValueError("download_url must point to an .mpp bundle")
        if not COMBINED_DOWNLOAD_URL_RE.fullmatch(str(value["download_url"])):
            raise ValueError(
                "download_url must point to a commit-pinned formal Nivqo combined bundle"
            )
    return value


def validate_combined_bundle(path: Path, expected_version: str):
    if not path.is_file():
        raise ValueError("formal combined bundle is missing")
    with zipfile.ZipFile(path) as bundle:
        names = set(bundle.namelist())
        required = {
            "classes.dex",
            "extensions/facebook.mpe",
            "extensions/messenger.mpe",
            "extensions/shared.mpe",
            "META-INF/MANIFEST.MF",
        }
        missing = required - names
        if missing:
            raise ValueError(f"combined bundle is missing payload(s): {sorted(missing)}")
        manifest = bundle.read("META-INF/MANIFEST.MF").decode("utf-8")
    fields = {}
    for line in manifest.splitlines():
        if ": " in line:
            key, value = line.split(": ", 1)
            fields[key] = value.strip()
    if fields.get("Name") != "Nivqo Patches":
        raise ValueError(f"unexpected bundle name: {fields.get('Name')!r}")
    if fields.get("Version") != expected_version:
        raise ValueError(
            f"bundle version {fields.get('Version')!r} does not match patches-bundle.json {expected_version!r}"
        )


def validate_combined_changelog(path: Path, expected_version: str):
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise ValueError("UTF-8 BOM is not allowed")
    text = data.decode("utf-8")
    matches = list(MORPHE_VERSION_HEADING_RE.finditer(text))
    if not matches:
        raise ValueError("missing Morphe-compatible version heading")
    first = matches[0]
    version = (first.group(1) or first.group(2)).strip()
    if version != expected_version:
        raise ValueError(f"latest version {version!r} does not match patches-bundle.json {expected_version!r}")
    end = matches[1].start() if len(matches) > 1 else len(text)
    scopes = {m.group(1).strip() for m in SCOPE_RE.finditer(text[first.end():end])}
    missing = {"Facebook", "Messenger"} - scopes
    if missing:
        raise ValueError(f"latest combined entry is missing app scope(s): {sorted(missing)}")


def validate_latest_changelog_scope(path: Path, expected_scope: str):
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        raise ValueError("UTF-8 BOM is not allowed")
    text = data.decode("utf-8")
    headings = list(HEADING_RE.finditer(text))
    if not headings:
        raise ValueError("missing release heading")
    start = headings[0].end()
    end = headings[1].start() if len(headings) > 1 else len(text)
    scopes = {match.group(1).strip() for match in SCOPE_RE.finditer(text[start:end])}
    if expected_scope not in scopes:
        raise ValueError(
            f"latest release must contain a scoped bullet for {expected_scope}; found {sorted(scopes)}"
        )


def main() -> int:
    parsed = {}
    errors = []
    for path in PUBLIC_JSON:
        try:
            parsed[path] = load_public_json(path)
            print(f"OK  {path.relative_to(ROOT)}")
        except Exception as exc:
            errors.append(f"{path.relative_to(ROOT)}: {exc}")

    for path, expected_scope in PUBLIC_CHANGELOGS:
        try:
            validate_latest_changelog_scope(path, expected_scope)
            print(f"OK  {path.relative_to(ROOT)} [{expected_scope}]")
        except Exception as exc:
            errors.append(f"{path.relative_to(ROOT)}: {exc}")

    combined_release = parsed.get(ROOT / "patches-bundle.json")
    if combined_release is not None:
        try:
            validate_combined_changelog(ROOT / "CHANGELOG.md", str(combined_release["version"]))
            print(f"OK  CHANGELOG.md [combined {combined_release['version']}]")
        except Exception as exc:
            errors.append(f"CHANGELOG.md: {exc}")
        try:
            validate_combined_bundle(COMBINED_BUNDLE, str(combined_release["version"]))
            print(f"OK  {COMBINED_BUNDLE.relative_to(ROOT)} [combined {combined_release['version']}]")
        except Exception as exc:
            errors.append(f"{COMBINED_BUNDLE.relative_to(ROOT)}: {exc}")

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
