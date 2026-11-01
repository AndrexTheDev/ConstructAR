/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.billing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/**
 * Is this the app that was released?
 *
 * An offline app cannot prove that to a server, so the honest bar is lower: notice when the
 * installation has been *changed underneath the licence check* and stop granting paid features
 * on that copy — without touching the operator's own measurements.
 *
 * Two signals, both cheap and both free of permissions:
 *
 *  * **The signing certificate.** The digest seen at first run is remembered (trust on first
 *    use) and compared on every start. A re-signed APK — the usual result of patching out the
 *    licence check and re-packing — has a different certificate, so it can no longer claim Pro.
 *    Because nothing is pinned in the source, a legitimate key rotation is not misdetected as
 *    an attack: it shows up once as "unconfirmed" and is re-trusted after reactivation.
 *  * **A debugger.** `Debug` is inert and permission-free; on a release build, an attached
 *    debugger means somebody is stepping through the verification live.
 *
 * What this is **not**: DRM. Everything here runs on a device the user controls, so a
 * determined attacker can remove the check. The point is to make "patch one boolean and ship
 * it to your crew" stop working, and to make the *next* step cost real effort.
 */
object AppIntegrity {

    data class Report(
        val debuggable: Boolean,
        val debuggerAttached: Boolean,
        /** SHA-256 of the signing certificate, or `null` when it cannot be read. */
        val signature: String?,
        /** True when the stored certificate no longer matches the installed one. */
        val certificateChanged: Boolean,
    ) {
        val tampered: Boolean
            get() = certificateChanged || (!debuggable && debuggerAttached)
    }

    /**
     * Reads the certificate digest once and remembers it.
     *
     * [knownSignature] is what the store kept last time; [remember] is called only on the first
     * run (or when nothing could be read), so the value cannot be re-written to match a
     * patched APK later.
     */
    fun check(
        context: Context,
        knownSignature: String?,
        remember: (String) -> Unit,
    ): Report {
        val debuggable = isDebuggable(context)
        val signature = signatureDigest(context)
        val changed = knownSignature != null && signature != null && !knownSignature.equals(signature, ignoreCase = true)
        if (knownSignature == null && signature != null) remember(signature)
        return Report(
            debuggable = debuggable,
            debuggerAttached = debuggerAttached(),
            signature = signature,
            certificateChanged = changed,
        )
    }

    /** SHA-256 of the first signing certificate, uppercase hex, or `null` if unreadable. */
    fun signatureDigest(context: Context): String? = runCatching {
        val manager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
        buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }.getOrNull()

    fun isDebuggable(context: Context): Boolean = runCatching {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(false)

    fun debuggerAttached(): Boolean = runCatching {
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }.getOrDefault(false)

    private const val HEX = "0123456789ABCDEF"
}
