package com.americankimchilabs.whitebox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color
import com.americankimchilabs.whitebox.R
import com.americankimchilabs.whitebox.data.ClaimClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Connecting the owner's phone (parked only): a QR with the claim address. The address's fragment carries the car's
 * secret; the phone page uses it to prove to the car that it saw this screen.
 */
@Composable
fun PhoneTab(claim: ClaimClient?, webBase: String?, consent: Boolean, onConsent: (Boolean) -> Unit) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConsentRow(consent, onConsent)
        if (claim == null || webBase == null) {
            Text(stringResource(R.string.phone_no_mirror), style = MaterialTheme.typography.titleLarge)
            return@Column
        }
        val state by claim.state.collectAsState()
        when (val s = state) {
            is ClaimClient.State.Claimed -> {
                Text(stringResource(R.string.phone_claimed, s.maskedPhone), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.phone_claimed_hint), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { claim.request() }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.phone_connect_other)) }
            }
            is ClaimClient.State.TransferPending -> {
                Text(stringResource(R.string.phone_transfer, s.maskedPhone), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.phone_transfer_hint), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { claim.request() }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.phone_connect_other)) }
            }
            is ClaimClient.State.Waiting -> {
                val url = "$webBase/claim#${s.code}.${s.secret}"
                val qr = remember(url) { qr(url, 480) }
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Image(qr, contentDescription = stringResource(R.string.phone_qr), modifier = Modifier.size(320.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.phone_scan), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.phone_scan_hint), style = MaterialTheme.typography.bodyLarge)
                        Text(url, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            ClaimClient.State.NotConnected -> {
                Text(stringResource(R.string.phone_intro), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.phone_intro_hint), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { claim.request() }, modifier = Modifier.ccpFocus(CircleShape)) { Text(stringResource(R.string.phone_connect)) }
            }
        }
    }
}

/** The driver's data choice, changeable here at any time. */
@Composable
private fun ConsentRow(consent: Boolean, onConsent: (Boolean) -> Unit) {
    var stopped by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (stopped) R.string.consent_stopped else if (consent) R.string.consent_row_on else R.string.consent_row_off),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = { stopped = consent; onConsent(!consent) }, modifier = Modifier.ccpFocus(CircleShape)) {
            Text(stringResource(if (consent) R.string.consent_stop else R.string.consent_start))
        }
    }
    HorizontalDivider()
}

private fun qr(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
