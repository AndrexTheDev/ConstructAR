#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
Mint and inspect ConstructAR licences.

ConstructAR has no INTERNET permission, so it cannot ask a server whether a payment arrived.
Instead a licence is a short signed statement the phone can verify by itself:

    CAR1.<base64url(payload JSON)>.<base64url(ECDSA P-256 SHA-256 signature)>

The signature covers the ASCII bytes of ``CAR1.<payload>``, so the signed text and the
transmitted text are byte-identical. The APK ships only the public key
(``app/src/main/kotlin/com/constructar/app/billing/LicencePublicKey.kt``); this script holds the
private key and therefore stays on your machine (``tools/private/`` is git-ignored).

Workflow when a crypto payment confirms
---------------------------------------
1. The operator sends you: the plan, the device tag shown in the app, and the transaction id.
2. Check the transaction on a block explorer (Solana / Bitcoin / Ethereum) for the right amount
   to the address in ``CryptoCheckout.kt``.
3. ``python3 tools/mint_licence.py --plan yearly --device ABCD123456 --serial <txid>``
4. Send the printed line back. The operator pastes it into *Pro freischalten* and is done.

First-time setup
----------------
    python3 tools/mint_licence.py --init

writes ``tools/private/licence_signing_key.pem`` and prints the Kotlin constant to paste into
``LicencePublicKey.kt``. **Do this once, before the first sale, and back the key up**: a lost
private key means every licence you ever sold must be re-minted with a new key and a new build.
The key that ships in the repository is a demo key whose private half was discarded on purpose
so that nobody — including anyone who forks this repo — can mint a working licence.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRIVATE_DIR = ROOT / "tools" / "private"
PRIVATE_KEY = PRIVATE_DIR / "licence_signing_key.pem"

PREFIX = "CAR1"

# The public half of the demo key committed with the app. Used by --verify when no private key
# is present. Keep this in sync with LicencePublicKey.kt if you replace the key.
DEMO_PUBLIC_KEY_BASE64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEX9y2jKeTryNpA0TJz9C-Ghm8Lxd8ysDaV5EwL3tsc1vv"
    "G3Xf3sAdjHzWZ7ezf0hOafsBNLYikpXmIhSQFZXusg"
)
PLANS = ("monthly", "yearly", "lifetime")
TERM_DAYS = {"monthly": 31, "yearly": 366, "lifetime": 0}


def b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def epoch_day(when: dt.date) -> int:
    return (when - dt.date(1970, 1, 1)).days


def run_openssl(args: list[str], stdin: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(args, input=stdin, capture_output=True, check=True)
    except FileNotFoundError:
        sys.exit("openssl is not installed (it is the only signing dependency).")
    except subprocess.CalledProcessError as error:
        sys.exit(f"openssl failed: {error.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


def init_key() -> None:
    if PRIVATE_KEY.exists():
        sys.exit(f"refusing to overwrite the existing key at {PRIVATE_KEY.relative_to(ROOT)}")
    PRIVATE_DIR.mkdir(parents=True, exist_ok=True)
    run_openssl(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(PRIVATE_KEY)])
    PRIVATE_KEY.chmod(0o600)
    public_der = run_openssl(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout", "-outform", "DER"])
    public_b64 = b64url_encode(public_der)
    print(f"private key written to {PRIVATE_KEY.relative_to(ROOT)} (git-ignored, back it up)\n")
    print("Paste this into app/src/main/kotlin/com/constructar/app/billing/LicencePublicKey.kt:\n")
    print('    const val DEMO_PUBLIC_KEY_BASE64: String =')
    print(f'        "{public_b64}"')
    print(
        "\nThen rebuild and ship that build: an APK built with the old key will reject every\n"
        "licence minted with the new one."
    )


def mint(plan: str, device: str, serial: str, issued: dt.date) -> str:
    if not PRIVATE_KEY.exists():
        sys.exit("no private key: run `python3 tools/mint_licence.py --init` first")
    payload = {
        "plan": plan.upper(),
        "device": device.strip().upper(),
        "issuedEpochDay": epoch_day(issued),
        "expiresEpochDay": 0 if TERM_DAYS[plan] == 0 else epoch_day(issued) + TERM_DAYS[plan],
        "serial": serial.strip(),
    }
    payload_json = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    payload_b64 = b64url_encode(payload_json)
    signature = run_openssl(["openssl", "dgst", "-sha256", "-sign", str(PRIVATE_KEY)], stdin=f"{PREFIX}.{payload_b64}".encode("ascii"))
    return f"{PREFIX}.{payload_b64}.{b64url_encode(signature)}"


def verify(licence: str, device: str, today: dt.date) -> tuple[bool, str]:
    """Check a licence string the same way the app does, without a phone."""
    parts = licence.strip().split(".")
    if len(parts) != 3 or parts[0] != PREFIX:
        return False, "malformed"
    if PRIVATE_KEY.exists():
        public_pem = run_openssl(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout"])
    else:
        public_der = b64url_decode(DEMO_PUBLIC_KEY_BASE64)
        public_pem = run_openssl(["openssl", "pkey", "-pubin", "-inform", "DER", "-outform", "PEM"], stdin=public_der)
    PRIVATE_DIR.mkdir(parents=True, exist_ok=True)
    key_file = PRIVATE_DIR / "verify_pub.pem"
    signature_file = PRIVATE_DIR / "verify_sig.der"
    key_file.write_bytes(public_pem)
    signature_file.write_bytes(b64url_decode(parts[2]))
    try:
        checked = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(key_file), "-signature", str(signature_file)],
            input=f"{PREFIX}.{parts[1]}".encode("ascii"),
            capture_output=True,
        )
    finally:
        key_file.unlink(missing_ok=True)
        signature_file.unlink(missing_ok=True)
    if checked.returncode != 0:
        return False, "signature does not match the key"
    try:
        payload = json.loads(b64url_decode(parts[1]).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return False, "payload is not JSON"
    if str(payload.get("device", "")) and str(payload["device"]).upper() != device.strip().upper():
        return False, f"bound to device {payload['device']}, not {device}"
    expires = int(payload.get("expiresEpochDay", 0))
    if expires and epoch_day(today) > expires:
        return False, "expired"
    days = "" if not expires else f", {expires - epoch_day(today)} days left"
    return True, f"valid {payload.get('plan')}{days}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Mint and inspect ConstructAR licences.")
    parser.add_argument("--init", action="store_true", help="generate the signing key pair (once)")
    parser.add_argument("--plan", choices=PLANS, help="what the operator bought")
    parser.add_argument("--device", default="", help="device tag shown in the app, or empty for any device")
    parser.add_argument("--serial", default="", help="order reference, e.g. the transaction id")
    parser.add_argument("--issued", default=dt.date.today().isoformat(), help="issue date, YYYY-MM-DD")
    parser.add_argument("--verify", metavar="LICENCE", help="check a licence instead of minting one")
    args = parser.parse_args()

    if args.init:
        init_key()
        return
    if args.verify:
        ok, detail = verify(args.verify, args.device, dt.date.today())
        print(("OK: " if ok else "INVALID: ") + detail)
        sys.exit(0 if ok else 1)
    if not args.plan:
        parser.error("either --init, --verify or --plan is required")
    issued = dt.date.fromisoformat(args.issued)
    print(mint(args.plan, args.device, args.serial, issued))


if __name__ == "__main__":
    main()
