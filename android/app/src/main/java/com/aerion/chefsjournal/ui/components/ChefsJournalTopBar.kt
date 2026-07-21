package com.aerion.chefsjournal.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Compact top header — shorter than Material's fixed 64dp TopAppBar.
 *
 * Content row is 48dp. The status-bar inset is NOT applied here: the outer
 * MainAppScaffold already pads the NavHost by the status bar, so adding it
 * again (as Material's TopAppBar does) would double the top gap.
 *
 * Place this as the first child of a screen's body Column so the list sits
 * flush beneath it with no extra gap.
 */
@Composable
fun ChefsJournalTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
    }
}
