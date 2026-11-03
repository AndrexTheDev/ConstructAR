/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Whether leaving the field book would throw away something the operator typed.
 *
 * The site diary is written on a wet scaffold with one thumb: a paragraph about the missing
 * Schlitze, a defect title, the location it belongs to. All three live in `rememberSaveable`
 * drafts, which survive a rotation - and only a rotation. The back arrow and the system back
 * gesture left the screen outright, so a diary line went with them without a question, on the one
 * screen in the app whose entire purpose is that the day gets written down (finding 143).
 *
 * The same rule the project screen follows since findings 124 and 142, in the place it is most
 * expensive to get wrong: the app's own sentence is "typed work is work", and a note typed in the
 * rain is the work.
 *
 * A severity chip on its own is nothing - the defect needs a title before there is anything to
 * lose - so the chosen severity is deliberately not part of this answer.
 */
internal object FieldDrafts {

    /** Blank text is not a draft; whitespace only is what a glove leaves behind. */
    fun unsaved(diaryText: String, defectTitle: String, defectLocation: String): Boolean =
        diaryText.isNotBlank() || defectTitle.isNotBlank() || defectLocation.isNotBlank()
}
