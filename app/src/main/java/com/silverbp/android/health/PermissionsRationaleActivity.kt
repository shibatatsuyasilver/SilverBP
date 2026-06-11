package com.silverbp.android.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.ui.theme.SilverBpTheme

/**
 * Health Connect requires apps requesting permissions to declare an Activity
 * that handles `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (modern,
 * Android 14+) and `android.intent.action.VIEW_PERMISSION_USAGE` with category
 * `android.intent.category.HEALTH_PERMISSIONS` (legacy, Android 13). The HC
 * permission sheet links to this screen so the user can read why an app needs
 * their data before granting.
 *
 * Without it, `PermissionsActivity` aborts with "App should support rationale
 * intent, finishing!" and no permission UI is shown.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SilverBpTheme { RationaleScreen(onClose = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hc_rationale_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.hc_rationale_close),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.hc_rationale_heading),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))

            Text(stringResource(R.string.hc_rationale_read_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.hc_rationale_read_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(8.dp))

            Text(stringResource(R.string.hc_rationale_write_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.hc_rationale_write_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(12.dp))

            Text(stringResource(R.string.hc_rationale_usage_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.hc_rationale_usage_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(8.dp))

            Text(
                stringResource(R.string.hc_rationale_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
