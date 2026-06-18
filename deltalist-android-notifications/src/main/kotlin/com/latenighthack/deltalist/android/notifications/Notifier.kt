package com.latenighthack.deltalist.android.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.StableItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Binds this [DeltaList] of stably-identified items to system-tray notifications.
 *
 * Items must be [StableItem] (call `.withStableIds()`) because a notification's identity is
 * an `Int` id that must follow an item across mutations. The returned notifier is unbound;
 * call [DeltaNotifier.bind] to start syncing the tray.
 *
 * ```kotlin
 * val notifier = downloads.withStableIds().notifier(context) {
 *     channel("downloads", "Downloads")
 *     content { dl -> notification { setSmallIcon(R.drawable.ic); setContentTitle(dl.name) } }
 *     onAction { dl, key -> /* ... */ }
 * }.bind(lifecycleOwner)
 * ```
 */
fun <T> DeltaList<StableItem<T>>.notifier(
    context: Context,
    build: NotifierBuilder<T>.() -> Unit,
): DeltaNotifier<T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<T>().apply(build)
    return DeltaNotifier(appContext, this, builder.toConfig(), builder.toOptions())
}

/** DSL for configuring a [DeltaNotifier]. */
class NotifierBuilder<T> internal constructor() {
    private val channels = mutableListOf<ChannelSpec>()
    private var tag: String = "deltalist"
    private var idBase: Int = 0
    private var group: GroupSpec<T>? = null
    private var content: (NotificationScope<T>.(T) -> Notification)? = null
    private var stateAccessor: ((T) -> Flow<Any?>)? = null
    private var stateInitial: ((T) -> Any?)? = null
    private var onDismiss: ((T) -> Unit)? = null
    private var onContentTap: ((T) -> Unit)? = null
    private var onAction: ((T, String) -> Unit)? = null

    /** If false (default), [DeltaNotifier.unbind] leaves posted notifications standing. */
    var cancelOnUnbind: Boolean = false

    /** Upper bound on `itemState` re-posts per item per second (under Android's ~10/s cap). */
    var rateLimitPerSecond: Int = 8

    fun channel(
        id: String,
        name: CharSequence,
        importance: Int = NotificationManagerCompat.IMPORTANCE_DEFAULT,
        configure: NotificationChannelCompat.Builder.() -> Unit = {},
    ) {
        channels.add(ChannelSpec(id, name, importance, configure))
    }

    /** Namespaces this notifier's tray ids and routes its interactions. */
    fun idSpace(tag: String, idBase: Int = 0) {
        this.tag = tag
        this.idBase = idBase
    }

    fun group(
        key: String,
        summary: (NotificationSummaryScope.(items: List<StableItem<T>>) -> Notification)? = null,
    ) {
        group = GroupSpec(key, summary)
    }

    /** Maps each item's value to a notification. Called on insert, update, reload and state change. */
    fun content(map: NotificationScope<T>.(item: T) -> Notification) {
        content = map
    }

    /** Optional per-item observable state; each emission re-runs [content] and re-posts the same id. */
    @Suppress("UNCHECKED_CAST")
    fun <S> itemState(accessor: (T) -> Flow<S>, initial: ((T) -> S)? = null) {
        stateAccessor = accessor as (T) -> Flow<Any?>
        stateInitial = initial as ((T) -> Any?)?
    }

    fun onDismiss(handler: (item: T) -> Unit) {
        onDismiss = handler
    }

    fun onContentTap(handler: (item: T) -> Unit) {
        onContentTap = handler
    }

    fun onAction(handler: (item: T, actionKey: String) -> Unit) {
        onAction = handler
    }

    internal fun toConfig(): NotifierConfig<T> {
        val map = content ?: error("notifier { content { ... } } is required")
        return NotifierConfig(tag, idBase, channels.toList(), map, group, onDismiss, onContentTap, onAction)
    }

    internal fun toOptions(): NotifierOptions<T> =
        NotifierOptions(cancelOnUnbind, rateLimitPerSecond, stateAccessor, stateInitial)
}

internal class ChannelSpec(
    val id: String,
    val name: CharSequence,
    val importance: Int,
    val configure: NotificationChannelCompat.Builder.() -> Unit,
)

internal class GroupSpec<T>(
    val key: String,
    val summary: (NotificationSummaryScope.(List<StableItem<T>>) -> Notification)?,
)

internal class NotifierConfig<T>(
    val tag: String,
    val idBase: Int,
    val channels: List<ChannelSpec>,
    val content: NotificationScope<T>.(T) -> Notification,
    val group: GroupSpec<T>?,
    val onDismiss: ((T) -> Unit)?,
    val onContentTap: ((T) -> Unit)?,
    val onAction: ((T, String) -> Unit)?,
) {
    fun notifId(stableId: Int): Int = idBase + stableId
    fun defaultChannelId(): String? = channels.firstOrNull()?.id
    val summaryTag: String get() = "$tag#summary"
}

internal class NotifierOptions<T>(
    val cancelOnUnbind: Boolean,
    val rateLimitPerSecond: Int,
    val stateAccessor: ((T) -> Flow<Any?>)?,
    val stateInitial: ((T) -> Any?)?,
)

/**
 * Drives the system tray from a [DeltaList]. Mirrors `DeltaAdapter`'s bind/unbind lifecycle.
 *
 * Unlike a RecyclerView adapter, posted notifications live in the OS tray independently of
 * this binding: by default [unbind] does NOT clear them (set `cancelOnUnbind = true` or call
 * [cancelAll] for deterministic teardown). Interactions keep working after unbind because the
 * back-channel runs through durable PendingIntents to [DeltaNotificationReceiver].
 */
class DeltaNotifier<T> internal constructor(
    context: Context,
    private val deltaList: DeltaList<StableItem<T>>,
    private val config: NotifierConfig<T>,
    private val options: NotifierOptions<T>,
) {
    private val sink: NotificationSink<T> = ManagerNotificationSink(context, config)
    private val grouped: Boolean = config.group?.summary != null

    private var controller: TrayController<T>? = null
    private var job: Job? = null

    fun bind(owner: LifecycleOwner): DeltaNotifier<T> {
        unbind()
        val scope = owner.lifecycleScope
        val controller = TrayController(
            scope = scope,
            sink = sink,
            grouped = grouped,
            stateAccessor = options.stateAccessor,
            stateInitial = options.stateInitial,
            rateLimitPerSecond = options.rateLimitPerSecond,
        )
        this.controller = controller
        NotifierRegistry.register(config.tag, this)
        job = scope.launch {
            deltaList.collect { delta: Delta<StableItem<T>> -> controller.applyDelta(delta) }
        }
        return this
    }

    fun unbind() {
        NotifierRegistry.unregister(config.tag, this)
        job?.cancel()
        job = null
        if (options.cancelOnUnbind) controller?.cancelAll() else controller?.stop()
    }

    /** Remove every notification this notifier posted, and any group summary. */
    fun cancelAll() {
        controller?.cancelAll()
    }

    internal fun dispatch(interaction: NotificationInteraction): Boolean {
        val value = controller?.valueFor(interaction.stableId) ?: return false
        when (interaction.type) {
            NotificationInteraction.Type.Dismiss -> config.onDismiss?.invoke(value)
            NotificationInteraction.Type.ContentTap -> config.onContentTap?.invoke(value)
            NotificationInteraction.Type.Action ->
                interaction.actionKey?.let { key -> config.onAction?.invoke(value, key) }
        }
        return true
    }
}
