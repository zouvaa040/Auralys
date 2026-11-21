/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * "Buy me a coffee": shows the QRIS code and keeps the screen bright while it is open so it scans easily.
 */

package com.metrolist.music.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.metrolist.music.R
import com.metrolist.music.utils.ComposeToImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val qr = remember { BitmapFactory.decodeResource(context.resources, R.drawable.qris) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // brighter screen while the code is on show, put back afterwards
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val old = window?.attributes?.screenBrightness
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
            onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = old ?: -1f } } }
        }
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth()
                .heightIn(max = (configuration.screenHeightDp * 0.94f).dp)
                .glassOverlay(glassShape(32.dp, 18.dp), 0.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.support_dialog_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(14.dp),
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QRIS",
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Text(stringResource(R.string.support_merchant), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("DAPSTORE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.support_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { ComposeToImage.saveBitmapAsFile(context, scaledQr(qr), "QRIS-Auralys") }
                                    Toast.makeText(context, context.getString(R.string.support_saved), Toast.LENGTH_SHORT).show()
                                } catch (e: Throwable) {
                                    Toast.makeText(context, e.message ?: "Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.support_save)) }
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) { ComposeToImage.saveBitmapAsFile(context, scaledQr(qr), "QRIS-Auralys-share") }
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            context.getString(R.string.support_share),
                                        ),
                                    )
                                } catch (e: Throwable) {
                                    Toast.makeText(context, e.message ?: "Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.support_share)) }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.support_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Scales the QR up without smoothing so the exported copy stays crisp and easy to scan. */
private fun scaledQr(src: Bitmap): Bitmap {
    val size = 1200
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.WHITE)
    val p = android.graphics.Paint().apply { isFilterBitmap = false }
    c.drawBitmap(src, null, android.graphics.Rect(0, 0, size, size), p)
    return out
}
