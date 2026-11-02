/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

/**
 * What activation does about the identity of the build it is running in.
 *
 * `AppIntegrity` remembers the signing certificate on first start and refuses paid features once it
 * changes. That is the right instinct - a re-signed APK is what patching out a licence check looks
 * like - but the refusal used to happen *before the key was even looked at*, and the remembered
 * digest was only ever written when nothing had been stored yet. The class documents the honest
 * path out ("a legitimate key rotation shows up once as unconfirmed and is re-trusted after
 * reactivation"), and that path did not exist: a rotated key locked the installation out for good,
 * with activation answering `UntrustedBuild` to every key the operator bought - permanently free
 * features on a phone full of his own unpaid-for work, and the only way out being "clear app data",
 * which deletes every room he measured.
 *
 * So the decision is one function with three answers, and only two signals feed it:
 *
 *  * **A debugger on a release build** is live tampering. No key lifts it.
 *  * **A changed certificate** is re-trusted by a licence that verifies: somebody who paid holds a
 *    signature only the developer's private key can produce, and it names this installation's tag.
 *    A patched build with no valid licence stays locked out, which is the case the check exists for.
 */
internal enum class ActivationGate {
    /** Store the licence and adopt the new certificate digest as trusted. */
    ADOPT_SIGNATURE,

    /** Store the licence; the build's identity is unchanged. */
    ACCEPT,

    /** Refuse the key as untrusted - the diagnostic says the build cannot vouch for itself. */
    REFUSE,
}

/**
 * Decides the gate for an activation attempt.
 *
 * [licenceValid] is the *verification* result, so an unreadable or forged key can never be used to
 * re-trust a changed build: refusing is the whole point of the certificate check.
 */
internal fun activationGate(report: AppIntegrity.Report, licenceValid: Boolean): ActivationGate = when {
    // A debugger attached to a *release* build is somebody stepping through this very check. The
    // debug build of a beta tester is exempt - there `debuggable` is true and this is normal.
    !report.debuggable && report.debuggerAttached -> ActivationGate.REFUSE

    report.certificateChanged && licenceValid && report.signature != null -> ActivationGate.ADOPT_SIGNATURE

    report.certificateChanged -> ActivationGate.REFUSE

    else -> ActivationGate.ACCEPT
}
