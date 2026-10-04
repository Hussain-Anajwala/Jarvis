package com.jarvis.core

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

sealed interface CalendarWriteResult {
    data class Written(val eventId: Long, val calendarName: String) : CalendarWriteResult
    data object PermissionUnavailable : CalendarWriteResult
    data object NoWritableCalendar : CalendarWriteResult
    data object ProviderUnavailable : CalendarWriteResult
}

class CalendarProviderGateway(private val context: Context) {
    private sealed interface CalendarLookup {
        data class Found(val calendar: WritableCalendar) : CalendarLookup
        data object NoWritableCalendar : CalendarLookup
        data object ProviderUnavailable : CalendarLookup
    }

    suspend fun insertEvent(title: String, startTime: Long, endTime: Long, location: String?): CalendarWriteResult =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermissions()) return@withContext CalendarWriteResult.PermissionUnavailable
            try {
                val calendar = when (val lookup = findWritableCalendar()) {
                    is CalendarLookup.Found -> lookup.calendar
                    CalendarLookup.NoWritableCalendar -> return@withContext CalendarWriteResult.NoWritableCalendar
                    CalendarLookup.ProviderUnavailable -> return@withContext CalendarWriteResult.ProviderUnavailable
                }
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, startTime)
                    put(CalendarContract.Events.DTEND, endTime)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.EVENT_LOCATION, location)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    ?: return@withContext CalendarWriteResult.ProviderUnavailable
                CalendarWriteResult.Written(
                    ContentUris.parseId(uri),
                    calendar.displayName
                )
            } catch (_: SecurityException) {
                CalendarWriteResult.PermissionUnavailable
            } catch (_: IllegalArgumentException) {
                CalendarWriteResult.ProviderUnavailable
            }
        }

    suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (eventId <= 0L || !hasCalendarPermissions()) return@withContext false
        try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null
            ) > 0
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun findWritableCalendar(): CalendarLookup {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(WRITE_ACCESS_LEVEL.toString()),
            null
        ) ?: return CalendarLookup.ProviderUnavailable

        val calendars = cursor.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accessIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val primaryIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            buildList {
                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val displayName = it.getString(nameIndex).orEmpty()
                    if (it.getInt(accessIndex) >= WRITE_ACCESS_LEVEL) {
                        add(WritableCalendar(id, displayName, it.getInt(primaryIndex) == 1))
                    }
                }
            }
        }
        val selected = calendars
            .filter { EMAIL_CALENDAR_NAME.matches(it.displayName) }
            .sortedByDescending { it.isPrimary }
            .firstOrNull()
            ?: calendars.sortedByDescending { it.isPrimary }.firstOrNull()
        return selected?.let(CalendarLookup::Found) ?: CalendarLookup.NoWritableCalendar
    }

    private companion object {
        const val WRITE_ACCESS_LEVEL = 700
        val EMAIL_CALENDAR_NAME = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }

    private data class WritableCalendar(
        val id: Long,
        val displayName: String,
        val isPrimary: Boolean
    )
}
