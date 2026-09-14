package dev.aesir1.rowly.ui.activity

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.atomic.AtomicBoolean

private const val START_COLOR = 0xFF2E9E4F.toInt()
private const val END_COLOR = 0xFFD64545.toInt()
private const val ROUTE_COLOR = 0xFF1B6C8C.toInt()
private const val CURSOR_COLOR = 0xFFF2A007.toInt()

/**
 * The recorded route on an OpenStreetMap base layer, with a green start marker and a red end
 * marker, zoomed so the whole session fits.
 *
 * osmdroid rather than Google Maps because it needs no API key, no Cloud project and no billing
 * account. It is a classic Android View, so it is held across recompositions and its lifecycle is
 * driven by hand.
 */
@Composable
fun RouteMap(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    /** Where the chart is being scrubbed, or null when nothing is selected. */
    cursor: GeoPoint? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
        }
    }

    val cursorMarker = remember { marker(mapView, GeoPoint(0.0, 0.0), CURSOR_COLOR) }
    // The route is drawn once. Rebuilding a few thousand polyline points on every scrub of the
    // chart would stutter, and re-fitting the bounding box would undo the user's own pan and zoom.
    val drawn = remember(points) { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        // osmdroid starts its tile-request threads in onResume, and a LifecycleEventObserver
        // registered on an already-RESUMED owner never replays that event. Opening this screen
        // from a running app therefore left the downloader stopped and the map blank until a
        // touch forced a reload - hence the explicit call before the observer is attached.
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        // MapView is a real Android View drawn in its own layer, and osmdroid happily paints
        // tiles past its bounds - without clipping it covers whatever Compose lays out below it.
        modifier = modifier.clipToBounds(),
        factory = { mapView },
        update = { map ->
            if (points.isNotEmpty() && drawn.compareAndSet(false, true)) {
                map.overlays.clear()
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(points)
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.color = ROUTE_COLOR
                    },
                )
                map.overlays.add(marker(map, points.first(), START_COLOR))
                if (points.size > 1) map.overlays.add(marker(map, points.last(), END_COLOR))

                // Fitting the route has to wait for a layout pass: before one, the viewport is
                // zero-sized and the zoom level would be computed against nothing.
                val box = BoundingBox.fromGeoPoints(points).increaseByScale(1.2f)
                map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false) }
                map.post { map.zoomToBoundingBox(box, false) }
            }
            map.overlays.remove(cursorMarker)
            if (cursor != null) {
                cursorMarker.position = cursor
                map.overlays.add(cursorMarker)
            }
            map.invalidate()
        },
    )
}

private fun marker(map: MapView, at: GeoPoint, color: Int) = Marker(map).apply {
    position = at
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = dot(color)
    setInfoWindow(null)
}

private fun dot(color: Int): Drawable = ShapeDrawable(OvalShape()).apply {
    paint.color = color
    intrinsicWidth = 36
    intrinsicHeight = 36
}

/**
 * osmdroid caches tiles on disk and identifies itself to the OSM tile servers, which reject its
 * default user agent outright. Both have to be set before the first MapView exists.
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    if (config.userAgentValue != context.packageName) {
        config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = context.packageName
    }
}
