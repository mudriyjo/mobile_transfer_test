package com.bank.mobile.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all(Char::isUpperCase)) {
            "Currency code must use three uppercase letters"
        }
    }
}

@Serializable
data class Money(
    val minorUnits: Long,
    val currency: CurrencyCode,
) : Comparable<Money> {
    init {
        require(minorUnits >= 0) { "Money cannot be negative" }
    }

    val isZero: Boolean
        get() = minorUnits == 0L

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        require(minorUnits <= Long.MAX_VALUE - other.minorUnits) { "Money addition overflow" }
        return copy(minorUnits = minorUnits + other.minorUnits)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        require(minorUnits >= other.minorUnits) { "Money subtraction cannot produce a negative amount" }
        return copy(minorUnits = minorUnits - other.minorUnits)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    /**
     * Splits an amount without losing minor units. Earlier recipients receive the remainder,
     * which makes the result deterministic for receipts and tests.
     */
    fun allocate(parts: Int): List<Money> {
        require(parts > 0) { "Allocation must contain at least one part" }
        val quotient = minorUnits / parts
        val remainder = (minorUnits % parts).toInt()
        return List(parts) { index ->
            Money(quotient + if (index < remainder) 1 else 0, currency)
        }
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Money currencies must match: ${currency.value} and ${other.currency.value}"
        }
    }

    companion object {
        fun zero(currency: CurrencyCode): Money = Money(0, currency)
    }
}

/**
 * Locale-explicit parser for user-entered decimal amounts. It never passes through floating
 * point, accepts either dot or comma as the decimal separator, and rejects grouping separators.
 */
object MinorUnitAmountParser {
    fun parse(
        input: String,
        currency: CurrencyCode,
        fractionDigits: Int = 2,
        maximumMajorDigits: Int = 12,
    ): Money? {
        require(fractionDigits in 0..6) { "fractionDigits must be between 0 and 6" }
        require(maximumMajorDigits > 0) { "maximumMajorDigits must be positive" }

        val normalized = input.trim()
        if (normalized.isEmpty() || normalized.startsWith('-') || normalized.startsWith('+')) return null
        if (normalized.any { !it.isDigit() && it != '.' && it != ',' }) return null

        val separators = normalized.count { it == '.' || it == ',' }
        if (separators > 1) return null
        val separatorIndex = normalized.indexOfFirst { it == '.' || it == ',' }
        val majorText = if (separatorIndex < 0) normalized else normalized.substring(0, separatorIndex)
        val fractionText = if (separatorIndex < 0) "" else normalized.substring(separatorIndex + 1)

        if (majorText.isEmpty() || majorText.length > maximumMajorDigits) return null
        if (majorText.any { !it.isDigit() } || fractionText.any { !it.isDigit() }) return null
        if (fractionText.length > fractionDigits) return null

        val major = majorText.toLongOrNull() ?: return null
        val factor = powerOfTen(fractionDigits) ?: return null
        if (major > Long.MAX_VALUE / factor) return null

        val paddedFraction = fractionText.padEnd(fractionDigits, '0')
        val fraction = paddedFraction.toLongOrNull() ?: 0L
        val scaledMajor = major * factor
        if (scaledMajor > Long.MAX_VALUE - fraction) return null
        return Money(scaledMajor + fraction, currency)
    }

    private fun powerOfTen(exponent: Int): Long? {
        var result = 1L
        repeat(exponent) {
            if (result > Long.MAX_VALUE / 10L) return null
            result *= 10L
        }
        return result
    }
}
