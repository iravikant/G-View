package com.acoder.gallery.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val kb = this / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024)
}

fun Long.formatDuration(): String {
    val total = this / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun Long.dateLabel(): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}

fun Long.dateTimeLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault()))

fun Long.timeLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

/** Whole days left until [this] (an epoch-millis expiry), never below 0. */
fun Long.daysUntil(now: Long = System.currentTimeMillis()): Int =
    (((this - now) + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
