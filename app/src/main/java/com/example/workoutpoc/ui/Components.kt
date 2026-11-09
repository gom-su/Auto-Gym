package com.example.workoutpoc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular

/** Readable line length on tablets and landscape; phones use the full width. */
val MaxContentWidth: Dp = 720.dp

/**
 * Set by a bottom-navigation tab that shows an existing screen as its root: the screen then takes the tab's
 * [title], shows no Up arrow (a tab root has nowhere to go up to), adds the tab's [actions] (the Ajustes gear) and
 * lists the tab's [header] (for example its section chips) as its first item.
 */
class TabRoot(val title: String, val actions: @Composable RowScope.() -> Unit = {}, val header: (@Composable () -> Unit)? = null)

val LocalTabRoot = staticCompositionLocalOf<TabRoot?> { null }

/** Where leaving the current screen goes ("Volver a Agente"), set by the navigation; overrides a screen's own label. */
val LocalBackLabel = staticCompositionLocalOf<String?> { null }

/**
 * Standard secondary screen: a top app bar with an Up arrow and the screen title, a thin progress track under it
 * while [busy], and a lazy list of content with consistent gutters. The arrow is announced as [backLabel], which
 * names where it goes ("Volver a Programas"), so the destination is explicit for screen-reader users too.
 * Inside a [TabRoot] the screen is a tab's root instead (see there). [drillUp] means the arrow currently climbs
 * inside the screen (a detail back to its list) rather than leaving it, so it is shown even on a tab root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    title: String, backLabel: String, onBack: () -> Unit, backEnabled: Boolean = true, busy: Boolean = false,
    message: String? = null,
    actions: @Composable RowScope.() -> Unit = {}, listState: LazyListState = rememberLazyListState(),
    drillUp: Boolean = false,
    content: LazyListScope.() -> Unit
) {
    val root = LocalTabRoot.current
    val upLabel = if (drillUp) backLabel else LocalBackLabel.current ?: backLabel
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(root?.title ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                    navigationIcon = {
                        if (root == null || drillUp) IconButton(onClick = onBack, enabled = backEnabled) { Icon(AppIcons.Back, contentDescription = upLabel) }
                    },
                    actions = { actions(); root?.actions?.invoke(this) }, scrollBehavior = scroll,
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
                BusyTrack(busy)
                StatusArea(message)
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = MaxContentWidth).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // A detail (one exercise, one session) shows only itself; its Up arrow leads back to the list and header.
                if (!drillUp) root?.header?.let { header -> item(key = "tab-header") { header() } }
                content()
            }
        }
    }
}

/**
 * Scroll state for the view identified by [key]: each detail opens at its top, and going back to a list (key null)
 * restores where that list was. Survives rotation.
 */
@Composable
fun rememberViewListState(key: Any?): LazyListState {
    val list = rememberLazyListState()
    val detail = androidx.compose.runtime.saveable.rememberSaveable(key, saver = LazyListState.Saver) { LazyListState() }
    return if (key == null) list else detail
}

/**
 * The screen's latest status message, pinned under the top bar: it is seen whatever the list's scroll position and
 * never moves the content. The user can hide it; a new message shows again.
 */
@Composable
fun StatusArea(message: String?) {
    var hidden by remember(message) { mutableStateOf(false) }
    // Shown without an enter animation: content that slides while the user is about to tap makes the tap miss.
    if (message != null && !hidden) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Banner(message, Modifier.widthIn(max = MaxContentWidth).padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
            onDismiss = { hidden = true })
    }
}

/** A fixed-height slot, so showing progress never shifts the content below it. */
@Composable
fun BusyTrack(busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxSize(), trackColor = Color.Transparent)
    }
}

/** Section title inside a screen, announced as a heading. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        supporting?.let { SupportingText(it) }
    }
}

/** Small uppercase label above a group: "PLANIFICACIÓN CON IA". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, modifier.semantics { heading() }, color = color,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp))
}

/** Secondary explanatory copy: muted so it never competes with data. */
@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
}

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val x = AppTheme.extended
    return when (tone) {
        Tone.Info -> c.secondaryContainer to c.onSecondaryContainer
        Tone.Success -> x.successContainer to x.onSuccessContainer
        Tone.Warning -> x.warningContainer to x.onWarningContainer
        Tone.Error -> c.errorContainer to c.onErrorContainer
    }
}

private fun Tone.icon() = when (this) {
    Tone.Info -> AppIcons.Info; Tone.Success -> AppIcons.CheckCircle; Tone.Warning -> AppIcons.Warning; Tone.Error -> AppIcons.Error
}

/** Inline status message, announced politely by screen readers when it appears or changes. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, tone: Tone = toneOf(text), title: String? = null,
           action: (@Composable () -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val (container, content) = toneColors(tone)
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = MaterialTheme.shapes.medium,
        color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(tone.icon(), contentDescription = null, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                action?.let { Box(Modifier.offset(x = (-12).dp)) { it() } }
            }
            onDismiss?.let {
                IconButton(onClick = it, modifier = Modifier.size(32.dp).offset(x = 8.dp, y = (-4).dp)) {
                    Icon(AppIcons.Close, contentDescription = "Ocultar mensaje", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Calm placeholder for an empty list, so "nothing yet" does not look like an error. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = AppIcons.Info) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Data card: tonal surface, consistent padding and rhythm. */
@Composable
fun ContentCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor: Color = MaterialTheme.colorScheme.onSurface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = color, contentColor = contentColor) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/**
 * Tappable list row that opens something: optional leading icon, title, supporting line and a chevron.
 * The title stays the first text of the row so it reads (and is found) exactly as before.
 */
@Composable
fun NavRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null,
           icon: ImageVector? = null, enabled: Boolean = true, badge: (@Composable () -> Unit)? = null) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            icon?.let { IconBadge(it) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            badge?.invoke()
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Icon on a soft circular container, used as a list-row leading element. */
@Composable
fun IconBadge(icon: ImageVector, container: Color = MaterialTheme.colorScheme.secondaryContainer,
              content: Color = MaterialTheme.colorScheme.onSecondaryContainer, size: Dp = 40.dp) {
    Surface(Modifier.size(size), shape = CircleShape, color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f)) }
    }
}

/** Compact status pill ("Aprobada", "activa", "descarga"). */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Info) {
    val (container, content) = toneColors(tone)
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** A number with its label, for at-a-glance summaries. */
@Composable
fun MetricTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Up to four metrics in one row, equal widths. */
@Composable
fun MetricRow(metrics: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        metrics.forEach { (value, label) -> MetricTile(value, label, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/**
 * Selectable chip whose selected state is shown by a check mark as well as by colour, so it does not rely on colour
 * alone. Drop-in for FilterChip(selected, onClick, label).
 */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(selected, onClick, label, modifier, enabled,
        leadingIcon = if (selected) ({ Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null)
}

/**
 * Labelled single choice among a few short options, on one line (Material segmented buttons). Prefer it to chips
 * whenever exactly one option applies and the options fit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(label: String?, options: List<T>, selected: T, optionLabel: (T) -> String, onSelect: (T) -> Unit,
                        enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(selected == option, { onSelect(option) }, SegmentedButtonDefaults.itemShape(i, options.size), enabled = enabled) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}

/** Labelled group of chips that wraps onto several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(label: String?, modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

@Composable
private fun ActionContent(text: String, icon: ImageVector?) {
    icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.sm)) }
    Text(text, textAlign = TextAlign.Center)
}

/** The screen's main call to action: filled, full width, comfortable height. */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                  icon: ImageVector? = null, tall: Boolean = false) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 48.dp)) {
        ActionContent(text, icon)
    }
}

/** A secondary action of similar weight to the primary one (alternatives, cancel). */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    icon: ImageVector? = null, tall: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 48.dp)) {
        ActionContent(text, icon)
    }
}

/** A supporting action that should be visible but not loud. */
@Composable
fun TonalAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                icon: ImageVector? = null, fill: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = (if (fill) modifier.fillMaxWidth() else modifier).heightIn(min = 48.dp)) {
        ActionContent(text, icon)
    }
}

/** Low-emphasis or destructive action at the end of a flow. */
@Composable
fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                icon: ImageVector? = null, destructive: Boolean = false, fill: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, modifier = (if (fill) modifier.fillMaxWidth() else modifier).heightIn(min = 48.dp),
        colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) {
        ActionContent(text, icon)
    }
}

/** In-screen "up one level" link (from a day back to its week, etc.). */
@Composable
fun BackLink(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(start = 4.dp, end = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).offset(x = (-4).dp)) {
        Icon(AppIcons.Back, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text)
    }
}

/** Path back up a drill-down: "← Ver todos los programas › Semanas › Días". Wraps on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Breadcrumbs(levels: List<Pair<String, () -> Unit>>) {
    FlowRow(Modifier.fillMaxWidth().offset(x = (-4).dp), verticalArrangement = Arrangement.Center) {
        levels.forEachIndexed { i, (label, action) ->
            if (i > 0) Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
            TextButton(onClick = action, contentPadding = PaddingValues(horizontal = if (i == 0) 4.dp else 8.dp),
                modifier = Modifier.heightIn(min = 48.dp)) {
                if (i == 0) { Icon(AppIcons.Back, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(label)
            }
        }
    }
}

/** Retry for data that failed to load. */
@Composable
fun RetryAction(text: String, onClick: () -> Unit, enabled: Boolean) {
    TonalAction(text, onClick, enabled = enabled, icon = AppIcons.Refresh, fill = false)
}

/** "Label: value" row used in detail cards. */
@Composable
fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
