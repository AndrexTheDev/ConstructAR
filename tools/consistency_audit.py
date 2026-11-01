#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
The page and the app must not disagree.

A landing page is a promise; the app is what the customer gets. The two are written in different
languages (Python and Kotlin) from different files, so the cheapest possible defect is that one
of them is edited and the other is not: a price that moved, a wallet address that changed in one
place only, a seventh language the app ships and the page does not.

This audit reads *both* sides and compares the facts a buyer would notice:

  * the three payment addresses (verbatim, one character is enough to lose the money),
  * the four prices (free / month / year / lifetime),
  * the six languages, in the app's resource filter and on the page,
  * the version the page advertises against the app's `versionName`.

Nothing here is generated from the other side on purpose. A shared constant would make the
disagreement impossible to *write*, but it would also hide a wrong edit; two independent copies
plus a gate is how a typo in either one is caught before a customer sees it.

Usage:  python3 tools/consistency_audit.py [--verbose]
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERBOSE = "--verbose" in sys.argv

findings: list[str] = []


def add(message: str) -> None:
    findings.append(message)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# What the app says
# ---------------------------------------------------------------------------------------------
checkout = read("core/src/main/kotlin/com/constructar/core/licence/CryptoCheckout.kt")
app_addresses = dict(re.findall(r'symbol = "(\w+)",\s*\n\s*address = "([^"]+)"', checkout))
app_prices = {
    plan: int(cents.replace("_", ""))
    for plan, cents in re.findall(r"^\s*(MONTHLY|YEARLY|LIFETIME)\(Plan\.\w+, ([\d_]+)\)", checkout, re.M)
}
app_version = re.search(r'versionName = "([^"]+)"', read("app/build.gradle.kts")).group(1)
app_locales = re.search(r"localeFilters \+= listOf\(([^)]*)\)", read("app/build.gradle.kts")).group(1)

# ---------------------------------------------------------------------------------------------
# What the page says
# ---------------------------------------------------------------------------------------------
sys.path.insert(0, str(ROOT / "tools"))
import build_site  # noqa: E402

page_addresses = dict(build_site.COINS)
page = (ROOT / "docs" / "index.html").read_text(encoding="utf-8")
json_ld = json.loads(re.search(r'<script type="application/ld\+json">(.*?)</script>', page, re.S).group(1))
application = next(node for node in json_ld["@graph"] if node["@type"] == "SoftwareApplication")
page_prices = {offer["name"]: offer["price"] for offer in application["offers"]}
page_version = application.get("softwareVersion", "")
page_locales = set(application.get("inLanguage", []))

# ---------------------------------------------------------------------------------------------
# Compare
# ---------------------------------------------------------------------------------------------
for symbol, address in sorted(app_addresses.items()):
    if symbol not in page_addresses:
        add(f"the app takes {symbol} but the page does not list it")
    elif page_addresses[symbol] != address:
        add(f"{symbol} address differs: app {address!r} vs page {page_addresses[symbol]!r}")

for symbol, address in sorted(page_addresses.items()):
    if symbol not in app_addresses:
        add(f"the page advertises {symbol} but the app cannot take it")
    if address not in page:
        add(f"the {symbol} address is not actually rendered on the page")

# The page's offer names are localized, so they are matched to the plan by position: the four
# offers are free, month, year, lifetime, in that order, in every language.
expected_order = ["0", "10", "100", "999"]
actual_order = list(page_prices.values())
if actual_order != expected_order:
    add(f"the page's four offers are {actual_order}, expected {expected_order}")

app_cents = {plan: cents for plan, cents in app_prices.items()}
for plan, cents in sorted(app_cents.items()):
    euros = str(cents // 100)
    if euros not in actual_order:
        add(f"{plan} costs {euros} € in the app but no such price is on the page")

locales_in_filter = set(re.findall(r'"(\w\w)"', app_locales))
if locales_in_filter != page_locales:
    add(f"languages differ: app {sorted(locales_in_filter)} vs page {sorted(page_locales)}")

if page_version and not app_version.startswith(page_version):
    add(f"the page advertises version {page_version!r} but the app is {app_version!r}")

# ---------------------------------------------------------------------------------------------
# The claims the page makes about the app, checked against the code that backs them
# ---------------------------------------------------------------------------------------------
if "0 Bytes" not in page and "0 bytes" not in page:
    add("the page no longer states the '0 bytes leave the phone' claim")
permissions = read("app/src/main/AndroidManifest.xml")
if "android.permission.INTERNET" in permissions:
    add("the app declares INTERNET while the page promises it sends nothing")

# ---------------------------------------------------------------------------------------------
# The same palette on both sides. The page is styled with CSS custom properties, the app with
# `Color(0xFF…)`; they are two copies of one design, and a colour changed in one place only is
# how a product starts looking like two products.
# ---------------------------------------------------------------------------------------------
theme = read("app/src/main/kotlin/com/constructar/app/ui/theme/Theme.kt")
jamaica = dict(re.findall(r"val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", theme))
css_root = re.search(r":root\{(.*?)\}", build_site.CSS, re.S).group(1)
css_vars = {name: value.upper() for name, value in re.findall(r"--([\w-]+):\s*#([0-9A-Fa-f]{6})", css_root)}
palette = {
    "ink": "Ink",
    "ink-elevated": "InkElevated",
    "surface-high": "SurfaceHigh",
    "lime": "Lime",
    "gold": "Gold",
    "green": "Green",
    "chalk": "Chalk",
    "ash": "Ash",
    "wire": "Wire",
}
for css_name, kotlin_name in palette.items():
    if css_name not in css_vars:
        add(f"the site CSS no longer defines --{css_name}")
        continue
    if kotlin_name not in jamaica:
        add(f"the app theme no longer defines Jamaica.{kotlin_name}")
        continue
    if css_vars[css_name] != jamaica[kotlin_name].upper():
        add(f"--{css_name} is #{css_vars[css_name]} on the page but #{jamaica[kotlin_name].upper()} in the app")

for lang in ("de", "es", "fr", "it", "pl"):
    localized = (ROOT / "docs" / lang / "index.html").read_text(encoding="utf-8")
    if not all(address in localized for address in page_addresses.values()):
        add(f"docs/{lang}/index.html does not carry all three payment addresses")

if VERBOSE:
    print(f"  app: {len(app_addresses)} coins, prices {app_cents}, version {app_version}, locales {sorted(locales_in_filter)}")
    print(f"  page: {len(page_addresses)} coins, offers {page_prices}, version {page_version}, locales {sorted(page_locales)}")


def main() -> int:
    if findings:
        print(f"consistency_audit: {len(findings)} finding(s)\n")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print("consistency_audit: OK - prices, addresses, languages and version agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
