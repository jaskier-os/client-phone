package com.repository.listener.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The side-carousel tile. Shows the link state; the whole surface is tappable and
 * launches [ScrollRemoteActivity], because a Tile cannot receive rotary events
 * itself.
 */
class StatusTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"

        /**
         * Self-heal refresh. Deliberately coarse: sub-minute freshness on a tile is
         * a battery anti-pattern and the platform coalesces it anyway. Real updates
         * are pushed from [WatchLinkService] on state transitions.
         */
        private const val FRESHNESS_MS = 12L * 60L * 1000L

        fun requestUpdate(context: Context) {
            try {
                getUpdater(context).requestUpdate(StatusTileService::class.java)
            } catch (e: Exception) {
                // A tile that is not added yet has no updater; never let this
                // take down the link service.
            }
        }
    }

    override fun onTileAddEvent(requestParams: androidx.wear.tiles.EventBuilders.TileAddEvent) {
        super.onTileAddEvent(requestParams)
        // Without this a freshly added tile renders blank until the first
        // scheduled refresh.
        requestUpdate(this)
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val state = WatchLinkService.current()?.state ?: LinkState.SETUP

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(ScrollRemoteActivity::class.java.name)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("Glasses Remote")
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(state.label)
                            .build()
                    )
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
}
