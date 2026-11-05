/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.SiteColors

/**
 * Three screens, thirty seconds, no account.
 *
 * An operator who has never seen an AR app needs to know exactly one thing before the camera
 * opens: *what am I supposed to do with this phone in a room?* The introduction answers it in
 * the same three steps the app itself uses — scan, draw, hand over — and can be skipped in one
 * tap, because it is repeated on the project screen anyway.
 *
 * The position is `rememberSaveable`, and *back* exists. A `remember` restarted the deck at slide
 * one whenever the phone turned, and with only *next* and *skip* on the screen a slide tapped past
 * could not be read again at all - the introduction is shown exactly once in the life of the app
 * (finding 145).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    // rememberSaveable, not remember: see OnboardingStep.
    var step by rememberSaveable { mutableStateOf(0) }
    val titles = listOf(
        R.string.onboard_1_title,
        R.string.onboard_2_title,
        R.string.onboard_3_title,
    )
    val bodies = listOf(
        R.string.onboard_1_body,
        R.string.onboard_2_body,
        R.string.onboard_3_body,
    )
    val icons = listOf(Icons.Default.ViewInAr, Icons.Default.PlayArrow, Icons.Default.Share)

    // Scrollable, and deliberately no longer centred: the logo, three dots, a headline, a body
    // and three buttons add up to more than a 640 dp phone shows at a 200 % font scale, and a
    // `fillMaxSize` column without a scroll clips what does not fit - which here is *next*, *back*
    // and *skip*, i.e. every way out of a screen the app shows before anything else works.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.constructar_logo),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape),
        )

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(titles[step]),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            // The 3D formats are a Pro feature (see `Entitlements.canExportMesh`), so the last
            // slide names the plan instead of promising them to everybody in the first thirty
            // seconds of the app (finding 95).
            text = if (step == bodies.lastIndex) {
                stringResource(R.string.onboard_3_body, stringResource(R.string.plan_pro))
            } else {
                stringResource(bodies[step])
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One dot per slide, counted from the titles rather than written as a 3 that can drift.
            titles.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == step) 26.dp else 10.dp)
                        .background(
                            if (index == step) SiteColors.accent else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        GloveButton(
            label = stringResource(
                if (OnboardingStep.isLast(step, titles.lastIndex)) R.string.onboard_start else R.string.onboard_next,
            ),
            icon = icons[step],
            onClick = {
                if (OnboardingStep.isLast(step, titles.lastIndex)) {
                    onDone()
                } else {
                    step = OnboardingStep.next(step, titles.lastIndex)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Going back one slide. Shown only when there is one: a disabled "back" on the first slide
        // is a button that lies about what it can do (finding 145).
        if (OnboardingStep.canGoBack(step)) {
            TextButton(
                onClick = { step = OnboardingStep.previous(step) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboard_back))
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboard_skip))
        }
    }
}
