#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
SEO and structured-data gate for the published landing pages.

`tools/build_site.py` generates six localized pages plus a 404, a sitemap, a robots file and a
manifest. This script reads the *generated* output — not the generator — and refuses to let a
regression ship. It answers the questions a crawler asks, in the order it asks them:

  1. Is the document well-formed and correctly declared?  (charset, viewport, lang, title,
     description, canonical, robots)
  2. Can a crawler find the other five languages?         (hreflang set, reciprocity, x-default)
  3. Does a shared link look right?                       (Open Graph + Twitter card, absolute
     image with real dimensions and alt text)
  4. Does a rich result have something true to say?       (JSON-LD @graph: SoftwareApplication
     with the four real prices, FAQPage with eight answered questions, WebSite)
  5. Does the page work when someone lands on it?         (every internal link and fragment
     resolves, every image has alt text and intrinsic size, no duplicate ids)
  6. Are the crawler files consistent with the pages?     (sitemap URLs + lastmod, robots Sitemap
     line, manifest icons that exist at the size they claim)
  7. Can a reader who needs large type read it?           (no `font-size` in px in the generated
     CSS: a px size overrides the browser's own font-size preference, so the paragraphs stay small
     while the rem-sized headings grow - finding 68)

The check is deliberately strict about *honesty* rather than volume: it verifies that the prices
in the structured data are the four prices the product actually charges, and that a claimed icon
size is the size of the file on disk. Invented metadata is worse than missing metadata.

Usage:  python3 tools/seo_audit.py [--verbose]
Exit code 0 means "nothing to fix"; 1 means the list printed is the list to fix.
"""

from __future__ import annotations

import json
import re
import struct
import sys
import xml.etree.ElementTree as ElementTree
from datetime import date
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
VERBOSE = "--verbose" in sys.argv

BASE_URL = "https://andrexthedev.github.io/ConstructAR/"
LANGS = ["en", "de", "es", "fr", "it", "pl"]
OG_LOCALES = {
    "en": "en_GB",
    "de": "de_DE",
    "es": "es_ES",
    "fr": "fr_FR",
    "it": "it_IT",
    "pl": "pl_PL",
}
# What the product actually charges. A schema.org Offer that disagrees with the price list is a
# structured-data violation, not a marketing choice.
PRICES = {"0", "10", "100", "999"}
FAQ_COUNT = 8

findings: list[str] = []


def add(path: Path | str, message: str) -> None:
    try:
        rel = Path(path).relative_to(ROOT)
    except ValueError:
        rel = Path(path)
    findings.append(f"{rel}: {message}")


def page_path(lang: str) -> Path:
    return DOCS / "index.html" if lang == "en" else DOCS / lang / "index.html"


def page_url(lang: str) -> str:
    return BASE_URL if lang == "en" else f"{BASE_URL}{lang}/"


class Document(HTMLParser):
    """The parts of a page this audit needs, collected in one pass."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.html_lang: str | None = None
        self.title = ""
        self.meta: list[dict[str, str]] = []
        self.links: list[dict[str, str]] = []
        self.anchors: list[dict[str, str]] = []
        self.images: list[dict[str, str]] = []
        self.ids: list[str] = []
        self.headings: dict[str, list[str]] = {}
        self.json_ld: list[str] = []
        self._in: str | None = None
        self._buffer: list[str] = []
        self._script_type: str | None = None

    # -- tags -------------------------------------------------------------
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key: (value or "") for key, value in attrs}
        if "id" in values:
            self.ids.append(values["id"])
        if tag == "html":
            self.html_lang = values.get("lang")
        elif tag == "meta":
            self.meta.append(values)
        elif tag == "link":
            self.links.append(values)
        elif tag == "a":
            self.anchors.append(values)
        elif tag == "img":
            self.images.append(values)
        elif tag == "script":
            self._script_type = values.get("type")
            self._in = "script"
            self._buffer = []
        elif tag in {"title", "h1", "h2", "h3"}:
            self._in = tag
            self._buffer = []

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        text = re.sub(r"\s+", " ", "".join(self._buffer)).strip()
        if tag == "script":
            if self._script_type == "application/ld+json" and text:
                self.json_ld.append(text)
            self._script_type = None
        elif tag == "title":
            self.title = text
        elif tag in {"h1", "h2", "h3"}:
            self.headings.setdefault(tag, []).append(text)
        self._in = None
        self._buffer = []

    def handle_data(self, data: str) -> None:
        if self._in is not None:
            self._buffer.append(data)


def parse(path: Path) -> Document:
    document = Document()
    document.feed(path.read_text(encoding="utf-8"))
    document.close()
    return document


def meta_value(document: Document, key: str, attribute: str = "name") -> str | None:
    for tag in document.meta:
        if tag.get(attribute) == key:
            return tag.get("content", "")
    return None


def meta_values(document: Document, key: str, attribute: str = "property") -> list[str]:
    return [tag.get("content", "") for tag in document.meta if tag.get(attribute) == key]


def link_href(document: Document, rel: str) -> list[str]:
    return [tag.get("href", "") for tag in document.links if tag.get("rel") == rel]


# --------------------------------------------------------------------- images


def image_size(path: Path) -> tuple[int, int] | None:
    data = path.read_bytes()
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    if data[:2] == b"\xff\xd8":
        index = 2
        while index < len(data) - 9:
            if data[index] != 0xFF:
                index += 1
                continue
            marker = data[index + 1]
            length = struct.unpack(">H", data[index + 2 : index + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2):
                height, width = struct.unpack(">HH", data[index + 5 : index + 9])
                return width, height
            index += 2 + length
    return None


# --------------------------------------------------------------------- checks


def check_document(lang: str) -> Document:
    path = page_path(lang)
    label = page_url(lang)
    document = parse(path)

    if document.html_lang != lang:
        add(path, f'<html lang="{document.html_lang}"> should be "{lang}"')

    # title / description
    if not 20 <= len(document.title) <= 70:
        add(path, f"<title> is {len(document.title)} characters (want 20-70): {document.title!r}")
    description = meta_value(document, "description") or ""
    if not 60 <= len(description) <= 200:
        add(path, f"meta description is {len(description)} characters (want 60-200)")
    if meta_value(document, "viewport") is None:
        add(path, "missing viewport meta")
    robots = meta_value(document, "robots") or ""
    if "index" not in robots or "follow" not in robots:
        add(path, f"robots meta should allow index,follow (got {robots!r})")
    if meta_value(document, "theme-color") is None:
        add(path, "missing theme-color (mobile browser chrome)")
    if meta_value(document, "color-scheme") is None:
        add(path, "missing color-scheme (prevents a white flash before the dark theme applies)")

    # canonical
    canonical = link_href(document, "canonical")
    if canonical != [label]:
        add(path, f"canonical should be exactly {label!r} (got {canonical})")

    # hreflang
    alternates = {
        tag.get("hreflang"): tag.get("href")
        for tag in document.links
        if tag.get("rel") == "alternate" and tag.get("hreflang")
    }
    expected = {code: page_url(code) for code in LANGS}
    expected["x-default"] = BASE_URL
    if alternates != expected:
        missing = {code: url for code, url in expected.items() if alternates.get(code) != url}
        add(path, f"hreflang links wrong or incomplete: {missing}")

    # Open Graph / Twitter
    for prop in ("og:type", "og:site_name", "og:title", "og:description", "og:url", "og:image", "og:locale"):
        if not meta_values(document, prop):
            add(path, f"missing {prop}")
    if (meta_values(document, "og:url") or [""])[0] != label:
        add(path, f"og:url should equal the canonical URL ({meta_values(document, 'og:url')})")
    if (meta_values(document, "og:locale") or [""])[0] != OG_LOCALES[lang]:
        add(path, f"og:locale should be {OG_LOCALES[lang]} for {lang}")
    og_image = (meta_values(document, "og:image") or [""])[0]
    if not og_image.startswith(BASE_URL):
        add(path, f"og:image must be absolute (got {og_image!r})")
    else:
        image = DOCS / og_image[len(BASE_URL) :]
        size = image_size(image) if image.exists() else None
        if size is None:
            add(path, f"og:image does not exist on disk: {image}")
        else:
            width = (meta_values(document, "og:image:width") or [""])[0]
            height = (meta_values(document, "og:image:height") or [""])[0]
            if (str(size[0]), str(size[1])) != (width, height):
                add(path, f"og:image is {size[0]}x{size[1]} but declares {width}x{height}")
    if not (meta_values(document, "og:image:alt") or [""])[0]:
        add(path, "missing og:image:alt")
    if meta_values(document, "og:locale:alternate") and len(meta_values(document, "og:locale:alternate")) != len(LANGS) - 1:
        add(path, "og:locale:alternate should list the other five languages")
    if (meta_value(document, "twitter:card") or "") != "summary_large_image":
        add(path, "twitter:card should be summary_large_image")
    for key in ("twitter:title", "twitter:description", "twitter:image", "twitter:image:alt"):
        if not (meta_value(document, key) or "").strip():
            add(path, f"missing {key}")
    twitter_image = meta_value(document, "twitter:image") or ""
    if twitter_image and not twitter_image.startswith(BASE_URL):
        add(path, "twitter:image must be absolute")

    # headings
    h1 = document.headings.get("h1", [])
    if len(h1) != 1:
        add(path, f"expected exactly one <h1>, found {len(h1)}")
    elif len(h1[0]) < 10:
        add(path, f"<h1> is too short to be a headline: {h1[0]!r}")
    if not document.headings.get("h2"):
        add(path, "no <h2> sections: the outline is flat")

    # assets that must resolve
    for rel, name in (("icon", "favicon"), ("apple-touch-icon", "apple-touch-icon"), ("manifest", "manifest")):
        hrefs = link_href(document, rel)
        if not hrefs:
            add(path, f"missing <link rel={rel}> ({name})")
        for href in hrefs:
            target = docs_target(lang, href)
            if target is not None and not target.exists():
                add(path, f"<link rel={rel}> points at a missing file: {href}")

    # images
    for image in document.images:
        # `alt=""` is correct for a decorative image next to a text label; a *missing* alt
        # attribute is what makes a screen reader read the file name aloud.
        if "alt" not in image:
            add(path, f"image without an alt attribute: {image.get('src')!r}")
        if not (image.get("width") and image.get("height")):
            add(path, f"image without width/height (layout shift): {image.get('src')!r}")

    # ids
    duplicates = {value for value in document.ids if document.ids.count(value) > 1}
    if duplicates:
        add(path, f"duplicate id attributes: {sorted(duplicates)}")

    # internal links and fragments
    for anchor in document.anchors:
        href = anchor.get("href", "")
        if href.startswith(("mailto:", "tel:")):
            continue
        check_internal_target(lang, path, document, href)

    # structured data
    check_json_ld(path, document, lang)
    return document


def docs_target(lang: str, href: str) -> Path | None:
    """Where a link on the page in [lang] lands in `docs/`, or None when it leaves the site.

    Both spellings are resolved the same way — the generator emits relative links inside the
    site, the 404 page has to use absolute ones, and a checker that only understood one of them
    would wave a broken link through.
    """
    absolute = urljoin(page_url(lang), href)
    if not absolute.startswith(BASE_URL):
        return None
    rest = absolute[len(BASE_URL) :].split("#")[0].split("?")[0]
    target = DOCS / rest
    if rest == "" or rest.endswith("/"):
        target = target / "index.html"
    return target


def check_internal_target(lang: str, path: Path, document: Document, href: str) -> None:
    if href.startswith("#"):
        if href[1:] not in document.ids:
            add(path, f"anchor {href!r} has no matching id")
        return
    resolved = docs_target(lang, href)
    if resolved is None:
        return
    if not resolved.exists():
        add(path, f"internal link to a missing file: {href!r} -> {resolved.relative_to(ROOT)}")
        return
    fragment = href.partition("#")[2]
    if fragment and resolved.suffix == ".html":
        if fragment not in parse(resolved).ids:
            add(path, f"link {href!r} points at a fragment that does not exist")


def check_json_ld(path: Path, document: Document, lang: str) -> None:
    if len(document.json_ld) != 1:
        add(path, f"expected exactly one application/ld+json block, found {len(document.json_ld)}")
        return
    try:
        data = json.loads(document.json_ld[0])
    except json.JSONDecodeError as error:
        add(path, f"JSON-LD does not parse: {error}")
        return
    graph = data.get("@graph") or []
    if data.get("@context") != "https://schema.org":
        add(path, "JSON-LD @context should be https://schema.org")
    types = {node.get("@type") for node in graph}
    for required in ("SoftwareApplication", "FAQPage", "WebSite"):
        if required not in types:
            add(path, f"JSON-LD @graph is missing a {required} node")

    for node in graph:
        kind = node.get("@type")
        if kind == "SoftwareApplication":
            if node.get("inLanguage") != LANGS:
                add(path, "SoftwareApplication.inLanguage should list the six shipped languages")
            prices = {offer.get("price") for offer in node.get("offers", [])}
            if prices != PRICES:
                add(path, f"SoftwareApplication offers are {sorted(prices)}, want {sorted(PRICES)}")
            for offer in node.get("offers", []):
                if not offer.get("availability"):
                    add(path, f"offer {offer.get('name')!r} has no availability")
            if not node.get("featureList"):
                add(path, "SoftwareApplication has no featureList")
            if not node.get("sameAs"):
                add(path, "SoftwareApplication has no sameAs (the repository)")
        elif kind == "FAQPage":
            questions = node.get("mainEntity") or []
            if len(questions) != FAQ_COUNT:
                add(path, f"FAQPage has {len(questions)} questions, want {FAQ_COUNT}")
            for question in questions:
                if not question.get("name") or not question.get("acceptedAnswer", {}).get("text"):
                    add(path, "FAQPage entry without a question or an answer")
            if node.get("inLanguage") != lang:
                add(path, f"FAQPage.inLanguage should be {lang!r}")
        elif kind == "WebSite":
            if node.get("url") != BASE_URL or node.get("inLanguage") != lang:
                add(path, "WebSite node should name the site URL and this language")


def check_sitemap() -> None:
    path = DOCS / "sitemap.xml"
    if not path.exists():
        add(path, "missing")
        return
    tree = ElementTree.parse(path)
    namespace = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9", "xhtml": "http://www.w3.org/1999/xhtml"}
    urls = tree.findall("s:url", namespace)
    if len(urls) != len(LANGS):
        add(path, f"{len(urls)} <url> entries, want {len(LANGS)}")
    locations = [url.findtext("s:loc", namespaces=namespace) for url in urls]
    if sorted(locations) != sorted(page_url(code) for code in LANGS):
        add(path, f"sitemap URLs do not match the six pages: {locations}")
    for url in urls:
        lastmod = url.findtext("s:lastmod", namespaces=namespace)
        if not lastmod:
            add(path, "entry without <lastmod>")
            continue
        try:
            date.fromisoformat(lastmod)
        except ValueError:
            add(path, f"<lastmod> is not an ISO date: {lastmod!r}")
        alternates = {link.get("hreflang") for link in url.findall("xhtml:link", namespace)}
        if alternates != set(LANGS):
            add(path, f"entry {url.findtext('s:loc', namespaces=namespace)} lacks the full hreflang set")


def check_robots() -> None:
    path = DOCS / "robots.txt"
    if not path.exists():
        add(path, "missing")
        return
    text = path.read_text(encoding="utf-8")
    if "User-agent: *" not in text:
        add(path, "no User-agent section")
    if "Disallow: /" in text:
        add(path, "robots.txt disallows the whole site")
    sitemap_line = [line for line in text.splitlines() if line.lower().startswith("sitemap:")]
    if not sitemap_line:
        add(path, "no Sitemap: line (crawlers cannot discover the sitemap)")
    elif sitemap_line[0].split(":", 1)[1].strip() != f"{BASE_URL}sitemap.xml":
        add(path, f"Sitemap line should point at {BASE_URL}sitemap.xml")


def check_manifest() -> None:
    path = DOCS / "manifest.webmanifest"
    if not path.exists():
        add(path, "missing")
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        add(path, f"does not parse: {error}")
        return
    for key in ("name", "short_name", "start_url", "display", "theme_color", "background_color", "icons"):
        if key not in data:
            add(path, f"missing {key}")
    if not data.get("icons"):
        return
    for icon in data["icons"]:
        src = icon.get("src", "")
        if not src.startswith(BASE_URL):
            add(path, f"icon src must be absolute: {src!r}")
            continue
        image = DOCS / src[len(BASE_URL) :]
        size = image_size(image) if image.exists() else None
        if size is None:
            add(path, f"icon missing or unreadable: {image}")
            continue
        declared = icon.get("sizes", "")
        actual = f"{size[0]}x{size[1]}"
        if declared != actual:
            add(path, f"icon {src} declares {declared} but is {actual}")


def check_not_found() -> None:
    path = DOCS / "404.html"
    if not path.exists():
        add(path, "missing")
        return
    document = parse(path)
    robots = meta_value(document, "robots") or ""
    if "noindex" not in robots:
        add(path, "404 page must be noindex")
    if len(document.headings.get("h1", [])) != 1:
        add(path, "404 page needs exactly one <h1>")
    for image in document.images:
        if "alt" not in image:
            add(path, f"image without an alt attribute: {image.get('src')!r}")
    # Served at whatever path the visitor asked for, so every URL in it must be absolute.
    for anchor in document.anchors:
        href = anchor.get("href", "")
        if href and not href.startswith(("http://", "https://", "mailto:", "#")):
            add(path, f"relative link would break on a deep 404 path: {href!r}")
    for link in document.links:
        href = link.get("href", "")
        if href and not href.startswith(("http://", "https://")) and link.get("rel") != "canonical":
            add(path, f"relative asset on the 404 page: {href!r}")
    for image in document.images:
        src = image.get("src", "")
        if src and not src.startswith("http"):
            add(path, f"relative image on the 404 page: {src!r}")


def check_uniqueness(documents: dict[str, Document]) -> None:
    for field, extract in (("title", lambda d: d.title), ("description", lambda d: meta_value(d, "description") or "")):
        seen: dict[str, str] = {}
        for lang, document in documents.items():
            value = extract(document)
            if value in seen:
                add(page_path(lang), f"{field} is identical to the {seen[value]} page (a duplicate page)")
            seen[value] = lang


PX_FONT_SIZE = re.compile(r"font-size\s*:\s*[0-9.]+px")


def check_font_size_units() -> None:
    """A `font-size` in px overrides the reader's browser preference; rem does not.

    The body rule was `font-size:17px` while every other size in the stylesheet was in rem - so a
    reader who raises the browser's default font size got bigger headings over unchanged body text,
    which is the combination that makes a page unusable rather than merely large (finding 68).
    """
    for lang in LANGS:
        path = page_path(lang)
        text = path.read_text(encoding="utf-8")
        for match in PX_FONT_SIZE.finditer(text):
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: `{match.group(0)}` is a px font size - use rem so the reader's "
                      f"font-size preference is honoured")


def main() -> int:
    missing = [str(page_path(code)) for code in LANGS if not page_path(code).exists()]
    if missing:
        for path in missing:
            add(path, "page not generated - run tools/build_site.py")
        print("\n".join(findings))
        return 1

    documents = {lang: check_document(lang) for lang in LANGS}
    check_uniqueness(documents)
    check_sitemap()
    check_robots()
    check_manifest()
    check_not_found()
    check_font_size_units()

    if findings:
        print(f"seo_audit: {len(findings)} finding(s)\n")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    if VERBOSE:
        for lang, document in documents.items():
            print(
                f"  {lang}: title {len(document.title)}c · h1 {len(document.headings.get('h1', []))} · "
                f"h2 {len(document.headings.get('h2', []))} · links {len(document.anchors)} · "
                f"images {len(document.images)} · json-ld {len(document.json_ld)}"
            )
    print(f"seo_audit: OK - {len(LANGS)} pages, sitemap, robots.txt, manifest.webmanifest, 404")
    return 0


if __name__ == "__main__":
    sys.exit(main())
