package dev.southstar21.droidtosh.notifications

import dev.southstar21.droidtosh.protocol.v1.NotificationRecord

/**
 * Fits notification records into one snapshot frame. The Mac drops the session on frames over
 * 1 MiB, so once the budget runs out icons are dropped first, then whole records; skipped records
 * go out later as new posts.
 */
object SnapshotBudget {
    const val BUDGET_BYTES = 900_000
    const val RECORD_OVERHEAD_BYTES = 8

    data class Result(
        val records: List<NotificationRecord>,
        val iconsDropped: Int,
        val recordsDropped: Int,
    )

    /** [onIconDropped] is told which record lost its icon, so the icon can be re-sent later. */
    fun fit(
        records: Sequence<NotificationRecord>,
        budgetBytes: Int = BUDGET_BYTES,
        onIconDropped: (NotificationRecord) -> Unit = {},
    ): Result {
        var budget = budgetBytes
        var iconsDropped = 0
        var recordsDropped = 0
        val kept = ArrayList<NotificationRecord>()
        for (candidate in records) {
            var record = candidate
            if (record.serializedSize > budget && !record.iconPng.isEmpty) {
                onIconDropped(record)
                record = record.toBuilder().clearIconPng().build()
                iconsDropped += 1
            }
            if (record.serializedSize > budget) {
                recordsDropped += 1
                continue
            }
            budget -= record.serializedSize + RECORD_OVERHEAD_BYTES
            kept += record
        }
        return Result(kept, iconsDropped, recordsDropped)
    }
}
