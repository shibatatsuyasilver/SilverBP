package com.silverbp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.silverbp.android.recognition.ModelBootstrap
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

    override fun onDestroy() {
        // Release the native LiteRT engine + OpenCL GPU context when the user
        // truly exits (back-out / swipe-from-recents). A leaked OpenCL context
        // can wedge the GPU driver until the phone is rebooted.
        if (isFinishing) {
            ModelBootstrap.shutdown()
        }
        super.onDestroy()
    }
}
