package com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design language: this app's entire personality is "cut the fluff."
 * The UI should feel like a stopwatch / scoreboard, not a friendly chat
 * assistant — high contrast, monospaced numerals for counters, no soft
 * bubble decoration. Silence (no interruption) reads as calm dark space;
 * an interruption is a hard, brief flash of amber, not a cute animation.
 */
object FillerFreeColors {
    val background = Color(0xFF1C1A17)      // Warm dark brownish-black
    val surface = Color(0xFF26231F)         // Warm dark brown
    val surfaceRaised = Color(0xFF332F2A)   // Lighter warm brown
    val hairline = Color(0xFF403C35)

    val textPrimary = Color(0xFFF2EBE1)      // Warm off-white
    val textSecondary = Color(0xFFB5AE9F)    // Muted warm beige
    val textMuted = Color(0xFF7A7368)        // Darker warm beige

    val signalAmber = Color(0xFFE6A23C)      // Vibrant warm amber
    val signalGreen = Color(0xFF67C23A)       // Positive green
    val signalRed = Color(0xFFF56C6C)         // Destructive red
}

object FillerFreeType {
    // Utility/counter face: tabular, mechanical feel for live-updating numbers.
    val counterNumber = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
    )

    val counterLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
    )

    val screenTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.3).sp,
    )

    val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    val interruptionLine = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp,
    )
}
