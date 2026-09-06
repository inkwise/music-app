/**
 * Room 数据访问对象：歌单表与歌单-歌曲关联表。
 */
package com.inkwise.music.data.dao

import androidx.room.*
import com.inkwise.music.data.model.PlaylistEntity
import com.inkwise.music.data.model.PlaylistSongEntity
import com.inkwise.music.data.model.PlaylistWithSongs
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * 歌单（playlists）与歌单-歌曲关联（playlist_song）的数据访问对象。
 *
 * 涉及"歌单 + 曲目"的查询都包在 @Transaction 里，保证关系数据一致性；
 * 排序依赖关联表的 sort_order 列，拖拽排序通过"清空后按新顺序重写"实现。
 */
@Dao
interface PlaylistDao {

    /** 新增歌单；主键冲突时整行覆盖（REPLACE），便于云端歌单同步时幂等落库 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    /** 批量写入歌单-歌曲关联，用于同步歌单曲目或整体重建关联 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(playlistSongs: List<PlaylistSongEntity>)

    /** 按本地歌单主键取歌单，取不到返回 null */
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    /** 事务内取单个歌单及其全部歌曲（关系查询），供歌单详情页观察 */
    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs>

    /** 按 sort_order 升序联查歌单曲目，还原用户手动排序后的实际顺序 */
    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.sort_order ASC
    """)
    fun getPlaylistSongsOrdered(playlistId: Long): Flow<List<Song>>

    /** 事务内取全部歌单及各自歌曲，供歌单列表页一次性观察 */
    @Transaction
    @Query("SELECT * FROM playlists")
    fun getAllPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    /** 新增歌单（冲突时抛异常）；与 REPLACE 版本并存，供不同写入路径按需选择 */
    @Insert
    suspend fun insert(playlist: PlaylistEntity)

    /** 按实体删除歌单（其下歌曲关联级联删除，歌曲本身保留） */
    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    /** 从歌单移除一首歌（只删关联行，不动歌曲本身） */
    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    /** 清空歌单的全部曲目关联（拖拽排序前清场、或整体覆盖歌单内容时用） */
    @Query("DELETE FROM playlist_song WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    /** 事务内重建歌单曲目顺序：先清空关联，再按 songIds 的顺序写回 sort_order */
    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<Long>) {
        clearPlaylistSongs(playlistId)
        insertPlaylistSongs(
            songIds.mapIndexed { sortOrder, songId ->
                PlaylistSongEntity(playlistId = playlistId, songId = songId, sortOrder = sortOrder)
            }
        )
    }

    /** 按服务端歌单 id 取歌单，用于云端同步时判断本地是否已存在同一歌单 */
    @Query("SELECT * FROM playlists WHERE cloudId = :cloudId LIMIT 1")
    suspend fun getPlaylistByCloudId(cloudId: Long): PlaylistEntity?

    /** 更新歌单元信息（标题 / 描述 / 本地封面），不改动 cloudId 与曲目列表 */
    @Query("UPDATE playlists SET title = :title, description = :description, coverUri = :coverUri WHERE id = :id")
    suspend fun updatePlaylist(id: Long, title: String, description: String, coverUri: String?)
}
