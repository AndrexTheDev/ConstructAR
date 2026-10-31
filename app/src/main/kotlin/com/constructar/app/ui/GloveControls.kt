package com.constructar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.constructar.app.ui.theme.SiteColors

/** Visual weight of an action: primary (orange), secondary (outline), danger (red). */
enum class ActionTone { PRIMARY, SECONDARY, DANGER }

/**
 * One glove-sized button.
 *
 * Deliberately not a `FloatingActionButton` (too small, no label) and not an `IconButton`
 * (a glove cannot hit a 24 dp glyph). 72 dp tall, icon + label, full-width by default so a
 * row of three still gives ~120 dp per button on a 6" phone.
 */
@Composable
fun GloveButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.PRIMARY,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val container = when (tone) {
        ActionTone.PRIMARY -> MaterialTheme.colorScheme.primary
        ActionTone.SECONDARY -> MaterialTheme.colorScheme.surfaceVariant
        ActionTone.DANGER -> SiteColors.danger
    }
    val content = when (tone) {
        ActionTone.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        ActionTone.SECONDARY -> MaterialTheme.colorScheme.onSurface
        ActionTone.DANGER -> Color.Black
    }
    val shape = RoundedCornerShape(18.dp)
    val height = Modifier.heightIn(min = 72.dp)

    if (tone == ActionTone.SECONDARY) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = modifier.then(height),
        ) {
            ButtonContent(label, icon, content, selected)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.tertiary else container,
                contentColor = content,
            ),
            modifier = modifier.then(height),
        ) {
            ButtonContent(label, icon, content, selected)
        }
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector, content: Color, selected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A big read-out used for lengths and statuses, e.g. `Length: 14.82 m`. */
@Composable
fun MeasurementReadout(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.displaySmall, color = accent)
    }
}

/** Row of evenly weighted glove buttons. */
@Composable
fun GloveButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
