package com.silverbp.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.silverbp.android.settings.AppThemeMode

private val LightColorScheme = lightColorScheme(
    primary = ForgeLightPrimary,
    onPrimary = ForgeLightOnPrimary,
    primaryContainer = ForgeLightPrimaryContainer,
    onPrimaryContainer = ForgeLightOnPrimaryContainer,
    secondary = ForgeLightSecondary,
    onSecondary = ForgeLightOnSecondary,
    secondaryContainer = ForgeLightSecondaryContainer,
    onSecondaryContainer = ForgeLightOnSecondaryContainer,
    tertiary = ForgeLightTertiary,
    onTertiary = ForgeLightOnTertiary,
    tertiaryContainer = ForgeLightTertiaryContainer,
    onTertiaryContainer = ForgeLightOnTertiaryContainer,
    background = ForgeLightBackground,
    onBackground = ForgeLightOnBackground,
    surface = ForgeLightSurface,
    onSurface = ForgeLightOnSurface,
    surfaceVariant = ForgeLightSurfaceVariant,
    onSurfaceVariant = ForgeLightOnSurfaceVariant,
    surfaceContainerLowest = ForgeLightSurfaceContainerLowest,
    surfaceContainerLow = ForgeLightSurfaceContainerLow,
    surfaceContainer = ForgeLightSurfaceContainer,
    surfaceContainerHigh = ForgeLightSurfaceContainerHigh,
    surfaceContainerHighest = ForgeLightSurfaceContainerHighest,
    outline = ForgeLightOutline,
    outlineVariant = ForgeLightOutlineVariant,
    error = ForgeLightError,
    onError = ForgeLightOnError,
    errorContainer = ForgeLightErrorContainer,
    onErrorContainer = ForgeLightOnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = ForgePrimary,
    onPrimary = ForgeOnPrimary,
    primaryContainer = ForgePrimaryContainer,
    onPrimaryContainer = ForgeOnPrimaryContainer,
    secondary = ForgeSecondary,
    onSecondary = ForgeOnSecondary,
    secondaryContainer = ForgeSecondaryContainer,
    onSecondaryContainer = ForgeOnSecondaryContainer,
    tertiary = ForgeTertiary,
    onTertiary = ForgeOnTertiary,
    tertiaryContainer = ForgeTertiaryContainer,
    onTertiaryContainer = ForgeOnTertiaryContainer,
    background = ForgeBackground,
    onBackground = ForgeOnSurface,
    surface = ForgeSurface,
    onSurface = ForgeOnSurface,
    surfaceVariant = ForgeSurfaceVariant,
    onSurfaceVariant = ForgeOnSurfaceVariant,
    surfaceContainerLowest = ForgeSurfaceContainerLowest,
    surfaceContainerLow = ForgeSurfaceContainerLow,
    surfaceContainer = ForgeSurfaceContainer,
    surfaceContainerHigh = ForgeSurfaceContainerHigh,
    surfaceContainerHighest = ForgeSurfaceContainerHighest,
    outline = ForgeOutline,
    outlineVariant = ForgeOutlineVariant,
    error = ForgeError,
    onError = ForgeOnError,
    errorContainer = ForgeErrorContainer,
    onErrorContainer = ForgeOnErrorContainer,
)

@Composable
fun SilverBpTheme(
    themeMode: AppThemeMode = AppThemeMode.Dark,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // MainActivity calls enableEdgeToEdge(), which otherwise keys the system-bar
    // icon color off the *device* theme. Drive it from the *app* theme instead,
    // so light mode gets dark status/nav icons (and vice-versa) regardless of the
    // phone's setting — without this they go invisible when the two disagree.
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme) {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {}
        }
    }

    // The Expressive look is built on STABLE M3: our own AppShapes + emphasized
    // Typography + container/tertiary colors. MaterialExpressiveTheme / MotionScheme
    // are `internal` in the material3 version this BOM resolves, so spring motion and
    // shape-morph are applied per-component (ui/components) via AppMotion instead.
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
