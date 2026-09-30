package com.couchmode.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.couchmode.app.wizard.Spot
import com.couchmode.app.wizard.WIZARD_STEPS
import com.couchmode.app.wizard.WizardState

private val DoneGreen = Color(0xFF2E9E44)

/**
 * Button setup, as in docs/concept/app-screens.png screen 3, but with positions instead of letters:
 * a controller diagram highlights where to press, because "A" sits in different places on different pads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    title: String,
    state: WizardState,
    hasSavedMap: Boolean,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onClearSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    if (!state.finished) {
                        Text(
                            "${state.index + 1} / ${WIZARD_STEPS.size}",
                            modifier = Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.error != null -> {
                    Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                    Text(state.error, textAlign = TextAlign.Center)
                    Button(onClick = onClose) { Text("Close") }
                }
                state.finished -> FinishedContent(state, onClose)
                else -> StepContent(state, hasSavedMap, onSkip, onRetry, onBack, onClearSaved)
            }
        }
    }
}

@Composable
private fun StepContent(
    state: WizardState,
    hasSavedMap: Boolean,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onClearSaved: () -> Unit,
) {
    val step = state.step
    ControllerDiagram(
        spot = step.spot,
        modifier = Modifier
            .widthIn(max = 250.dp)
            .fillMaxWidth(),
    )
    Text(step.prompt, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        step.hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Text(
        when {
            state.justCaptured -> "Got it"
            state.notice != null -> state.notice
            else -> "Waiting for input... skipping in ${state.secondsLeft}s"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (state.justCaptured) DoneGreen else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    ProgressDots(state)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.index > 0) OutlinedButton(onClick = onBack) { Text("Back") }
        OutlinedButton(onClick = onSkip, enabled = !state.justCaptured) { Text("Skip button") }
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
    if (hasSavedMap) TextButton(onClick = onClearSaved) { Text("Forget saved buttons") }
}

@Composable
private fun FinishedContent(state: WizardState, onClose: () -> Unit) {
    val count = state.captured.size
    Text(
        when (state.saved) {
            true -> "All set"
            false -> "Nothing was changed"
            null -> "Saving..."
        },
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        when (state.saved) {
            true -> "Saved $count of ${WIZARD_STEPS.size} buttons. Games will now see this controller's buttons in the right places."
            false -> "No buttons were pressed, so the saved setup (if any) is unchanged."
            null -> ""
        },
        textAlign = TextAlign.Center,
    )
    ProgressDots(state)
    Button(onClick = onClose, enabled = state.saved != null) { Text("Done") }
}

@Composable
private fun ProgressDots(state: WizardState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WIZARD_STEPS.forEachIndexed { i, step ->
            val color = when {
                step.canonical in state.captured -> DoneGreen
                !state.finished && i == state.index -> MaterialTheme.colorScheme.primary
                i < state.index || state.finished -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier
                    .size(if (!state.finished && i == state.index) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** A generic controller outline with the button at [spot] highlighted. */
@Composable
fun ControllerDiagram(spot: Spot?, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.aspectRatio(1.7f)) {
        val w = size.width
        val h = size.height
        fun at(x: Float, y: Float) = Offset(x * w, y * h)
        val thin = Stroke(width = 1.5.dp.toPx())
        val thick = Stroke(width = 2.5.dp.toPx())

        // body
        drawRoundRect(
            color = body,
            topLeft = at(0.06f, 0.20f),
            size = Size(0.88f * w, 0.68f * h),
            cornerRadius = CornerRadius(0.22f * h),
        )
        drawRoundRect(
            color = outline,
            topLeft = at(0.06f, 0.20f),
            size = Size(0.88f * w, 0.68f * h),
            cornerRadius = CornerRadius(0.22f * h),
            style = thick,
        )

        fun button(x: Float, y: Float, r: Float, hit: Boolean) {
            val c = at(x, y)
            if (hit) drawCircle(accent, r * h, c) else drawCircle(outline, r * h, c, style = thin)
        }

        fun bar(x0: Float, x1: Float, y0: Float, y1: Float, hit: Boolean) {
            val tl = at(x0, y0)
            val sz = Size((x1 - x0) * w, (y1 - y0) * h)
            val corner = CornerRadius(0.03f * h)
            if (hit) drawRoundRect(accent, tl, sz, corner) else drawRoundRect(outline, tl, sz, corner, style = thin)
        }

        // triggers (above) and bumpers
        bar(0.14f, 0.30f, 0.02f, 0.10f, spot == Spot.LEFT_TRIGGER)
        bar(0.70f, 0.86f, 0.02f, 0.10f, spot == Spot.RIGHT_TRIGGER)
        bar(0.12f, 0.34f, 0.12f, 0.18f, spot == Spot.LEFT_BUMPER)
        bar(0.66f, 0.88f, 0.12f, 0.18f, spot == Spot.RIGHT_BUMPER)

        // sticks: a ring, filled when we are asking for the click
        fun stick(x: Float, y: Float, hit: Boolean) {
            val c = at(x, y)
            drawCircle(outline, 0.11f * h, c, style = thin)
            if (hit) drawCircle(accent, 0.075f * h, c) else drawCircle(outline, 0.075f * h, c, style = thin)
        }
        stick(0.26f, 0.46f, spot == Spot.LEFT_STICK)
        stick(0.62f, 0.72f, spot == Spot.RIGHT_STICK)

        // d-pad (not asked for; just part of the picture)
        val dc = at(0.38f, 0.72f)
        val arm = 0.05f * h
        drawRoundRect(outline, Offset(dc.x - arm * 2.4f, dc.y - arm * 0.8f), Size(arm * 4.8f, arm * 1.6f), CornerRadius(arm * 0.4f), style = thin)
        drawRoundRect(outline, Offset(dc.x - arm * 0.8f, dc.y - arm * 2.4f), Size(arm * 1.6f, arm * 4.8f), CornerRadius(arm * 0.4f), style = thin)

        // face buttons in a diamond
        button(0.74f, 0.56f, 0.05f, spot == Spot.SOUTH)
        button(0.83f, 0.46f, 0.05f, spot == Spot.EAST)
        button(0.65f, 0.46f, 0.05f, spot == Spot.WEST)
        button(0.74f, 0.36f, 0.05f, spot == Spot.NORTH)

        // center buttons
        button(0.44f, 0.42f, 0.03f, spot == Spot.SELECT)
        button(0.56f, 0.42f, 0.03f, spot == Spot.START)
        button(0.50f, 0.32f, 0.045f, spot == Spot.HOME)
    }
}
