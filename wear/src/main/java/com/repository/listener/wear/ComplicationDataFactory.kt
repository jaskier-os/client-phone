package com.repository.listener.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType

/**
 * Builds the [ComplicationData] for a given [LinkState] and slot type.
 *
 * Split out of [LinkComplicationService] so it can be constructed with any
 * Context. A Service instance cannot be built in a test without the framework
 * attaching a base context, which would leave the type coverage of the picker
 * preview -- the thing that decides whether we are offered at all -- unverifiable.
 *
 * Both the live request and the picker preview go through here, so a preview can
 * never render differently from the real thing.
 */
class ComplicationDataFactory(private val context: Context) {

    /**
     * Launches the full remote screen, which is where the rotary bezel actually
     * works; a complication cannot receive rotary events itself.
     *
     * FLAG_IMMUTABLE is mandatory from Android 12 -- the platform rejects a
     * mutable PendingIntent here outright.
     */
    private fun tapAction(): PendingIntent {
        val intent = Intent(context, ScrollRemoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun icon(): Icon = Icon.createWithResource(context, R.drawable.ic_complication)

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    /**
     * Returns null for a type this data source does not support. Building the
     * wrong type instead would make the platform drop us entirely.
     *
     * A null [state] means [WatchLinkService] is not running, which is distinct
     * from any LinkState and must read differently -- see [ComplicationCopy].
     */
    fun build(type: ComplicationType, state: LinkState?): ComplicationData? {
        val shortText = state?.let { ComplicationCopy.shortText(it) }
            ?: ComplicationCopy.INACTIVE_SHORT
        val longText = state?.let { ComplicationCopy.longText(it) }
            ?: ComplicationCopy.INACTIVE_LONG
        val health = state?.let { ComplicationCopy.healthRank(it) } ?: 0f
        val description = text(
            state?.let { ComplicationCopy.contentDescription(it) }
                ?: ComplicationCopy.INACTIVE_DESCRIPTION,
        )

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text(shortText),
                contentDescription = description,
            )
                .setTitle(text(ComplicationCopy.TITLE))
                .setMonochromaticImage(MonochromaticImage.Builder(icon()).build())
                .setTapAction(tapAction())
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = text(longText),
                contentDescription = description,
            )
                .setTitle(text(ComplicationCopy.TITLE))
                .setMonochromaticImage(MonochromaticImage.Builder(icon()).build())
                .setTapAction(tapAction())
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = health,
                min = 0f,
                max = ComplicationCopy.HEALTH_MAX,
                contentDescription = description,
            )
                .setText(text(shortText))
                .setMonochromaticImage(MonochromaticImage.Builder(icon()).build())
                .setTapAction(tapAction())
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = MonochromaticImage.Builder(icon()).build(),
                contentDescription = description,
            )
                .setTapAction(tapAction())
                .build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(icon(), SmallImageType.ICON).build(),
                contentDescription = description,
            )
                .setTapAction(tapAction())
                .build()

            else -> null
        }
    }
}
