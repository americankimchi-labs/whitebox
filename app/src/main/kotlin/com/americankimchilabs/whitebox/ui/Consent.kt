package com.americankimchilabs.whitebox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.americankimchilabs.whitebox.R

/**
 * Asked once, parked: may pseudonymous crash signal windows help improve detection? Why first, then exactly what is
 * and is not sent, how it is protected, and that it can be undone. Nothing is sent unless the driver agrees.
 */
@Composable
fun ConsentPrompt(onAnswer: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 1000.dp)) {
            Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.consent_why), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Item(R.string.consent_send_title, R.string.consent_send)
            Item(R.string.consent_never_title, R.string.consent_never)
            Item(R.string.consent_protect_title, R.string.consent_protect)
            Item(R.string.consent_undo_title, R.string.consent_undo)
            Text(stringResource(R.string.consent_safety_note), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onAnswer(true) }, modifier = Modifier.ccpFocus(CircleShape)) {
                    Text(stringResource(R.string.consent_yes), style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = { onAnswer(false) }, modifier = Modifier.ccpFocus(CircleShape)) {
                    Text(stringResource(R.string.consent_no), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Item(@StringRes title: Int, @StringRes body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
}
