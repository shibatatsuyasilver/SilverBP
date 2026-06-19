package com.silverbp.android.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.silverbp.android.ui.theme.SilverBpTheme

/**
 * SilverBP top app bar — a thin, drop-in wrapper over Material 3 [TopAppBar].
 *
 * Mirrors the mockup `.topbar` (design/mockups/assets/app.css): a flat header
 * with a transparent container so it sits flush on the screen background, a bold
 * headline title, and optional leading / trailing icon slots. Existing screens
 * already using a raw [TopAppBar] can swap to this without changing call sites.
 *
 * - [title] renders in [MaterialTheme.typography.headlineMedium] (Bold/26sp,
 *   matching the mockup `--headline-m` token), coloured [onSurface] — the
 *   legible, senior-friendly heading from the mockup.
 * - Container is transparent so the bar inherits whatever surface/background it
 *   is placed on (no elevation tint, no scrim) — matching `.topbar`'s flat look.
 * - [navigationIcon] and [actions] are passed straight through; wrap tappable
 *   content in an `IconButton` so it keeps the >= 48dp tap target.
 *
 * The project opts into ExperimentalMaterial3Api globally, so no @OptIn is needed.
 *
 * Pure UI: no state, no ViewModel coupling.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                // .topbar .title = --headline-m (700/26px); headlineMedium is
                // already Bold/26sp in Type.kt, so no fontWeight override needed.
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Preview(name = "AppTopBar — dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun AppTopBarPreview() {
    SilverBpTheme {
        AppTopBar(title = "今日")
    }
}
