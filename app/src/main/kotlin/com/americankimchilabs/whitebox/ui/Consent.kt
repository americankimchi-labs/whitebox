package com.americankimchilabs.whitebox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * The first-run setup shows it as one of its pages; [ConsentScreen] shows it on its own.
 */
@Composable
fun ConsentPrompt(onAnswer: (Boolean) -> Unit) {
    Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.consent_why).bySentence(), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(32.dp))
    Item(R.string.consent_send_title, R.string.consent_send)
    Item(R.string.consent_never_title, R.string.consent_never)
    Item(R.string.consent_protect_title, R.string.consent_protect)
    Item(R.string.consent_undo_title, R.string.consent_undo)
    Text(stringResource(R.string.consent_safety_note).bySentence(), style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
    Spacer(Modifier.height(40.dp))
    Buttons(R.string.consent_yes, { onAnswer(true) }, R.string.consent_no, { onAnswer(false) })
}

/** The data-sharing question on its own (a car set up before the question existed). */
@Composable
fun ConsentScreen(onAnswer: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 1500.dp)) { ConsentPrompt(onAnswer) }
    }
}

@Composable
private fun Item(@StringRes title: Int, @StringRes body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(stringResource(body).bySentence(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
}
