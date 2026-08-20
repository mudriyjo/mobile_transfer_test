package com.bank.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF0B57D0)
private val BrandBlueDark = Color(0xFFAEC6FF)
private val BankLightColors: ColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A42),
    secondary = Color(0xFF515F78),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF9F9FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFE1E2EC),
)

private val BankDarkColors: ColorScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004493),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBAC6E4),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474F),
)

@Composable
fun BankTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) BankDarkColors else BankLightColors,
        typography = Typography(),
        content = content,
    )
}
