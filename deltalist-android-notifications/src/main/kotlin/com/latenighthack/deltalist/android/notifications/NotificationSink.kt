package com.latenighthack.deltalist.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.latenighthack.deltalist.Stable

/**
 * The side-effecting surface that the [TrayController] drives. Keeping it behind an
 * interface lets the controller's delta -> operation logic be unit-tested with a fake,
 * with no Android types involved in the assertions.
 *
 * @param E the list element type (carries identity); @param T the notification payload type.
 */
internal interface NotificationSink<E : Stable, T> {
    fun post(stableId: Int, value: T, state: Any?)
    fun cancel(stableId: Int)
    fun postSummary(items: List<E>)
    fun cancelSummary()
}

/**
 * Production sink: renders each item through the builder's `content` map and posts it to
 * the system tray via [NotificationManagerCompat]. Channels are created lazily and
 * idempotently on first post.
 */
internal class ManagerNotificationSink<E : Stable, T>(
    private val context: Context,
    private val config: NotifierConfig<T>,
    private val summary: (NotificationSummaryScope.(List<E>) -> Notification)?,
) : NotificationSink<E, T> {

    private val manager = NotificationManagerCompat.from(context)
    private var channelsCreated = false

    private fun ensureChannels() {
        if (channelsCreated) return
        config.channels.forEach { spec ->
            val builder = NotificationChannelCompat.Builder(spec.id, spec.importance)
                .setName(spec.name)
            spec.configure(builder)
            manager.createNotificationChannel(builder.build())
        }
        channelsCreated = true
    }

    @SuppressLint("MissingPermission")
    override fun post(stableId: Int, value: T, state: Any?) {
        ensureChannels()
        val scope = NotificationScope(context, stableId, state, config)
        val notification = config.content.invoke(scope, value)
        manager.notify(config.tag, config.notifId(stableId), notification)
    }

    override fun cancel(stableId: Int) {
        manager.cancel(config.tag, config.notifId(stableId))
    }

    @SuppressLint("MissingPermission")
    override fun postSummary(items: List<E>) {
        val summary = summary ?: return
        val groupKey = config.groupKey ?: return
        ensureChannels()
        val scope = NotificationSummaryScope(context, groupKey, config.defaultChannelId())
        val notification = summary.invoke(scope, items)
        manager.notify(config.summaryTag, SUMMARY_ID, notification)
    }

    override fun cancelSummary() {
        manager.cancel(config.summaryTag, SUMMARY_ID)
    }

    private companion object {
        const val SUMMARY_ID = 0
    }
}
