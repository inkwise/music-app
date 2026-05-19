package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun ArtistText(
    artist: String,
    artistIds: List<Long>,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    if (artistIds.isEmpty()) {
        Text(
            text = artist,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val names = artist.split(", ")
    if (names.size != artistIds.size) {
        Text(
            text = artist,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start
    ) {
        names.forEachIndexed { index, name ->
            val id = artistIds[index]
            Text(
                text = name,
                style = style,
                color = color,
                maxLines = 1,
                modifier = Modifier.clickable { onArtistClick(id) }
            )
            if (index < names.size - 1) {
                Text(
                    text = ", ",
                    style = style,
                    color = color,
                    maxLines = 1
                )
            }
        }
    }
}
