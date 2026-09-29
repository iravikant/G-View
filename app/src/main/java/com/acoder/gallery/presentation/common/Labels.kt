package com.acoder.gallery.presentation.common

import com.acoder.gallery.domain.model.MediaFilter
import com.acoder.gallery.domain.model.SortOrder

fun SortOrder.label() = when (this) {
    SortOrder.NEWEST -> "Date · Newest first"
    SortOrder.OLDEST -> "Date · Oldest first"
    SortOrder.NAME_ASC -> "Name · A to Z"
    SortOrder.NAME_DESC -> "Name · Z to A"
    SortOrder.SIZE_DESC -> "Size · Largest first"
    SortOrder.SIZE_ASC -> "Size · Smallest first"
    SortOrder.PHOTOS_FIRST -> "Photos first"
    SortOrder.VIDEOS_FIRST -> "Videos first"
}

fun MediaFilter.label() = when (this) {
    MediaFilter.ALL -> "All"
    MediaFilter.PHOTOS -> "Photos"
    MediaFilter.VIDEOS -> "Videos"
    MediaFilter.LARGE -> "Large files"
    MediaFilter.RECENT -> "Last 7 days"
    MediaFilter.FAVORITES -> "Favorites"
}
