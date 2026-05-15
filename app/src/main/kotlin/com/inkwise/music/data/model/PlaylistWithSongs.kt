package com.inkwise.music.data.model

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class PlaylistWithSongs(
    @Embedded
    val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy =
            Junction(
                value = PlaylistSongEntity::class,
                parentColumn = "playlistId",
                entityColumn = "songId",
            ),
    )
    val songs: List<Song>,
)
