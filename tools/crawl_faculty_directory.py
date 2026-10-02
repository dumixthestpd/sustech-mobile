#!/usr/bin/env python3
"""Refresh the bundled, bilingual faculty index from SUSTech's public directory.

The app searches the generated JSON locally. This script makes one request for
the full Chinese directory and one polite request per English alphabet page;
it does not fetch individual profiles, contact details, or images.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import string
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


BASE = "https://www.sustech.edu.cn"
CHINESE_INDEX = f"{BASE}/zh/letter/"
ENGLISH_LETTER = f"{BASE}/en/letter/{{letter}}.html"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/faculty_directory.json"
FIELDS = {"name", "p", "p1", "p2", "dep"}


class DirectoryParser(HTMLParser):
    """Read only public name/title/department fields from directory cards."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.in_item = False
        self.record: dict[str, str] | None = None
        self.field: str | None = None
        self.records: dict[str, dict[str, str]] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_map = dict(attrs)
        if tag == "li":
            self.in_item = True
            self.record = None
        elif tag == "a" and self.in_item:
            href = attrs_map.get("href", "") or ""
            if href.startswith("/zh/faculties/"):
                slug = href.removeprefix("/zh/faculties/").split("?", 1)[0]
                if slug:
                    self.record = {"slug": slug}
        elif tag == "div" and self.record is not None:
            classes = set((attrs_map.get("class") or "").split())
            self.field = next((name for name in FIELDS if name in classes), None)
            if self.field:
                self.record.setdefault(self.field, "")

    def handle_data(self, data: str) -> None:
        if self.record is not None and self.field:
            self.record[self.field] += data

    def handle_endtag(self, tag: str) -> None:
        if tag == "div":
            self.field = None
        elif tag == "li":
            if self.record is not None:
                slug = self.record.pop("slug")
                self.records[slug] = {
                    key: " ".join(self.record.get(key, "").split())
                    for key in FIELDS
                }
            self.record = None
            self.field = None
            self.in_item = False


def fetch(url: str) -> str:
    request = Request(
        url,
        headers={"User-Agent": "SUSTechMobileFacultyIndex/1.0 (public directory cache)"},
    )
    with urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise RuntimeError(f"Unexpected HTTP {response.status} for {url}")
        charset = response.headers.get_content_charset() or "utf-8"
        return response.read().decode(charset, errors="replace")


def parse_page(url: str, allow_empty: bool = False) -> dict[str, dict[str, str]]:
    parser = DirectoryParser()
    parser.feed(fetch(url))
    if not parser.records and not allow_empty:
        raise RuntimeError(f"No faculty records found at {url}; refusing to replace the asset")
    return parser.records


def main() -> None:
    arg_parser = argparse.ArgumentParser(description=__doc__)
    arg_parser.add_argument("--output", type=Path, default=OUTPUT)
    arg_parser.add_argument(
        "--delay", type=float, default=0.2,
        help="delay between English letter pages in seconds (default: 0.2)",
    )
    args = arg_parser.parse_args()

    zh = parse_page(CHINESE_INDEX)
    en: dict[str, dict[str, str]] = {}
    for position, letter in enumerate(string.ascii_lowercase):
        if position:
            time.sleep(max(args.delay, 0))
        # Some alphabet pages are valid but contain no matching staff entries.
        en.update(parse_page(ENGLISH_LETTER.format(letter=letter), allow_empty=True))

    entries = []
    for slug, zh_fields in zh.items():
        en_fields = en.get(slug, {})
        entries.append({
            "id": slug,
            "nameZh": zh_fields.get("name", ""),
            "titleZh": " ".join(filter(None, (zh_fields.get("p", ""), zh_fields.get("p1", ""), zh_fields.get("p2", "")))),
            "departmentZh": zh_fields.get("dep", ""),
            "nameEn": en_fields.get("name", ""),
            "titleEn": " ".join(filter(None, (en_fields.get("p", ""), en_fields.get("p1", ""), en_fields.get("p2", "")))),
            "departmentEn": en_fields.get("dep", ""),
            "url": f"{BASE}/zh/faculties/{slug}",
        })

    if len(entries) < 100 or len(en) < 100:
        raise RuntimeError(f"Directory looked incomplete (zh={len(entries)}, en={len(en)}); refusing to replace the asset")

    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).date().isoformat(),
        "generatedFrom": [CHINESE_INDEX, f"{BASE}/en/letter/<a-z>.html"],
        "recordCount": len(entries),
        "records": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(f"Wrote {len(entries)} faculty records ({len(en)} English entries) to {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (URLError, TimeoutError, RuntimeError) as error:
        raise SystemExit(str(error))
