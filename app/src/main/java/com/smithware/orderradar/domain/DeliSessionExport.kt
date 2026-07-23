package com.smithware.orderradar.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DeliExportDocumentType {
    EXPIRY_RADAR,
    ORDER_SHEET
}

interface DeliPdfExporter {
    fun exportPdf(result: DeliReconciliationResult, today: LocalDate, type: DeliExportDocumentType): ByteArray
}

object StubDeliPdfExporter : DeliPdfExporter {
    override fun exportPdf(result: DeliReconciliationResult, today: LocalDate, type: DeliExportDocumentType): ByteArray =
        throw UnsupportedOperationException("PDF export is reserved behind DeliPdfExporter for a later pass.")
}

object DeliSessionExporter {
    fun expiryCsv(result: DeliReconciliationResult, today: LocalDate): String =
        csv(
            listOf(
                "bucket",
                "sku",
                "item",
                "category",
                "cases",
                "pounds",
                "location",
                "use_by_date",
                "relative_date",
                "production_hint"
            ),
            result.expiryRadar.map { item ->
                listOf(
                    item.bucket.label(),
                    item.sku,
                    item.itemName,
                    item.category.name.readable(),
                    item.cases.clean(),
                    item.pounds?.clean().orEmpty(),
                    item.location.name.readable(),
                    item.useByDate?.format(dateFormatter).orEmpty(),
                    item.relativeUseBy(today),
                    item.productionHint.orEmpty()
                )
            }
        )

    fun orderSheetCsv(result: DeliReconciliationResult): String =
        csv(
            listOf(
                "order_index",
                "action",
                "sku",
                "item",
                "system_suggested_cases",
                "radar_recommended_cases",
                "delta_cases",
                "usable_on_hand_cases",
                "excluded_expiring_cases",
                "demand_multiplier",
                "confidence",
                "reason"
            ),
            result.orderSheet.sortedBy { it.orderIndex }.map { rec ->
                listOf(
                    (rec.orderIndex + 1).toString(),
                    rec.action.name,
                    rec.sku,
                    rec.itemName,
                    rec.systemSuggestedCases.clean(),
                    rec.radarRecommendedCases.clean(),
                    rec.deltaCases.clean(),
                    rec.usableOnHandCases.clean(),
                    rec.excludedExpiringCases.clean(),
                    rec.demandMultiplier.clean(),
                    "${(rec.confidence * 100).toInt()}%",
                    rec.reason
                )
            }
        )

    fun shareSummary(result: DeliReconciliationResult, today: LocalDate, nextDeliveryDate: LocalDate): String {
        val orderLines = result.orderSheet.sortedBy { it.orderIndex }.joinToString("\n") { rec ->
            "- ${rec.action.name}: ${rec.sku} ${rec.itemName} | system ${rec.systemSuggestedCases.clean()} -> radar ${rec.radarRecommendedCases.clean()} cases | ${rec.reason}"
        }
        val expiryLines = result.expiryRadar
            .filter { it.bucket in activeBuckets }
            .joinToString("\n") { item ->
                "- ${item.bucket.label()}: ${item.itemName} | ${item.cases.clean()} cases${item.pounds?.let { ", ${it.clean()} lb" } ?: ""} | ${item.location.name.readable()} | use by ${item.useByDate?.format(dateFormatter) ?: "unknown"} (${item.relativeUseBy(today)})${item.productionHint?.let { " | $it" } ?: ""}"
            }
        return """
            Deli Order Radar
            Run date: ${today.format(dateFormatter)}
            Next delivery: ${nextDeliveryDate.format(dateFormatter)}

            Order Sheet
            ${orderLines.ifBlank { "- No supplier order rows in this session." }}

            Expiry Radar
            ${expiryLines.ifBlank { "- No 0-10 day expiry items in this session." }}
        """.trimIndent()
    }
}

fun ExpiryBucket.label(): String =
    when (this) {
        ExpiryBucket.DAYS_0_TO_2 -> "0-2 days"
        ExpiryBucket.DAYS_3_TO_5 -> "3-5 days"
        ExpiryBucket.DAYS_6_TO_10 -> "6-10 days"
        ExpiryBucket.LATER -> "Later"
        ExpiryBucket.UNKNOWN -> "Unknown"
    }

fun ExpiryRadarItem.relativeUseBy(today: LocalDate): String =
    when (val days = daysUntilExpiry ?: useByDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }) {
        null -> "date unreadable"
        in Int.MIN_VALUE..-1 -> "${-days} day(s) past use-by"
        0 -> "expires today"
        1 -> "expires tomorrow"
        else -> "expires in $days day(s)"
    }

val activeBuckets: Set<ExpiryBucket> = setOf(
    ExpiryBucket.DAYS_0_TO_2,
    ExpiryBucket.DAYS_3_TO_5,
    ExpiryBucket.DAYS_6_TO_10
)

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

private fun csv(headers: List<String>, rows: List<List<String>>): String =
    (listOf(headers) + rows)
        .joinToString("\n") { row -> row.joinToString(",") { it.csvCell() } }

private fun String.csvCell(): String {
    val escaped = replace("\"", "\"\"")
    return if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
}

private fun String.readable(): String =
    lowercase(Locale.US).split("_").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase(Locale.US) } }
