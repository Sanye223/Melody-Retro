package com.melody.player.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 以 content://<package>.artwork/album/<albumId> 的形式对外提供专辑封面。
 * 只在本应用内使用（exported=false），供 Coil 与 Media3 播放通知读取。
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx: Context = context ?: return null
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null
        val bytes = ArtworkStore.artworkBytes(ctx, albumId) ?: return null

        val dir = File(ctx.cacheDir, "artwork").apply { if (!exists()) mkdirs() }
        val target = File(dir, "stream_$albumId.jpg")
        target.writeBytes(bytes)
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
