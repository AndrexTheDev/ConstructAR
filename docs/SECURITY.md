# Security and licence hardening

*How far an offline app can go — and where it stops.*

ConstructAR has no network stack, no account and no server, so it cannot phone home to check
anything. That is the product's whole point (a basement, a faraday cage of a switch room, a
customer who does not want his floor plan in somebody's cloud), and it shapes what "crack-proof"
can honestly mean here.

---

## Threat model

| Attacker | What they try | What happens |
|----------|---------------|--------------|
| A tradesman who found the price too high | Type a made-up licence, or a screenshot of somebody else's | `BadSignature` / `DeviceMismatch`. Licences are ECDSA P-256 signatures over the payload, verified with a public key that ships in the APK. Without the private key no valid string exists. |
| A crew sharing one purchase | Copy the licence text to a second phone | The payload carries the buyer's **device tag** (a truncated SHA-256 of a random installation id). The second phone computes a different tag and is refused. |
| The same person, on a rooted phone | Edit `SharedPreferences`: set the trial start back, or raise the clock watermark | The trial start and the watermark are **HMAC-sealed** with a key derived from the installation id (`LicenceSeal`). A value that fails its seal counts as tampering: the trial counts as used and the watermark cannot be lowered. |
| The same person, with the system clock | Set the date back a year to resurrect an expired subscription | `ClockGuard` keeps a **high-water mark**: expiry is judged against the last day this installation has ever seen, not against the clock. A one-day tolerance absorbs time zones and a late NTP sync. |
| Somebody who patches the APK and re-signs it | Delete the licence check, ship it to colleagues | `AppIntegrity` remembers the signing certificate on first run and reports a re-signed build; a release build with an attached debugger counts the same. Paid features are refused — **the operator's projects stay readable and exportable**, because a licensing problem must never hold somebody's measurements hostage. |
| Somebody who imports a shared backup | Fill a free installation with twenty rooms from a colleague's zip | Restoring obeys the same plan as creating (`Entitlements.maxProjects`). Files beyond the limit are *not* imported and *not* altered; the app says how many were held back, and they import after upgrading. |
| Somebody who shares a backup, or just a project name | Put a spreadsheet formula in a room name so it runs on the machine of whoever opens the exported material list | `MaterialCsvWriter.escape` prefixes any cell a spreadsheet would evaluate (`= + @ \t \r`, and `-` unless what follows is a plain number) with the text marker `'`, after RFC 4180 quoting. A leading minus on a real number stays a number |
| R8, accidentally | Optimise away the verifier because it "looks unreachable" | The workflow greps the **release** DEX for `SHA256withECDSA`, `CAR1.`, `HmacSHA256` and the public key. A build that lost its verification path fails CI. |

---

## What is deliberately *not* claimed

* **This is not DRM.** Everything runs on a device the owner controls. A determined attacker
  with the APK, a disassembler and patience can remove any check in this repository — that is
  true of every offline app, including the expensive ones. The goal is to make "patch one
  boolean and pass it round the site" stop working, and to make the next step cost real effort.
* **No certificate pinning.** The repository ships no release keystore, so pinning a digest in
  the source would break the project's own CI build. `AppIntegrity` uses trust-on-first-use
  instead: it detects a *change*, not an unknown first install. If you ship with a stable
  keystore, pin its SHA-256 in `AppIntegrity` and replace the TOFU comparison — the hook is
  already there. The change is not a life sentence: a licence that verifies re-trusts the new
  certificate (`activationGate`), so a rotated key costs the operator one activation and no data,
  while a patched build with no valid licence still gets nothing.
* **No root, emulator or hooking-framework detection.** Those heuristics produce false
  positives on cheap but legitimate hardware, and a foreman whose phone is "detected as rooted"
  would be locked out of work he paid for. A false lockout is worse than a theoretical bypass.
* **No remote revocation.** There is no server, so a refunded licence keeps working until it
  expires. That is a deliberate trade: the same absence of a server is why the app works in a
  basement.
* **No obfuscation theatre.** The licence classes are left obfuscatable (no `-keep`), but the
  code is not encrypted, packed or hidden behind native tricks. Packing would trade a real
  startup cost and real crash risk for a few minutes of an attacker's time.

---

## Operational rules for the developer

1. **Mint your own key before the first sale.**
   `python3 tools/mint_licence.py --init` prints a new public key; paste it over
   `LicencePublicKey.PUBLIC_KEY_BASE64` and keep the private half offline (see
   [BILLING.md](BILLING.md)). Until then the app carries a *demo* key whose private half was
   discarded, and the help screen shows a developer note (`licence_demo_key`) saying so.
2. **Sign every release with the same keystore.** A new signing key looks like a repackaged
   build to `AppIntegrity`; existing installations will show "unconfirmed" until the licence is
   entered again.
3. **Never ship a debug build to a customer.** `isDebuggable` disables the debugger check (so
   CI and local development work), which means a debug APK is not protected by that rule.
4. **Check the flags after a release build.** The `android-build` workflow already fails if the
   release DEX lost the verifier or the key; read that step before publishing an APK.

---

## Where the code lives

| Concern | File |
|---------|------|
| Licence wire format and signature check | `core/src/main/kotlin/com/constructar/core/licence/{LicenceFormat,LicenceVerifier}.kt` |
| Clock watermark, seal, entitlement decision | `core/src/main/kotlin/com/constructar/core/licence/TamperGuard.kt` |
| Android store (prefs, sealing, integrity) | `app/src/main/kotlin/com/constructar/app/billing/{EntitlementStore,AppIntegrity}.kt` |
| Spreadsheet-formula guard on exported CSV | `export/src/main/kotlin/com/constructar/export/csv/MaterialCsvWriter.kt` |
| Survives-a-crash file writes | `core/src/main/kotlin/com/constructar/core/io/SafeFiles.kt` |
| Tests | `core/src/test/kotlin/com/constructar/core/licence/TamperGuardTest.kt`, `…/core/io/SafeFilesTest.kt` |

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
