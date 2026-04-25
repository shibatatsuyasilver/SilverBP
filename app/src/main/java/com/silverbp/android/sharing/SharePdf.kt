package com.silverbp.android.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.silverbp.android.R
import java.io.File

fun Context.sharePdf(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.share)))
}
