package org.updater.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the browser can do, kept apart from the state so the screen renders on its own. */
data class BrowserActions(
    val open: (String) -> Unit = {},
    val up: () -> Unit = {},
    val choose: (String) -> Unit = {},
    val showAll: (Boolean) -> Unit = {},
    val grant: () -> Unit = {},
    val systemPicker: () -> Unit = {},
    val close: () -> Unit = {},
)

/**
 * The built-in file manager.
 *
 * It walks the filesystem rather than going through the system picker, which is what makes a 130 MB
 * library cheap to read and a path worth remembering between launches. The picker is still one
 * button away, for anything this cannot reach.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileBrowser(state: BrowseState, actions: BrowserActions = BrowserActions()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.browser_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(state.target.title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.close) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.browser_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.systemPicker) {
                        Icon(
                            painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.browser_system_picker),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.denied) {
                AccessGate(actions)
                return@Column
            }

            Crumbs(state, actions)
            Shortcuts(state, actions)
            Text(
                if (state.showAll) stringResource(R.string.browser_showing_all)
                else stringResource(R.string.browser_showing_first, state.target.extensions(", .")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            AnimatedVisibility(visible = state.loading) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            if (state.entries.isEmpty() && !state.loading) {
                EmptyFolder(state, actions)
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(state.entries) { index, entry ->
                    EntryRow(
                        entry = entry,
                        shape = groupShape(index, state.entries.size),
                        onClick = {
                            if (entry.isDirectory) actions.open(entry.path)
                            else actions.choose(entry.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Crumbs(state: BrowseState, actions: BrowserActions) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.up, modifier = Modifier.size(48.dp)) {
            Icon(
                painterResource(R.drawable.ic_up),
                contentDescription = stringResource(R.string.browser_up),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Storage.crumbs(state.path).forEachIndexed { index, (name, path) ->
            if (index > 0) {
                Text(
                    "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            Text(
                if (name == Storage.STORAGE_CRUMB) stringResource(R.string.browser_place_storage)
                else name,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (path == state.path) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (path == state.path) FontWeight.SemiBold else FontWeight.Normal,
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable { actions.open(path) }
                        .padding(10.dp, 16.dp),
            )
        }
    }
}

@Composable
private fun Shortcuts(state: BrowseState, actions: BrowserActions) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Storage.shortcuts(state.target).forEach { (name, path) ->
            AssistChip(
                onClick = { actions.open(path) },
                label = { Text(stringResource(name)) },
                shape = CircleShape,
            )
        }
        FilterChip(
            selected = state.showAll,
            onClick = { actions.showAll(!state.showAll) },
            label = { Text(stringResource(R.string.browser_all_files)) },
            shape = CircleShape,
        )
    }
}

@Composable
private fun EntryRow(entry: BrowseEntry, shape: Shape, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color =
            if (entry.interesting) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier =
                Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveBadge(
                icon = if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_library,
                shape = MaterialTheme.shapes.medium,
                container =
                    if (entry.isDirectory) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                content =
                    if (entry.isDirectory) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 36.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.isDirectory) {
                Spacer(Modifier.width(8.dp))
                Text(
                    readableSize(entry.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AccessGate(actions: BrowserActions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveBadge(
            icon = R.drawable.ic_folder,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            size = 80.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.access_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = actions.grant,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(stringResource(R.string.access_allow))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = actions.systemPicker) {
            Text(stringResource(R.string.access_use_picker))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun EmptyFolder(state: BrowseState, actions: BrowserActions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ExpressiveBadge(
            icon = R.drawable.ic_folder,
            shape = MaterialShapes.Clover4Leaf.toShape(),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            size = 80.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (state.showAll) stringResource(R.string.browser_empty)
            else
                stringResource(
                    R.string.browser_empty_filtered,
                    state.target.extensions.joinToString("/") { ".$it" },
                ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        if (!state.showAll) {
            TextButton(onClick = { actions.showAll(true) }) {
                Text(stringResource(R.string.browser_show_every_file))
            }
        }
    }
}

/** A size as the resource that words it and the number that goes in it, or null for no size. */
private fun sizeOf(bytes: Long): Pair<Int, Long>? =
    when {
        bytes <= 0 -> null
        bytes >= 1024 * 1024 -> R.string.size_megabytes to bytes / (1024 * 1024)
        bytes >= 1024 -> R.string.size_kilobytes to bytes / 1024
        else -> R.string.size_bytes to bytes
    }

@Composable
internal fun readableSize(bytes: Long): String =
    sizeOf(bytes)?.let { (worded, value) -> stringResource(worded, value) } ?: ""
