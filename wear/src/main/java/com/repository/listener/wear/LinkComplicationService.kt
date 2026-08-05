package com.repository.listener.wear

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Publishes the glasses link status into a watch-face complication slot.
 *
 * This is the only always-visible surface the app owns, so it does no work of its
 * own: it reads whatever [WatchLinkService] already computed and returns. It never
 * starts the link service, never opens a session and never holds a wakelock -- a
 * watch face binds this on every ambient tick, and anything expensive here is
 * charged to the user's battery all day.
 *
 * Updates are pushed, not polled: UPDATE_PERIOD_SECONDS is 0 in the manifest and
 * [requestUpdate] is called from [WatchLinkService] only on an actual state
 * transition.
 */
class LinkComplicationService : SuspendingComplicationDataSourceService() {

    companion object {

        private const val TAG = "LinkComplication"

        /**
         * Floor between pushes.
         *
         * Edge-triggering alone is NOT enough of a limiter. The status frame is
         * advisory and arrives on the ping cadence, so a phone flapping a status
         * bit produces a genuine state transition per ping -- roughly six pushes a
         * minute, sustained, against a documented budget of "a few minutes on
         * average". Exceeding it gets the data source throttled, which the user
         * would experience as a permanently stale complication.
         *
         * Chosen comfortably above the ping interval so a flap cannot beat it.
         */
        private const val MIN_PUSH_INTERVAL_MS = 30_000L

        private val pushHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var lastPushMs = 0L

        @Volatile
        private var pushScheduled = false

        /**
         * Asks the platform to re-request our data, at most once per
         * [MIN_PUSH_INTERVAL_MS].
         *
         * Trailing edge, not leading-only: when a burst is dropped, one push is
         * scheduled at the interval boundary so the FINAL state always reaches the
         * watch face. Dropping the tail instead would leave the complication
         * showing a transient state permanently, which is the exact failure this
         * limiter is meant to avoid causing.
         */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            val now = SystemClock.elapsedRealtime()
            val since = now - lastPushMs

            if (since >= MIN_PUSH_INTERVAL_MS) {
                lastPushMs = now
                push(app)
                return
            }

            // A push is already queued; it will pick up whatever the state is when
            // it fires, so there is nothing to add.
            if (pushScheduled) return

            pushScheduled = true
            pushHandler.postDelayed(
                {
                    pushScheduled = false
                    lastPushMs = SystemClock.elapsedRealtime()
                    push(app)
                },
                MIN_PUSH_INTERVAL_MS - since,
            )
        }

        private fun push(context: Context) {
            try {
                ComplicationDataSourceUpdateRequester
                    .create(
                        context = context,
                        complicationDataSourceComponent = ComponentName(
                            context,
                            LinkComplicationService::class.java,
                        ),
                    )
                    .requestUpdateAll()
            } catch (e: Exception) {
                // Must never take down the link service. Logged rather than
                // swallowed: a failure here is invisible on the watch face except
                // as data that silently stops changing, so this line is the only
                // way to tell a throttle or a binder failure from a stuck state
                // machine.
                Log.w(TAG, "complication update request failed", e)
            }
        }
    }

    /**
     * Built lazily rather than in a field initialiser: a Service has no usable
     * base context until the framework attaches one, and PendingIntent creation
     * needs it.
     */
    private val factory: ComplicationDataFactory by lazy { ComplicationDataFactory(this) }

    /**
     * Fired when the user places us in a slot.
     *
     * Without this the complication would render whatever the link service last
     * pushed -- and if that service is not running (fresh boot, process death, or
     * the session's own stopSelf), nothing would ever push again and the slot
     * would stay frozen forever. Requesting one update here is what makes the very
     * first render correct.
     *
     * It deliberately does NOT start [WatchLinkService]: a watch face must not be
     * able to start a foreground session merely by being displayed. The null state
     * renders as "not running, tap to start" instead, which is honest and gives
     * the user the action.
     */
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        requestUpdate(this)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // Read-only, and null-preserving: null means the link service is not
        // running at all, which reads differently from any LinkState. Collapsing
        // it to SETUP would render "Pair" on a dead service forever.
        val state = WatchLinkService.current()?.state
        return factory.build(request.complicationType, state)
    }

    /**
     * Static sample shown in the complication picker.
     *
     * Returning null here removes this data source from the picker FOR THAT TYPE,
     * so every type in the manifest's SUPPORTED_TYPES must produce something.
     * READY is the sample because it is the state the user is trying to reach.
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        factory.build(type, LinkState.READY)
}
