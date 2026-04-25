package com.silverbp.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.silverbp.android.ui.nav.AppNavHost

@Composable
fun SilverBpApp() {
    Surface(modifier = Modifier.fillMaxSize()) {
        AppNavHost()
    }
}
