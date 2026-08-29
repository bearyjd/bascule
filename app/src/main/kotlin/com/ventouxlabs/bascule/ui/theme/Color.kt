package com.ventouxlabs.bascule.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Bascule's brand identity: a violet primary matching the launcher icon, with
 * a warm clay secondary for data emphasis and a soft blue tertiary for
 * informational surfaces. Full Material3
 * [androidx.compose.material3.ColorScheme] role sets for both light and dark,
 * hand-tuned rather than generator-derived — there is no design tooling in
 * this pipeline, so tonal steps are chosen directly against WCAG-adjacent
 * contrast rather than computed from a single seed. This palette is the
 * fallback for pre-API-31 devices or `useDynamicColor = false`; the primary
 * device target (SDK 31+) uses Material You dynamic colour instead.
 */

// Primary — violet, matching the launcher icon.
val VioletPrimaryLight = Color(0xFF553CBE)
val OnVioletPrimaryLight = Color(0xFFFFFFFF)
val VioletPrimaryContainerLight = Color(0xFFE6DEFF)
val OnVioletPrimaryContainerLight = Color(0xFF1B0A63)

val VioletPrimaryDark = Color(0xFFC9B4F8)
val OnVioletPrimaryDark = Color(0xFF003738)
val VioletPrimaryContainerDark = Color(0xFF3E2A96)
val OnVioletPrimaryContainerDark = Color(0xFFE6DEFF)

// Secondary — warm clay, used for data emphasis (weight/body-comp values).
val ClaySecondaryLight = Color(0xFF7B5847)
val OnClaySecondaryLight = Color(0xFFFFFFFF)
val ClaySecondaryContainerLight = Color(0xFFFFDBC9)
val OnClaySecondaryContainerLight = Color(0xFF2E1608)

val ClaySecondaryDark = Color(0xFFEBBFA6)
val OnClaySecondaryDark = Color(0xFF472A1B)
val ClaySecondaryContainerDark = Color(0xFF5F4030)
val OnClaySecondaryContainerDark = Color(0xFFFFDBC9)

// Tertiary — soft blue, informational (timestamps, diagnostics, banners).
val BlueTertiaryLight = Color(0xFF3C608F)
val OnBlueTertiaryLight = Color(0xFFFFFFFF)
val BlueTertiaryContainerLight = Color(0xFFD5E3FF)
val OnBlueTertiaryContainerLight = Color(0xFF001B3B)

val BlueTertiaryDark = Color(0xFFA9C7FF)
val OnBlueTertiaryDark = Color(0xFF0A305E)
val BlueTertiaryContainerDark = Color(0xFF224777)
val OnBlueTertiaryContainerDark = Color(0xFFD5E3FF)

// Error — Material's standard red family, used for BLOCKED_AUTH/FAILED_PERMANENT.
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

// Neutral surfaces.
val BackgroundLight = Color(0xFFF9FAFA)
val OnBackgroundLight = Color(0xFF191C1C)
val SurfaceLight = Color(0xFFF9FAFA)
val OnSurfaceLight = Color(0xFF191C1C)
val SurfaceVariantLight = Color(0xFFDAE5E4)
val OnSurfaceVariantLight = Color(0xFF3F4948)
val OutlineLight = Color(0xFF6F7979)
val OutlineVariantLight = Color(0xFFBEC9C8)
val ScrimLight = Color(0xFF000000)
val InverseSurfaceLight = Color(0xFF2D3131)
val InverseOnSurfaceLight = Color(0xFFEFF1F0)
val InversePrimaryLight = VioletPrimaryDark
val SurfaceTintLight = VioletPrimaryLight

val BackgroundDark = Color(0xFF111414)
val OnBackgroundDark = Color(0xFFE0E3E2)
val SurfaceDark = Color(0xFF111414)
val OnSurfaceDark = Color(0xFFE0E3E2)
val SurfaceVariantDark = Color(0xFF3F4948)
val OnSurfaceVariantDark = Color(0xFFBEC9C8)
val OutlineDark = Color(0xFF899392)
val OutlineVariantDark = Color(0xFF3F4948)
val ScrimDark = Color(0xFF000000)
val InverseSurfaceDark = Color(0xFFE0E3E2)
val InverseOnSurfaceDark = Color(0xFF191C1C)
val InversePrimaryDark = VioletPrimaryLight
val SurfaceTintDark = VioletPrimaryDark
