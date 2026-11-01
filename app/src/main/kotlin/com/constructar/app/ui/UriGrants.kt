/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * A permanent folder grant is a courtesy, never a condition.
 *
 * Both writers (protocol export, backup) pick a folder through `OpenDocumentTree` and then call
 * `takePersistableUriPermission`, so the app may come back to that tree later. Two things go
 * wrong with that call when it is treated as a precondition:
 *
 *  * **Providers that cannot grant permanently.** Some document providers answer the request
 *    with a `SecurityException` - and because the call sat inside the export's own `runCatching`,
 *    the operator saw "Export failed: Failed to take persistable permission" over a folder he had
 *    just picked, with the protocol already built and nothing wrong with it.
 *  * **A full grant table.** Android keeps a limited number of persisted grants per app (128 by
 *    default). A tradesman who exports into a new folder on every job eventually reaches it, and
 *    the same exception ends the next export - permanently, because he cannot clear grants from
 *    inside the app.
 *
 * The write itself only needs the transient grant the picker hands to the activity for the
 * duration of this screen, so the permanent grant is best-effort: [take] reports whether it was
 * taken and swallows the refusal.
 */
object UriGrants {

    /** Runs [take], returning whether the grant was taken; a refusal is not an error. */
    inline fun take(take: () -> Unit): Boolean = runCatching { take() }.isSuccess
}
