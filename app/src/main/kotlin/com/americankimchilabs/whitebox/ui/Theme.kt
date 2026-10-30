package com.americankimchilabs.whitebox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * Type scale for the Pleos head unit (2560×1440 @ 160 dpi), taken from the official vehicle sample app's Type.kt
 * (vendor/pleos-sample-vehicle-app). The status figures use [STAT_SIZE], larger, for at-a-glance reading.
 */
private val Automotive = Typography(
    headlineLarge = Default.headlineLarge.copy(fontSize = 38.sp, lineHeight = 46.sp),
    headlineMedium = Default.headlineMedium.copy(fontSize = 34.sp, lineHeight = 42.sp),
    headlineSmall = Default.headlineSmall.copy(fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = Default.titleLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleMedium = Default.titleMedium.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleSmall = Default.titleSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = Default.bodyLarge.copy(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = Default.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodySmall = Default.bodySmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
    labelLarge = Default.labelLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
    labelMedium = Default.labelMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    labelSmall = Default.labelSmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
)

val STAT_SIZE = 40.sp

@Composable
fun WhiteBoxTheme(content: @Composable () -> Unit) = MaterialTheme(typography = Automotive, content = content)
