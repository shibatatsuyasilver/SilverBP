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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                title = { Text("資料使用說明") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "關閉")
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
                "為什麼 silverbp 需要 Health Connect 權限?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))

            Text("讀取步數", fontWeight = FontWeight.Medium)
            Text(
                "用於每日步數成就徽章、連續達標天數,以及將你的活動量與血壓量測結合,提供更完整的健康趨勢觀察。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(8.dp))

            Text("讀寫運動 / 血壓", fontWeight = FontWeight.Medium)
            Text(
                "在你進行運動量測或新增血壓紀錄時,可選擇性同步到 Health Connect,讓其他健康 app 也能讀取(由你自行控制)。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(12.dp))

            Text("資料如何被使用?", fontWeight = FontWeight.Medium)
            Text(
                "• 全部運算與儲存在你的手機本機,不會上傳至我們的伺服器。\n" +
                    "• 你可以隨時在系統 Health Connect 設定中撤銷任何權限。\n" +
                    "• 撤銷後,silverbp 會停止背景同步,既有的本地紀錄不會被刪除。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.size(8.dp))

            Text(
                "本應用程式提供的內容僅供參考,並非醫療設備或醫療診斷。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
