package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ArtistText(
    artist: String,
    artistIds: List<Long>,
    onArtistClick: (Long) -> Unit,
    onArtistNameClick: ((String) -> Unit)? = null,
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
            modifier = if (onArtistNameClick != null) {
                modifier.clickable { onArtistNameClick(artist) }
            } else {
                modifier
            }
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
