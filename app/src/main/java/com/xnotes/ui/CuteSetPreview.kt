package com.xnotes.ui

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.caverock.androidsvg.SVG
import com.xnotes.R
import com.xnotes.core.cute.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class CuteSetPreview(val placements: List<CuteDiagramPlacement>, val pictures: List<Picture>)

private data class PreparedCuteSet(val key: CuteSpec, val result: Result<CuteSetPreview>)

/** A result belongs to its exact input: an old preview can never enable insertion for new parameters. */
@Composable
internal fun rememberCuteSetPreview(spec: CuteSpec?): Result<CuteSetPreview>? {
    val key = spec?.copy(showInput = false, rowStart = 0, columnStart = 0, transposeDiagram = false,
        hardware = spec.hardware.copy(thread = -1))
    val prepared by produceState<PreparedCuteSet?>(null, key) {
        value = null
        if (key != null) {
            delay(120) // Coalesce typing; SVG generation and parsing never run on the UI thread.
            value = withContext(Dispatchers.Default) {
                PreparedCuteSet(key, runCatching {
                    val placements = CuteDiagramSet.create(key)
                    CuteSetPreview(placements, placements.map { SVG.getFromString(it.asset.svg).renderToPicture() })
                })
            }
        }
    }
    return prepared?.takeIf { it.key == key }?.result
}

@Composable
internal fun CuteSetPreviewCard(preview: CuteSetPreview?, height: Int, workspace: CuteWorkspace) {
    val description = stringResource(if (workspace == CuteWorkspace.MMA) R.string.cute_mma_set_preview else R.string.cute_copy_set_preview)
    var zoom by remember(preview) { mutableFloatStateOf(1f) }
    var pan by remember(preview) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize(1, 1)) }
    val bounds = remember(preview) { preview?.placements?.map { it.rect }?.reduce { a, b -> a.union(b) } }
    val width = bounds?.w?.toFloat() ?: 1f
    val diagramHeight = bounds?.h?.toFloat() ?: 1f
    val fit = minOf(viewport.width / width, viewport.height / diagramHeight).coerceAtLeast(.001f)
    val origin = Offset((viewport.width - width * fit) / 2, (viewport.height - diagramHeight * fit) / 2)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cute_insertion_preview), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cute_set_gestures), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = preview != null, onClick = { zoom = 1f; pan = Offset.Zero }) { Text(stringResource(R.string.cute_fit)) }
            }
            Box(Modifier.fillMaxWidth().height(height.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (preview == null) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                } else {
                    Canvas(Modifier.fillMaxSize().padding(8.dp).onSizeChanged { viewport = it }
                        .semantics { contentDescription = description }
                        .pointerInput(preview, fit, origin) {
                            detectTransformGestures { centroid, delta, factor, _ ->
                                val next = (zoom * factor).coerceIn(1f, 8f)
                                pan = (pan + origin - centroid) * (next / zoom) + centroid - origin + delta
                                zoom = next
                                pan = Offset(pan.x.coerceIn(-width * fit * zoom, viewport.width.toFloat()),
                                    pan.y.coerceIn(-diagramHeight * fit * zoom, viewport.height.toFloat()))
                            }
                        }) {
                        val geometry = bounds ?: return@Canvas
                        val canvas = drawContext.canvas.nativeCanvas
                        val checkpoint = canvas.save()
                        canvas.translate(origin.x + pan.x, origin.y + pan.y)
                        canvas.scale(fit * zoom, fit * zoom)
                        canvas.translate(-geometry.left.toFloat(), -geometry.top.toFloat())
                        preview.placements.forEachIndexed { index, placement ->
                            val saved = canvas.save()
                            canvas.translate(placement.rect.left.toFloat(), placement.rect.top.toFloat())
                            canvas.drawPicture(preview.pictures[index])
                            canvas.restoreToCount(saved)
                        }
                        canvas.restoreToCount(checkpoint)
                    }
                }
            }
            Text(stringResource(R.string.cute_set_editable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
