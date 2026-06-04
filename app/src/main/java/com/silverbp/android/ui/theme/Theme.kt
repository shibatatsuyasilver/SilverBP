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
    background = ForgeLightBackground,
    onBackground = ForgeLightOnBackground,
    surface = ForgeLightSurface,
    onSurface = ForgeLightOnSurface,
    surfaceVariant = ForgeLightSurfaceVariant,
    onSurfaceVariant = ForgeLightOnSurfaceVariant,
    surfaceContainer = ForgeLightSurfaceContainer,
    surfaceContainerHigh = ForgeLightSurfaceContainerHigh,
    outline = ForgeLightOutline,
    outlineVariant = ForgeLightOutlineVariant,
    error = ForgeLightError,
    onError = ForgeLightOnError,
)

private val DarkColorScheme = darkColorScheme(
    primary = ForgePrimary,
    onPrimary = ForgeOnPrimary,
    secondary = ForgeSecondary,
    onSecondary = ForgeOnSecondary,
    background = ForgeBackground,
    surface = ForgeSurface,
    surfaceVariant = ForgeSurfaceVariant,
    onSurface = ForgeOnSurface,
    onSurfaceVariant = ForgeOnSurfaceVariant,
    outline = ForgeOutline,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
