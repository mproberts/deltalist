package com.latenighthack.deltalist.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Receiver for the `content { }` mapping. Provides a [NotificationCompat.Builder] that is
 * pre-wired with the channel, group, and the durable back-channel intents (body tap +
 * dismissal). You only set the visual content.
 *
 * @property stableId the item's session-stable id (also the tray notification id base).
 * @property state the latest value emitted by `itemState { }`, or null if none.
 */
class NotificationScope<T> internal constructor(
    val context: Context,
    val stableId: Int,
    val state: Any?,
    private val config: NotifierConfig<T>,
) {
    /**
     * Builds a notification whose channel, group, content-tap and dismissal intents are
     * already wired. Declare action buttons inside [body] via [action].
     */
    fun notification(
        channelId: String? = null,
        body: NotificationCompat.Builder.() -> Unit,
    ): Notification {
        val channel = channelId ?: config.defaultChannelId()
        ?: error("No channel configured. Call channel(...) in the notifier builder or pass channelId.")

        val builder = NotificationCompat.Builder(context, channel)
            .setContentIntent(pendingIntent(NotificationInteraction.Type.ContentTap, null))
            .setDeleteIntent(pendingIntent(NotificationInteraction.Type.Dismiss, null))

        config.group?.let { builder.setGroup(it.key) }
        builder.body()
        return builder.build()
    }

    /**
     * Adds an action button whose tap is routed back to `onAction(item, key)` via a durable
     * PendingIntent.
     */
    fun NotificationCompat.Builder.action(
        icon: Int,
        title: CharSequence,
        key: String,
    ): NotificationCompat.Builder =
        addAction(icon, title, pendingIntent(NotificationInteraction.Type.Action, key))

    private fun pendingIntent(type: NotificationInteraction.Type, actionKey: String?): PendingIntent {
        val intent = Intent(context, DeltaNotificationReceiver::class.java).apply {
            action = ACTION_INTERACT
            putExtra(EXTRA_TAG, config.tag)
            putExtra(EXTRA_STABLE_ID, stableId)
            putExtra(EXTRA_TYPE, type.name)
            actionKey?.let { putExtra(EXTRA_ACTION_KEY, it) }
        }
        // Distinct request code per (notification id, action) so PendingIntents never alias.
        val keyPart = actionKey ?: "__${type.name}__"
        val requestCode = config.notifId(stableId) * 31 + keyPart.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Receiver for the optional group `summary { }` builder. The returned notification is
 * automatically marked as the group summary.
 */
class NotificationSummaryScope internal constructor(
    val context: Context,
    private val groupKey: String,
    private val defaultChannelId: String?,
) {
    fun summaryNotification(
        channelId: String? = null,
        body: NotificationCompat.Builder.() -> Unit,
    ): Notification {
        val channel = channelId ?: defaultChannelId
        ?: error("No channel configured for the group summary.")

        return NotificationCompat.Builder(context, channel)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .apply(body)
            .build()
    }
}
