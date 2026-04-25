package com.silverbp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.silverbp.android.ui.SilverBpApp
import com.silverbp.android.ui.theme.SilverBpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SilverBpTheme {
                SilverBpApp()
            }
        }
    }
}
