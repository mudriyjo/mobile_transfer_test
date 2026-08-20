package com.bank.mobile.feature.accounts

import com.bank.mobile.core.model.CurrencyCode
import com.bank.mobile.core.model.Money
import kotlinx.serialization.Serializable

data class Account(
    val id: String,
    val displayName: String,
    val maskedNumber: String,
    val balance: Money,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class AccountDto(
    val id: String,
    val displayName: String,
    val balanceMinorUnits: Long,
    val currency: String,
    val updatedAt: String,
)

internal fun AccountDto.toDomain(nowMillis: Long): Account = Account(
    id = id,
    displayName = displayName,
    maskedNumber = "•••• ${id.stableMaskedSuffix()}",
    balance = Money(balanceMinorUnits, CurrencyCode(currency)),
    updatedAtEpochMillis = nowMillis,
)

private fun String.stableMaskedSuffix(): String {
    val digits = filter(Char::isDigit)
    if (digits.length >= 4) return digits.takeLast(4)

    val hash = fold(0) { current, character ->
        (current * 31 + character.code) and Int.MAX_VALUE
    }
    return (hash % 10_000).toString().padStart(4, '0')
}
