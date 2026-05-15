package com.pumpfinder.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pumpfinder.app.data.PumpCandidate
import com.pumpfinder.app.ui.theme.Bg
import com.pumpfinder.app.ui.theme.GoldHot
import com.pumpfinder.app.ui.theme.GreenOk
import com.pumpfinder.app.ui.theme.Line
import com.pumpfinder.app.ui.theme.LinkBlue
import com.pumpfinder.app.ui.theme.OrangeHot
import com.pumpfinder.app.ui.theme.Panel
import com.pumpfinder.app.ui.theme.Panel2
import com.pumpfinder.app.ui.theme.RedBad
import com.pumpfinder.app.ui.theme.TextDim
import com.pumpfinder.app.ui.theme.TextMain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(viewModel: ScanViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg, titleContentColor = TextMain,
                    actionIconContentColor = TextMain,
                ),
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pumps", style = MaterialTheme.typography.headlineLarge,
                                 color = TextMain)
                            Spacer(Modifier.size(12.dp))
                            SourcePill(state.source)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(asOfLabel(state),
                             style = MaterialTheme.typography.labelMedium, color = TextDim)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = TextMain, strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).background(Bg)) {
            when {
                state.loading && state.candidates.isEmpty() ->
                    LoadingState()
                state.error != null && state.candidates.isEmpty() ->
                    ErrorState(state.error!!)
                state.candidates.isEmpty() ->
                    EmptyState()
                else ->
                    CandidateList(
                        candidates = state.candidates,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        onOpenYahoo = { open(ctx, it.yahooUrl) },
                        onOpenStocktwits = { open(ctx, it.stocktwitsUrl) },
                        onOpenFb = { open(ctx, it.fbSearchUrl) },
                    )
            }
        }
    }
}

@Composable
private fun SourcePill(source: String) {
    val (label, color) = when (source) {
        "live"    -> "LIVE"    to GreenOk
        "mock"    -> "MOCK"    to GoldHot
        "offline" -> "OFFLINE" to RedBad
        else      -> "—"       to TextDim
    }
    Surface(
        color = Panel2,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label, color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

private fun asOfLabel(state: ScanUiState): String {
    if (state.asOfMs == 0L) return "—"
    val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(state.asOfMs))
    return "as of $time · ${state.candidates.size} hit${if (state.candidates.size == 1) "" else "s"}"
}

@Composable
private fun LoadingState() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = OrangeHot)
}

@Composable
private fun ErrorState(message: String) = Box(
    Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
) {
    Text("Couldn't load: $message", color = RedBad,
         style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun EmptyState() = Box(
    Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center
) {
    Text(
        "No candidates match the filters right now. Markets may be closed, or nothing fits the pre-runup pattern.",
        color = TextDim, style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun CandidateList(
    candidates: List<PumpCandidate>,
    contentPadding: PaddingValues,
    onOpenYahoo: (PumpCandidate) -> Unit,
    onOpenStocktwits: (PumpCandidate) -> Unit,
    onOpenFb: (PumpCandidate) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(candidates, key = { it.ticker }) { c ->
            CandidateCard(c, onOpenYahoo, onOpenStocktwits, onOpenFb)
        }
    }
}

@Composable
private fun CandidateCard(
    c: PumpCandidate,
    onOpenYahoo: (PumpCandidate) -> Unit,
    onOpenStocktwits: (PumpCandidate) -> Unit,
    onOpenFb: (PumpCandidate) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        onClick = { onOpenYahoo(c) },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(c.score)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(c.ticker, style = MaterialTheme.typography.titleLarge, color = TextMain)
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = TextDim,
                         maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth()) {
                Stat("PRICE",    "$${"%.2f".format(c.price)}", TextMain, Modifier.weight(1f))
                Stat("TODAY",    "${if (c.changePct >= 0) "+" else ""}${"%.2f".format(c.changePct)}%",
                     if (c.changePct >= 0) GreenOk else RedBad, Modifier.weight(1f))
                Stat("REL VOL",  "${"%.1f".format(c.relVol)}×", TextMain, Modifier.weight(1f))
                Stat("FLOAT",    formatBig(c.floatShares), TextMain, Modifier.weight(1f))
            }

            if (c.reasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.reasons.take(2).forEach { ReasonChip(it) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkText("Yahoo",      onClick = { onOpenYahoo(c) })
                LinkText("Stocktwits", onClick = { onOpenStocktwits(c) })
                LinkText("FB search",  onClick = { onOpenFb(c) })
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    val (bg, fg) = when {
        score >= 85 -> OrangeHot.copy(alpha = 0.18f) to OrangeHot
        score >= 70 -> GoldHot.copy(alpha = 0.18f)   to GoldHot
        else        -> Panel2                        to TextDim
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, fg.copy(alpha = 0.5f))) {
        Box(Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            Text("$score", color = fg, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Stat(
    label: String, value: String, valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextDim)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

@Composable
private fun ReasonChip(text: String) {
    Surface(
        color = GoldHot.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldHot.copy(alpha = 0.25f)),
    ) {
        Text(
            text, color = GoldHot, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Surface(color = Bg, onClick = onClick, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text, color = LinkBlue,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

private fun formatBig(n: Long?): String {
    if (n == null) return "—"
    return when {
        n >= 1_000_000_000 -> "%.2fB".format(n / 1_000_000_000.0)
        n >= 1_000_000     -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000         -> "%.1fK".format(n / 1_000.0)
        else               -> n.toString()
    }
}

private fun open(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
