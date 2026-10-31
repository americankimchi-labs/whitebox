package com.americankimchilabs.whitebox.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The rotary focus ring: high contrast on the light theme, visible at a glance from the driver's seat. */
private val FocusRing = Color(0xFF1565C0)

/**
 * Rotary (CCP) support. The platform's rotary service moves focus between focusable elements when the knob turns and
 * clicks the focused one on push, so every control must be focusable and show where focus is. Put this before the
 * element's own focus or click modifier (or pass it as a Material component's `modifier`): it observes that element.
 */
fun Modifier.ccpFocus(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(if (focused) Modifier.border(4.dp, FocusRing, shape) else Modifier)
}

/**
 * A row the knob can land on, for lists without controls (the timeline): turning the knob steps through the rows
 * and the list scrolls to keep the focused one in view.
 */
fun Modifier.ccpRow(): Modifier = ccpFocus(RoundedCornerShape(4.dp)).focusable()
