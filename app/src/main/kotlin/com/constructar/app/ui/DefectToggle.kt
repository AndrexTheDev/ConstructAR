/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.app.R

/**
 * What the button on a defect row does, and what it says it does.
 *
 * The row has carried one `Check` icon since the defect list existed, in both states, with the
 * content description "Mark resolved / open again" (finding 136). So on a defect that is *already*
 * resolved - the row the operator is most likely to tap while reading through the punch list - the
 * tick means "done", and tapping it **undoes** done. Nothing asks first, and the consequence lands
 * on the document the customer signs: the defect he had just closed reappears as outstanding in the
 * protocol. The detail line does say "Resolved", so the state is not hidden; the *action* is.
 *
 * One decision, one place, so the icon and the sentence cannot disagree: a tick for "this is still
 * open, close it", an undo arrow for "this is closed, open it again".
 */
internal object DefectToggle {

    /** The two directions a defect can be moved in. */
    enum class Action {
        /** An open defect: the tap closes it. */
        RESOLVE,

        /** A resolved defect: the tap reopens it. */
        REOPEN,
    }

    fun action(resolved: Boolean): Action = if (resolved) Action.REOPEN else Action.RESOLVE

    /** The sentence for the button - also what a screen reader announces before the tap. */
    fun labelRes(action: Action): Int = when (action) {
        Action.RESOLVE -> R.string.field_defect_resolve
        Action.REOPEN -> R.string.field_defect_reopen
    }

    /**
     * What the defect *is*, not what the tap would do - the half a screen reader could not hear.
     *
     * The button's label is the action ("Mark resolved" / "Open again"), because that is what a
     * tap does; a TalkBack user who lands on the button of a resolved defect therefore hears an
     * instruction to reopen it without being told it is closed. The sighted operator has the
     * detail line ("· Resolved") and the tick's own shape; the spoken row has neither, so the
     * state travels on the button as `stateDescription` (finding 211). Reuses the two words the
     * protocol already prints for a defect's status, so screen and paperwork cannot disagree.
     */
    fun stateRes(resolved: Boolean): Int =
        if (resolved) R.string.pdf_status_resolved else R.string.pdf_status_open
}
