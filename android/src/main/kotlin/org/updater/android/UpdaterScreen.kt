package org.updater.android

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.updater.Engine
import org.updater.MatchStatus
import org.updater.OffsetResult
import org.updater.OutputFormat
import org.updater.android.theme.StatusColors

/**
 * Everything the screen can do, so that the layout itself holds no state and can be rendered alone.
 */
data class ScreenActions(
    val browse: (BrowseFor) -> Unit = {},
    val clear: (BrowseFor) -> Unit = {},
    val setOffsetsText: (String) -> Unit = {},
    val setSigLen: (String) -> Unit = {},
    val setMaxSigLen: (String) -> Unit = {},
    val setAutoTune: (Boolean) -> Unit = {},
    val setMaxMatches: (String) -> Unit = {},
    val setFormat: (OutputFormat) -> Unit = {},
    val setEngine: (Engine) -> Unit = {},
    val setPane: (Pane) -> Unit = {},
    val setFilter: (ResultFilter) -> Unit = {},
    val toggleResult: (String) -> Unit = {},
    val copyText: (String) -> Unit = {},
    val showOptions: (Boolean) -> Unit = {},
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val copy: () -> Unit = {},
    val share: () -> Unit = {},
)

@Composable
fun UpdaterScreen(model: UpdaterViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val browse = state.browse

    val systemPicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { model.choose(browse?.target ?: BrowseFor.OLD_LIBRARY, it) }
        }
    val settings =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            model.recheckAccess()
        }
    // Below Android 11 file access is an ordinary runtime permission; from 11 on it is a setting
    // the system only lets an app ask for by sending you to it.
    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {
            model.recheckAccess()
        }

    if (browse != null) {
        FileBrowser(
            state = browse,
            actions =
                BrowserActions(
                    open = model::open,
                    up = model::up,
                    choose = model::choose,
                    showAll = model::showAllFiles,
                    grant = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            settings.launch(Storage.accessIntent(context))
                        } else {
                            permission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    systemPicker = { systemPicker.launch(arrayOf("*/*")) },
                    close = model::closeBrowser,
                ),
        )
        return
    }

    UpdaterContent(
        state = state,
        actions =
            ScreenActions(
                browse = model::browse,
                clear = model::clear,
                setOffsetsText = model::setOffsetsText,
                setSigLen = model::setSigLen,
                setMaxSigLen = model::setMaxSigLen,
                setAutoTune = model::setAutoTune,
                setMaxMatches = model::setMaxMatches,
                setFormat = model::setFormat,
                setEngine = model::setEngine,
                setPane = model::setPane,
                setFilter = model::setFilter,
                toggleResult = model::toggleResult,
                copyText = { clipboard.setText(AnnotatedString(it)) },
                showOptions = model::showOptions,
                start = model::start,
                stop = model::stop,
                copy = { clipboard.setText(AnnotatedString(model.resultsAsText())) },
                share = { share(context, model.resultsAsText()) },
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdaterContent(state: UiState, actions: ScreenActions = ScreenActions()) {
    val setupList = rememberLazyListState()
    val resultsList = rememberLazyListState()

    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copy: (String) -> Unit = { text ->
        actions.copyText(text)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(R.string.copied, text))
        }
    }

    val density = LocalDensity.current
    var dockSpace by remember { mutableStateOf(112.dp) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    ExpressiveBadge(
                        icon = R.drawable.ic_swap,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        size = 32.dp,
                    )
                },
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AnimatedContent(
                targetState = state.pane,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "pane",
            ) { pane ->
                when (pane) {
                    Pane.SETUP -> SetupPane(state, actions, setupList, dockSpace)
                    Pane.RESULTS -> ResultsPane(state, actions, resultsList, copy, dockSpace)
                }
            }

            SnackbarHost(
                snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dockSpace + 8.dp),
            )
            Box(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .onSizeChanged { dockSpace = with(density) { it.height.toDp() } + 16.dp }
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                ActionBar(state, actions)
            }
        }
    }
}

// ----------------------------------------------------------------------------- setup

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupPane(
    state: UiState,
    actions: ScreenActions,
    listState: LazyListState,
    bottomSpace: Dp,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("setup-list"),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, bottomSpace),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item { SetupIntro(state) }
        item { SectionHeader(R.drawable.ic_library, R.string.section_libraries, null) }
        item {
            PickerRow(
                shape = groupShape(0, 2),
                icon = R.drawable.ic_library,
                badge = MaterialShapes.Cookie9Sided.toShape(),
                tint = MaterialTheme.colorScheme.primaryContainer,
                onTint = MaterialTheme.colorScheme.onPrimaryContainer,
                label = stringResource(R.string.file_old_library),
                value = state.oldLib?.describe(),
                hint = stringResource(R.string.file_old_library_hint),
                onClear = state.oldLib?.let { { actions.clear(BrowseFor.OLD_LIBRARY) } },
                onClick = { actions.browse(BrowseFor.OLD_LIBRARY) },
            )
        }
        item {
            PickerRow(
                shape = groupShape(1, 2),
                icon = R.drawable.ic_library,
                badge = MaterialShapes.Clover4Leaf.toShape(),
                tint = MaterialTheme.colorScheme.secondaryContainer,
                onTint = MaterialTheme.colorScheme.onSecondaryContainer,
                label = stringResource(R.string.file_new_library),
                value = state.newLib?.describe(),
                hint = stringResource(R.string.file_new_library_hint),
                onClear = state.newLib?.let { { actions.clear(BrowseFor.NEW_LIBRARY) } },
                onClick = { actions.browse(BrowseFor.NEW_LIBRARY) },
            )
        }
        item { SectionHeader(R.drawable.ic_target, R.string.section_offsets, null) }
        item {
            PickerRow(
                shape = groupShape(0, 1),
                icon = R.drawable.ic_target,
                badge = MaterialShapes.Sunny.toShape(),
                tint = MaterialTheme.colorScheme.tertiaryContainer,
                onTint = MaterialTheme.colorScheme.onTertiaryContainer,
                label = stringResource(R.string.file_offsets),
                value = state.offsetsFile?.describe(),
                hint = offsetsHint(state),
                onClear = state.offsetsFile?.let { { actions.clear(BrowseFor.OFFSETS) } },
                onClick = { actions.browse(BrowseFor.OFFSETS) },
            )
        }

        if (state.offsetsFile == null) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedTextField(
                    value = state.offsetsText,
                    onValueChange = actions.setOffsetsText,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    label = { Text(stringResource(R.string.typed_offsets_label)) },
                    placeholder = { Text(stringResource(R.string.typed_offsets_placeholder)) },
                    shape = MaterialTheme.shapes.large,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }

        item {
            SectionHeader(
                R.drawable.ic_spark,
                R.string.section_engine,
                stringResource(state.engine.label),
            )
        }
        item { EngineCard(state, actions) }

        if (state.engine == Engine.MODERN) {
            item { Spacer(Modifier.height(6.dp)) }
            item {
                PickerRow(
                    shape = groupShape(0, 2),
                    icon = R.drawable.ic_folder,
                    badge = MaterialShapes.Pill.toShape(),
                    tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = stringResource(R.string.file_old_dump),
                    value = state.oldDump?.describe(),
                    hint = stringResource(R.string.file_old_dump_hint),
                    onClear = state.oldDump?.let { { actions.clear(BrowseFor.OLD_DUMP) } },
                    onClick = { actions.browse(BrowseFor.OLD_DUMP) },
                )
            }
            item {
                PickerRow(
                    shape = groupShape(1, 2),
                    icon = R.drawable.ic_folder,
                    badge = MaterialShapes.Pill.toShape(),
                    tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = stringResource(R.string.file_new_dump),
                    value = state.newDump?.describe(),
                    hint = stringResource(R.string.file_new_dump_hint),
                    onClear = state.newDump?.let { { actions.clear(BrowseFor.NEW_DUMP) } },
                    onClick = { actions.browse(BrowseFor.NEW_DUMP) },
                )
            }
        }
    }
}

@Composable
private fun SetupIntro(state: UiState) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            stringResource(R.string.setup_heading),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                if (state.running) R.string.status_working_detail else R.string.setup_description
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                filesSummary(state),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun EngineCard(state: UiState, actions: ScreenActions) {
    GroupSurface(MaterialTheme.shapes.large) {
        Column {
            Column(Modifier.padding(16.dp)) {
                ChoiceRow(
                    options = EngineDisplayOrder,
                    selected = state.engine,
                    label = { stringResource(it.label) },
                    onSelect = actions.setEngine,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(state.engine.blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OptionsSection(state, actions)
        }
    }
}

/** Tuning and output format, folded away: the defaults are right for most runs. */
@Composable
private fun OptionsSection(state: UiState, actions: ScreenActions) {
    val rotation by animateFloatAsState(if (state.showOptions) 180f else 0f, label = "chevron")

    Column {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable { actions.showOptions(!state.showOptions) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.section_options),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    optionsSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron),
                contentDescription =
                    stringResource(
                        if (state.showOptions) R.string.option_hide else R.string.option_show
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = state.showOptions,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (state.engine == Engine.CLASSIC) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.option_auto_tune),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.autoTune, onCheckedChange = actions.setAutoTune)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.engine == Engine.CLASSIC) {
                        NumberField(
                            value = state.sigLen,
                            onValueChange = actions.setSigLen,
                            label = stringResource(R.string.option_start_at),
                            suffix = stringResource(R.string.unit_bytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.engine == Engine.NULLBIT) {
                        // This engine takes a whole function however long it is; what it
                        // tunes against is how many places the result may match.
                        NumberField(
                            value = state.maxMatches,
                            onValueChange = actions.setMaxMatches,
                            label = stringResource(R.string.option_allow_up_to),
                            suffix = stringResource(R.string.unit_matches),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        NumberField(
                            value = state.maxSigLen,
                            onValueChange = actions.setMaxSigLen,
                            label =
                                stringResource(
                                    if (state.engine == Engine.CLASSIC) R.string.option_widen_to
                                    else R.string.option_grow_to
                                ),
                            suffix = stringResource(R.string.unit_bytes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.option_output_format),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = OutputFormat.entries,
                    selected = state.format,
                    label = { it.id },
                    onSelect = actions.setFormat,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(state.format.blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------- results

@Composable
private fun ResultsPane(
    state: UiState,
    actions: ScreenActions,
    listState: LazyListState,
    copy: (String) -> Unit,
    bottomSpace: Dp,
) {
    val shown = state.shownResults
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("results-list"),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, bottomSpace),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (state.results.isEmpty() && !state.running && state.message == null) {
            item { EmptyResults(actions) }
        } else {
            item { StatusCard(state) }
        }
        if (state.warnings.isNotEmpty()) {
            item { DiagnosticsSection(state.warnings) }
        }
        if (state.results.isNotEmpty()) {
            item { ExportActions(state.results.size, actions) }
            item { ResultFilters(state, actions) }
            itemsIndexed(shown) { index, result ->
                ResultRow(
                    result = result,
                    shape = groupShape(index, shown.size),
                    expanded = result.offset in state.expandedResults,
                    onTap = { actions.toggleResult(result.offset) },
                    copy = copy,
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.results_filter_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyResults(actions: ScreenActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveBadge(
            R.drawable.ic_target,
            MaterialShapes.Clover4Leaf.toShape(),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            size = 64.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.results_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.results_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { actions.setPane(Pane.SETUP) }) {
            Text(stringResource(R.string.results_go_setup))
        }
    }
}

@Composable
private fun DiagnosticsSection(warnings: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "diagnostics")
    Column(Modifier.padding(top = 12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_alert),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(R.plurals.diagnostics_count, warnings.size, warnings.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.option_hide else R.string.option_show
                        ),
                    modifier = Modifier.size(18.dp).rotate(rotation),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                warnings.forEachIndexed { index, warning ->
                    WarningRow(warning, groupShape(index, warnings.size))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(state: UiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpressiveBadge(
                    icon =
                        if (state.running) R.drawable.ic_spark
                        else if (state.message != null) R.drawable.ic_alert
                        else R.drawable.ic_check,
                    shape = MaterialShapes.Clover4Leaf.toShape(),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 44.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        headline(state),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.running) {
                Spacer(Modifier.height(16.dp))
                if (state.total == 0) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.results.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val counts = state.counts
                    StatTile(
                        counts[MatchStatus.RESOLVED] ?: 0,
                        R.string.stat_resolved,
                        StatusColors.resolved,
                        Modifier.weight(1f),
                    )
                    StatTile(
                        counts[MatchStatus.AMBIGUOUS] ?: 0,
                        R.string.stat_ambiguous,
                        StatusColors.ambiguous,
                        Modifier.weight(1f),
                    )
                    StatTile(
                        (counts[MatchStatus.NOT_FOUND] ?: 0) + (counts[MatchStatus.ERROR] ?: 0),
                        R.string.stat_missing,
                        StatusColors.failed,
                        Modifier.weight(1f),
                    )
                }
            }
            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ExportActions(count: Int, actions: ScreenActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics { heading() }) {
            Text(
                stringResource(R.string.section_results),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(R.plurals.results_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = actions.copy) {
            Icon(
                painterResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.action_copy),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = actions.share) {
            Icon(
                painterResource(R.drawable.ic_share),
                contentDescription = stringResource(R.string.action_share),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StatTile(
    count: Int,
    @StringRes label: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.14f),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp)) {
            Text(
                "$count",
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ResultFilters(state: UiState, actions: ScreenActions) {
    Row(
        modifier =
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ResultFilter.entries
            .map { it to it.count(state.results) }
            .filter { (filter, count) -> count > 0 || filter == ResultFilter.ALL }
            .forEach { (filter, count) ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { actions.setFilter(filter) },
                    shape = CircleShape,
                    label = {
                        Text(
                            stringResource(
                                R.string.filter_chip,
                                stringResource(filter.label),
                                count,
                            ),
                            maxLines = 1,
                        )
                    },
                )
            }
    }
}

/**
 * One result, and what tapping it does.
 *
 * A resolved offset is the thing you came for, so tapping copies it. An ambiguous one has no single
 * answer to copy, so tapping opens the candidates and each of those copies itself.
 */
@Composable
private fun ResultRow(
    result: OffsetResult,
    shape: Shape,
    expanded: Boolean,
    onTap: () -> Unit,
    copy: (String) -> Unit,
) {
    val color =
        when (result.status) {
            MatchStatus.RESOLVED -> StatusColors.resolved
            MatchStatus.AMBIGUOUS -> StatusColors.ambiguous
            MatchStatus.NOT_FOUND,
            MatchStatus.ERROR -> StatusColors.failed
        }
    val animated by animateColorAsState(color, label = "status")
    val ambiguous = result.status == MatchStatus.AMBIGUOUS
    val resolved = result.status == MatchStatus.RESOLVED
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "candidates")
    val haptics = LocalHapticFeedback.current

    GroupSurface(shape) {
        Column {
            Row(
                modifier =
                    Modifier.clickable(enabled = resolved || ambiguous) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (resolved) copy(result.foundOffsets.first()) else onTap()
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveBadge(
                    icon = if (resolved) R.drawable.ic_check else R.drawable.ic_alert,
                    shape = MaterialTheme.shapes.medium,
                    container = animated.copy(alpha = 0.14f),
                    content = animated,
                    size = 36.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        result.offset,
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        result.headline(),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        color = animated,
                        fontWeight = FontWeight.SemiBold,
                    )
                    result.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (resolved) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription =
                            stringResource(R.string.copy_offset, result.foundOffsets.first()),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (ambiguous) {
                    Icon(
                        painterResource(R.drawable.ic_chevron),
                        contentDescription =
                            stringResource(
                                if (expanded) R.string.hide_candidates else R.string.show_candidates
                            ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).rotate(rotation),
                    )
                }
            }

            AnimatedVisibility(
                visible = ambiguous && expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 10.dp)) {
                    result.foundOffsets.forEach { candidate ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { copy(candidate) }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                candidate,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painterResource(R.drawable.ic_copy),
                                contentDescription =
                                    stringResource(R.string.copy_offset, candidate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningRow(text: String, shape: Shape) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_alert),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ----------------------------------------------------------------------------- pieces

@Composable
private fun ActionBar(state: UiState, actions: ScreenActions) {
    Surface(
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("floating-navigation"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.selectableGroup().height(IntrinsicSize.Min).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneButton(
                label = stringResource(R.string.pane_setup),
                icon = R.drawable.ic_tune,
                selected = state.pane == Pane.SETUP,
                onClick = { actions.setPane(Pane.SETUP) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            FilledIconButton(
                onClick = if (state.running) actions.stop else actions.start,
                enabled = state.running || state.canStart,
                modifier = Modifier.width(72.dp).heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                ToolbarAction(
                    if (state.running) R.drawable.ic_stop else R.drawable.ic_play,
                    if (state.running) R.string.action_stop else R.string.action_start,
                )
            }
            PaneButton(
                label = stringResource(R.string.pane_results),
                icon = R.drawable.ic_target,
                selected = state.pane == Pane.RESULTS,
                onClick = { actions.setPane(Pane.RESULTS) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ToolbarAction(icon: Int, @StringRes label: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

/** One end of the toolbar: where tapping it takes you, and whether you are already there. */
@Composable
private fun PaneButton(
    label: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.large)
                .background(background)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeader(icon: Int, @StringRes title: Int, trailing: String?) {
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 2.dp, top = 20.dp, bottom = 8.dp).semantics {
                heading()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupSurface(shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
private fun PickerRow(
    shape: Shape,
    icon: Int,
    badge: Shape,
    tint: Color,
    onTint: Color,
    label: String,
    value: String?,
    hint: String,
    onClear: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) 0.975f else 1f,
            animationSpec = spring(dampingRatio = 0.55f),
            label = "press",
        )
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxWidth().scale(scale),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier =
                Modifier.clickable(interactionSource = interaction, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveBadge(icon, badge, tint, onTint, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(
                    value ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    maxLines = if (value == null) 2 else 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (onClear != null) {
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear_file, label),
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp).rotate(-90f),
                )
            }
        }
    }
}

/**
 * A row of expressive toggle buttons, one per option.
 *
 * The selected one takes more of the row and squares off, which is the expressive way of showing a
 * choice: the shape carries the state, not just the colour.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                ToggleButton(
                    checked = option == selected,
                    onCheckedChange = { onSelect(option) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shapes = ToggleButtonDefaults.shapes(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    // The row's own height is intrinsic -- as tall as its tallest button -- and every button
    // fills it, so a label that wraps to two lines (an engine name with a long word on the end)
    // doesn't leave its neighbours short or get clipped to fit a line that was never guaranteed.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val weight by
                animateFloatAsState(
                    targetValue = if (isSelected) 1.4f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f),
                    label = "choice",
                )
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(weight).fillMaxHeight().heightIn(min = 52.dp),
                shapes = ToggleButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ----------------------------------------------------------------------------- text

@Composable
private fun PickedFile.describe(): String {
    val size = readableSize(bytes)
    return if (size.isEmpty()) name else stringResource(R.string.file_description, name, size)
}

/** What the Files header says on its right, so the section reads at a glance. */
@Composable
private fun filesSummary(state: UiState): String {
    val chosen =
        listOfNotNull(state.oldLib, state.newLib).size +
            if (state.offsetsFile != null || state.offsetsText.isNotBlank()) 1 else 0

    return if (chosen == 3) stringResource(R.string.files_ready)
    else stringResource(R.string.files_chosen, chosen)
}

@Composable
private fun offsetsHint(state: UiState): String {
    if (state.offsetsText.isBlank()) return stringResource(R.string.file_offsets_hint)

    val typed = state.offsetsText.lines().count { it.isNotBlank() }
    return pluralStringResource(R.plurals.file_offsets_typed, typed, typed)
}

@Composable
private fun tuningSummary(state: UiState): String =
    when (state.engine) {
        Engine.CLASSIC ->
            if (state.autoTune) stringResource(R.string.tuning_auto, state.maxSigLen)
            else stringResource(R.string.tuning_fixed, state.sigLen)
        Engine.MODERN -> stringResource(R.string.tuning_grow, state.maxSigLen)
        Engine.NULLBIT ->
            pluralStringResource(
                R.plurals.tuning_matches,
                state.maxMatches.toIntOrNull() ?: 1,
                state.maxMatches,
            )
    }

@Composable
private fun optionsSummary(state: UiState): String =
    stringResource(R.string.options_summary, tuningSummary(state), state.format.id)

@Composable
private fun OffsetResult.headline(): String =
    when (status) {
        MatchStatus.RESOLVED -> foundOffsets.first()
        MatchStatus.AMBIGUOUS ->
            pluralStringResource(R.plurals.result_matches, foundOffsets.size, foundOffsets.size)
        MatchStatus.NOT_FOUND -> stringResource(R.string.result_not_found)
        MatchStatus.ERROR -> stringResource(R.string.result_error)
    }

@Composable
private fun headline(state: UiState): String =
    when {
        state.running ->
            stringResource(
                R.string.status_working,
                state.done,
                if (state.total > 0) "${state.total}"
                else stringResource(R.string.status_total_unknown),
            )
        state.results.isEmpty() -> stringResource(R.string.status_ready)
        else ->
            stringResource(
                R.string.status_done,
                state.counts[MatchStatus.RESOLVED] ?: 0,
                state.results.size,
            )
    }

@Composable
private fun detail(state: UiState): String =
    when {
        state.running -> stringResource(R.string.status_working_detail)
        state.results.isEmpty() -> stringResource(R.string.status_ready_detail)
        else ->
            stringResource(
                R.string.status_done_detail,
                state.seconds.toFloat(),
                stringResource(state.engine.label),
            )
    }

private fun share(context: Context, text: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser)))
}
