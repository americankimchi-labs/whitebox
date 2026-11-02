package com.americankimchilabs.whitebox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * Type scale for the Pleos head unit (2560×1440 @ 160 dpi). The vehicle sample app's scale (body 20 sp) reads as
 * small print at arm's length on this screen, so every size is about one and a half times it: body text at 28–30 sp,
 * titles from 32 sp, so a driver who needs reading glasses can still read every screen. The status figures use
 * [STAT_SIZE], larger, for at-a-glance reading.
 */
/**
 * Korean breaks only between words (never inside one, as Android otherwise does for Hangul), and lines are balanced so a
 * paragraph does not end with one stranded word.
 */
private val Words = LineBreak(strategy = LineBreak.Strategy.Balanced, strictness = LineBreak.Strictness.Strict, wordBreak = LineBreak.WordBreak.Phrase)
private fun TextStyle.words() = copy(lineBreak = Words)

private val Automotive = Typography(
    headlineLarge = Default.headlineLarge.copy(fontSize = 52.sp, lineHeight = 64.sp).words(),
    headlineMedium = Default.headlineMedium.copy(fontSize = 46.sp, lineHeight = 58.sp).words(),
    headlineSmall = Default.headlineSmall.copy(fontSize = 40.sp, lineHeight = 52.sp).words(),
    titleLarge = Default.titleLarge.copy(fontSize = 36.sp, lineHeight = 46.sp).words(),
    titleMedium = Default.titleMedium.copy(fontSize = 32.sp, lineHeight = 42.sp).words(),
    titleSmall = Default.titleSmall.copy(fontSize = 30.sp, lineHeight = 38.sp).words(),
    bodyLarge = Default.bodyLarge.copy(fontSize = 30.sp, lineHeight = 44.sp).words(),
    bodyMedium = Default.bodyMedium.copy(fontSize = 28.sp, lineHeight = 40.sp).words(),
    bodySmall = Default.bodySmall.copy(fontSize = 26.sp, lineHeight = 36.sp).words(),
    labelLarge = Default.labelLarge.copy(fontSize = 28.sp, lineHeight = 36.sp).words(),
    labelMedium = Default.labelMedium.copy(fontSize = 26.sp, lineHeight = 34.sp).words(),
    labelSmall = Default.labelSmall.copy(fontSize = 26.sp, lineHeight = 32.sp).words(),
)

val STAT_SIZE = 56.sp

/** The logo's purple. Buttons use it too, so the app and its launcher icon are one colour. */
val BrandPurple = Color(0xFF6750A4)

private val Colors = lightColorScheme(primary = BrandPurple)

@Composable
fun WhiteBoxTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, typography = Automotive, content = content)

/**
 * Starts each sentence of a paragraph on its own line, so a sentence never begins at the end of another's line. Only a
 * stop followed by a space ends a sentence (0.3초 stays whole).
 */
fun String.bySentence(): String = replace(Regex("(?<=[.?!])[ \\t]+(?=\\S)"), "\n")
