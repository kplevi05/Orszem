package hu.orszembejelento.app.report.data.local

import androidx.room.TypeConverter
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID

/** Explicit, boring converters - no reflection-based magic, so the stored shape is obvious from reading this file. */
class Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun submissionStateToString(value: SubmissionState): String = value.name

    @TypeConverter
    fun stringToSubmissionState(value: String): SubmissionState = SubmissionState.valueOf(value)

    @TypeConverter
    fun publicStatusToString(value: PublicReportStatus?): String? = value?.name

    @TypeConverter
    fun stringToPublicStatus(value: String?): PublicReportStatus? = value?.let(PublicReportStatus::valueOf)
}
