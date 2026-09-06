/**
 * Room 数据访问对象：歌曲表。
 */
package com.inkwise.music.data.dao

import androidx.room.*
import com.inkwise.music.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * 歌曲表（songs）的数据访问对象。
 *
 * 列表查询统一返回 Flow，插入/删除后 UI 自动收到新数据；
 * 单条查询用 suspend 一次性返回，供播放器按 id 取歌等"要一次结果"的场景。
 * 本地歌曲与云端歌曲共用本表，故多处查询带 is_local 条件加以区分。
 */
@Dao
interface SongDao {
    /** 查询全部歌曲（本地 + 云端），按标题升序 */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    /** 仅查询本地扫描入库的歌曲（is_local = 1），按标题升序 */
    @Query("SELECT * FROM songs WHERE is_local = 1 ORDER BY title ASC")
    fun getLocalSongsOnly(): Flow<List<Song>>

    /** 按本地自增主键取单首歌，取不到返回 null */
    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getSongById(id: Long): Song?

    /** 按服务端音乐 id 取歌，用于把云端歌曲映射回本地记录；不存在返回 null */
    @Query("SELECT * FROM songs WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getSongByCloudId(cloudId: Long): Song?

    /** 插入或更新单首歌（主键冲突时整行覆盖），返回新插入行的 rowId */
    @Upsert
    suspend fun insertSong(song: Song): Long

    /** 批量插入或更新歌曲，用于媒体扫描结果一次性入库 */
    @Upsert
    suspend fun insertSongs(songs: List<Song>)

    /** 按实体删除指定歌曲（其在歌单中的关联记录随之级联删除） */
    @Delete
    suspend fun deleteSong(song: Song)

    /** 按主键删除歌曲 */
    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: Long)

    /** 清空整张歌曲表（注销账号 / 重置本地库时用） */
    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    /** 按文件绝对路径精确查找歌曲；路径是本库内唯一业务键，用于扫描去重 */
    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getSongByPath(path: String): Song?

    /** 本地歌曲按歌手名模糊过滤（LIKE %name%），按标题升序 */
    @Query("SELECT * FROM songs WHERE is_local = 1 AND artist LIKE '%' || :name || '%' ORDER BY title ASC")
    fun getLocalSongsByArtistName(name: String): Flow<List<Song>>

    /** 云端歌曲按歌手名模糊过滤（LIKE %name%），按标题升序 */
    @Query("SELECT * FROM songs WHERE is_local = 0 AND artist LIKE '%' || :name || '%' ORDER BY title ASC")
    fun getCloudSongsByArtistName(name: String): Flow<List<Song>>

    /** 按专辑名精确查询歌曲（本地 + 云端），按标题升序 */
    @Query("SELECT * FROM songs WHERE album = :albumName ORDER BY title ASC")
    fun getSongsByAlbum(albumName: String): Flow<List<Song>>

    /** 仅本地歌曲按专辑名精确查询，按标题升序 */
    @Query("SELECT * FROM songs WHERE is_local = 1 AND album = :albumName ORDER BY title ASC")
    fun getLocalSongsByAlbum(albumName: String): Flow<List<Song>>

    /** 更新歌曲（按主键匹配整行覆盖） */
    @Update
    suspend fun updateSong(song: Song)
}
