<!--
  Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
  All rights reserved. Proprietary and confidential - see LICENSE.
  SPDX-License-Identifier: LicenseRef-Proprietary
-->

# Licence and payment

**All Rights Reserved.** ConstructAR is proprietary software; the free tier is a licence to use a
limited feature set, not an open-source grant. See [LICENSE](../LICENSE).

| Plan | Price | Term | Plan value |
|------|-------|------|------------|
| Free | 0 € | forever | 1 room, unlimited runs, CSV, watermarked PDF |
| Pro monthly | 10 € | 31 days | unlimited rooms, clean PDF, GLB/PLY |
| Pro yearly | 100 € | 366 days | same, two months free |
| Pro lifetime | 999 € | never expires | same, paid once |
| Trial | 0 € | 7 days | Pro, once per installation |

Pro is sold **only** for SOL, BTC and ETH. There is no card, no account, no store billing and no
checkout server — the app does not even hold the `INTERNET` permission, so no billing SDK could
work here even if one were wanted.

## Payment addresses

These three addresses in `core/src/main/kotlin/com/constructar/core/licence/CryptoCheckout.kt`
are the product's only payment endpoints. The app displays them with *copy* and *open wallet*
buttons; nothing else in the product can receive money.

| Coin | Address |
|------|---------|
| SOL | `79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8` |
| BTC | `bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f` |
| ETH | `0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7` |

Because the amount is set in euro and paid in crypto, the euro price is the reference: convert at
the rate of the day, and treat a transfer of at least the euro value as paid. Quote the rate in
the reply so both sides agree on the number that was used.

## The licence

A licence is one line of text:

```
CAR1.<base64url(payload JSON)>.<base64url(ECDSA P-256 SHA-256 signature)>
```

The payload is `{"plan":"MONTHLY|YEARLY|LIFETIME","device":"ABCDEF123456","issuedEpochDay":…,
"expiresEpochDay":…,"serial":"…"}`, and the signature covers the ASCII bytes of
`CAR1.<payload>` — the exact text that travels — so no JSON canonicalisation can invalidate a
valid key.

The APK ships only the **public** key
(`app/src/main/kotlin/com/constructar/app/billing/LicencePublicKey.kt`). Verification happens on
the phone, offline, in `LicenceVerifier` (`:core`, 13 unit tests). The result is a specific
verdict, never a generic "invalid": malformed, wrong signature, bound to another device, or
expired.

`device` binds a licence to one phone. The tag is a 12-hex-character prefix of SHA-256 over a
random installation id generated on first start — **no hardware identifier, no IMEI, no
`ANDROID_ID`**, so the developer can tie a purchase to a phone without either side handling
anything that identifies a person. An empty `device` means "any device"; use it for a lifetime
licence only if you are willing to accept sharing between phones.

`expiresEpochDay` is a **UTC day number**, not a timestamp: a licence never expires "early"
because of a time zone, and a phone with a wrong clock can only ever hurt itself.

## Minting a licence (the part you do by hand)

One-time setup, **before the first sale**:

```bash
python3 tools/mint_licence.py --init
```

This writes `tools/private/licence_signing_key.pem` (git-ignored, `chmod 600`) and prints the
Kotlin constant to paste into `LicencePublicKey.kt`. **Back the private key up.** Losing it means
every licence ever sold has to be re-minted against a new key and a new build; leaking it means
anyone can mint valid licences.

The key committed to this repository is a **demo key whose private half was destroyed**. That is
intentional: nobody — including anyone who forks this repo — can produce a licence that a build
of it accepts. `LicencePublicKey.isDemoKey` is true while that is the case, so a release build
can refuse to be shipped with it (see the release checklist).

Per sale:

1. The operator sends: plan, the device code shown in *Pro freischalten*, and the transaction id.
2. Check the transaction on a block explorer: right address, at least the euro value converted.
3. Mint:

   ```bash
   python3 tools/mint_licence.py --plan yearly --device ABCD123456 --serial <txid>
   ```

4. Send the printed line back. The operator pastes it into the paywall and taps *Activate*.

`python3 tools/mint_licence.py --verify "<licence>" --device ABCD123456` checks a key the same
way the app does, which is the fastest way to answer "it says it is invalid".

## Trial

Seven days of Pro, started by an explicit tap, once per installation. It exists so that a
sceptical foreman can measure a real room with the real features before any money moves — which
is the only honest way to sell a measuring tool. The start day is stored locally; clearing app
data resets it. That is an acceptable trade: the alternative is a server call, and there is no
server.

## What happens when a subscription lapses

Nothing is deleted and nothing is hidden. Rooms already measured stay readable and exportable;
the PDF gains a watermark, and *new* rooms and the GLB/PLY exports need Pro again. A tool that
takes a foreman's measurements hostage would be extortion, and the review fallout would be worth
less than the renewals.

## Honest limits

- **Offline verification is best-effort.** The check is genuine ECDSA against a key that is not
  in the APK, which stops casual copying — but a rooted device can always patch a client-side
  check. Any offline app has this property; a server would fix it and break the offline promise,
  and the promise is the product.
- **Refunds and reversals** are manual: a licence, once issued, is not revoked by the app. Keep
  the serial in the payload so you can trace a licence back to a transaction.
- **Currency risk** is yours: prices are in euro, receipts are in crypto. Convert at the rate of
  the day and keep the quote in the reply.
- **Tax**: selling software for crypto is a taxable event in most jurisdictions. This document is
  an engineering note, not tax advice.
