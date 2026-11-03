/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.constructar.app.R
import com.constructar.app.ui.theme.Jamaica

/**
 * The installation tag the operator has to send with his payment - and a way to copy it.
 *
 * The purchase flow has exactly one string in it that the operator has to *transmit*: this tag,
 * twelve hex characters, sent by mail or messenger together with the transaction id, and it is
 * the only thing the developer needs to mint a key that will activate on this phone. Both screens
 * that show it - the paywall, at the end of the three steps, and the help screen next to the plan
 * chip - printed it as plain text with no way to copy it, while the wallet addresses a few lines
 * above it in the same paywall each had a copy button (finding 133). So the one value he must send
 * had to be retyped by hand, character by character, from a phone screen into the same phone - and
 * a single wrong character produces a licence for somebody else's installation, which fails on
 * activation and costs a round trip with the only human in the loop.
 */
internal object DeviceCode {

    /**
     * The tag as it should reach the clipboard: no spaces, upper case.
     *
     * `LicenceVerifier.deviceMatches` compares trimmed and case-insensitively, so this is exactly
     * the form a minted licence has to contain - copying the *displayed* text unchanged is right,
     * and normalising here means the clipboard cannot carry a stray space from a font or a line
     * break from a narrow screen.
     */
    fun normalise(tag: String): String = tag.filterNot { it.isWhitespace() }.uppercase()
}

/** Puts [value] on the clipboard. No permission, no service: the operator's own phone. */
internal fun copyToClipboard(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(value.take(8), value))
}

/**
 * The tag in [style]/[color], with a copy button beside it.
 *
 * One composable for both screens on purpose: a second copy of "print the code" is how one of
 * them ends up without the button again (rule 26).
 */
@Composable
fun DeviceCodeLine(
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = Jamaica.Lime,
) {
    val context = LocalContext.current
    val code = DeviceCode.normalise(tag)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(code, style = style, color = color)
        IconButton(onClick = { copyToClipboard(context, code) }) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.paywall_copy),
                tint = color,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
