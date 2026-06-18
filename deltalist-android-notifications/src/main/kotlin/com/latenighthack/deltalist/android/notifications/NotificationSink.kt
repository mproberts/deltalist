package com.latenighthack.deltalist.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.latenighthack.deltalist.StableItem

/**
 * The side-effecting surface that the [TrayController] drives. Keeping it behind an
 * interface lets the controller's delta -> operation logic be unit-tested with a fake,
 * with no Android types involved in the assertions.
 */
internal interface NotificationSink<T> {
    fun post(stableId: Int, value: T, state: Any?)
    fun cancel(stableId: Int)
    fun postSummary(items: List<StableItem<T>>)
    fun cancelSummary()
}

/**
 * Production sink: renders each item through the builder's `content` map and posts it to
 * the system tray via [NotificationManagerCompat]. Channels are created lazily and
 * idempotently on first post.
 */
internal class ManagerNotificationSink<T>(
    private val context: Context,
    private val config: NotifierConfig<T>,
) : NotificationSink<T> {

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
    override fun postSummary(items: List<StableItem<T>>) {
        val group = config.group ?: return
        val summary = group.summary ?: return
        ensureChannels()
        val scope = NotificationSummaryScope(context, group.key, config.defaultChannelId())
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
