package com.acoder.gallery.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionManager {
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ); Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        ); else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullAccess(c: Context) =
        permissions().filterNot { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
            .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    fun hasAnyAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) hasFullAccess(c) || ContextCompat.checkSelfPermission(
            c,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED else permissions().all {
            ContextCompat.checkSelfPermission(
                c,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
}
