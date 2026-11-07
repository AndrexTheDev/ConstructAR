/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.app.data.ProjectRepository

/**
 * What the search field on the room list looks through.
 *
 * The app knows three names for a job - what the room is called, what the site is called, and who
 * it is for - and the search looked at the first two. The third is the one the operator actually
 * remembers: nobody walks into the app remembering that the job was "Wohnzimmer OG", everybody
 * remembers that it was for **Schmidt**. Typing "Schmidt" into a list that holds his room answered
 * "Nothing matches", and the row did not carry his name either, so the match could not even have
 * been seen (finding 144).
 *
 * Kept out of the composable for the usual reason: the rule is one function and can be asserted
 * without a phone.
 */
internal object ProjectFilter {

    /**
     * True when [query] - trimmed, case-insensitive - appears in the room's name, its site or its
     * customer; a blank query matches everything, which is what an empty field means.
     *
     * `ignoreCase` is the whole point of a search typed with a glove on: the operator does not
     * capitalise the customer's name when he is in a hurry.
     */
    fun matches(query: String, project: ProjectRepository.ProjectSummary): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return project.name.contains(needle, ignoreCase = true) ||
            project.siteName.contains(needle, ignoreCase = true) ||
            project.customerName.contains(needle, ignoreCase = true)
    }

    /**
     * Rows below the large Continue card. The repository supplies the most recent project first;
     * showing row zero again on the default screen makes one job look like two. A search still
     * searches every project, including that one, so typing its customer can never return nothing.
     */
    fun listRows(
        projects: List<ProjectRepository.ProjectSummary>,
        query: String,
    ): List<ProjectRepository.ProjectSummary> {
        val matches = projects.filter { matches(query, it) }
        return if (query.isBlank()) matches.drop(1) else matches
    }
}
