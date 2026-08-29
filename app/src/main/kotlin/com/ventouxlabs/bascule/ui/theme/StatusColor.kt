@file:Suppress("MatchingDeclarationName")
// This file intentionally pairs the StatusPalette data class with its
// constructor function statusPalette, grouped under the concept name
// "StatusColor" rather than either individual declaration's name.

package com.ventouxlabs.bascule.ui.theme

import androidx.compose.ui.graphics.Color
import com.ventouxlabs.bascule.data.ReadingStatus

/** Container plus the content colour guaranteed legible on it. */
data class StatusPalette(val container: Color, val content: Color)

/**
 * Delivery status is the one thing on History that must not be re-tinted by
 * the wallpaper: dynamic colour is on by default, so leaving these to
 * `MaterialTheme.colorScheme` made "needs your attention" and "delivered"
 * as distinguishable as the user's background happened to allow.
 *
 * Values are chosen to clear WCAG AA (4.5:1) against their own container in
 * both themes — pinned by `StatusColorTest`, not by eye.
 */
fun statusPalette(status: ReadingStatus, darkTheme: Boolean): StatusPalette = when (status) {
    ReadingStatus.BLOCKED_AUTH, ReadingStatus.FAILED_PERMANENT ->
        if (darkTheme) BlockedStatusPaletteDark else BlockedStatusPaletteLight

    ReadingStatus.HELD_CONFIRM ->
        if (darkTheme) HeldStatusPaletteDark else HeldStatusPaletteLight

    ReadingStatus.PENDING ->
        if (darkTheme) PendingStatusPaletteDark else PendingStatusPaletteLight

    ReadingStatus.SENT, ReadingStatus.DECLINED ->
        if (darkTheme) SentStatusPaletteDark else SentStatusPaletteLight
}

private val BlockedStatusPaletteLight = StatusPalette(container = Color(0xFFFFDAD6), content = Color(0xFF6B1010))
private val BlockedStatusPaletteDark = StatusPalette(container = Color(0xFF5C1A1A), content = Color(0xFFFFD9D6))

private val HeldStatusPaletteLight = StatusPalette(container = Color(0xFFFFEBC2), content = Color(0xFF5A4304))
private val HeldStatusPaletteDark = StatusPalette(container = Color(0xFF4A3A08), content = Color(0xFFFFE8A8))

private val PendingStatusPaletteLight = StatusPalette(container = Color(0xFFE2E8F8), content = Color(0xFF2B3550))
private val PendingStatusPaletteDark = StatusPalette(container = Color(0xFF283044), content = Color(0xFFC7D3F0))

private val SentStatusPaletteLight = StatusPalette(container = Color(0xFFEBEBF1), content = Color(0xFF44444E))
private val SentStatusPaletteDark = StatusPalette(container = Color(0xFF2A2A31), content = Color(0xFFC9C9D2))
