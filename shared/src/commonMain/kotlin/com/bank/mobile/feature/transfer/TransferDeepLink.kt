package com.bank.mobile.feature.transfer

import com.bank.mobile.core.ids.OperationId

data class TransferDeepLink(
    val operationId: OperationId,
)

object TransferDeepLinkParser {
    private const val MaxUrlLength = 512

    fun parse(rawUrl: String): TransferDeepLink? {
        if (rawUrl.isEmpty() || rawUrl.length > MaxUrlLength || '#' in rawUrl) return null

        val schemeEnd = rawUrl.indexOf("://")
        if (schemeEnd <= 0 || !rawUrl.substring(0, schemeEnd).equals("mobilebank", ignoreCase = true)) {
            return null
        }

        val authorityStart = schemeEnd + 3
        val authorityEnd = rawUrl.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it == -1) rawUrl.length else it }
        val host = rawUrl.substring(authorityStart, authorityEnd)
        if (!host.equals("transfer", ignoreCase = true)) return null

        val suffix = rawUrl.substring(authorityEnd)
        if (!suffix.startsWith('?')) return null
        val parameters = suffix.drop(1).split('&')
        if (parameters.size != 1) return null

        val separator = parameters.single().indexOf('=')
        if (separator <= 0 || parameters.single().substring(0, separator) != "operationId") return null
        val operationId = decodeAscii(parameters.single().substring(separator + 1)) ?: return null
        if (operationId.length !in 8..128 || operationId.any { !it.isSafeOperationIdCharacter() }) return null

        return TransferDeepLink(OperationId(operationId))
    }

    private fun decodeAscii(value: String): String? = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '%') {
                append(character)
                index += 1
                continue
            }

            if (index + 2 >= value.length) return null
            val high = value[index + 1].hexValue() ?: return null
            val low = value[index + 2].hexValue() ?: return null
            val decoded = high * 16 + low
            if (decoded > 0x7f) return null
            append(decoded.toChar())
            index += 3
        }
    }

    private fun Char.hexValue(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private fun Char.isSafeOperationIdCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '-' || this == '_' || this == '.' || this == ':'
}

class TransferDeepLinkHandler(
    private val viewModel: TransferViewModel,
) {
    fun open(rawUrl: String): Boolean {
        val deepLink = TransferDeepLinkParser.parse(rawUrl) ?: return false
        viewModel.dispatch(TransferAction.OpenOperation(deepLink.operationId))
        return true
    }
}
