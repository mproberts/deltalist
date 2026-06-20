package com.latenighthack.deltalist.android.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.StableItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Binds this [DeltaList] of [StableItem]-wrapped items to system-tray notifications.
 *
 * Use this overload when ids are synthesized by `.withStableIds()`; the notification payload
 * is each item's [StableItem.value]. The returned notifier is unbound; call
 * [DeltaNotifier.bind] to start syncing the tray.
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
    build: NotifierBuilder<StableItem<T>, T>.() -> Unit,
): DeltaNotifier<StableItem<T>, T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<StableItem<T>, T>().apply(build)
    return DeltaNotifier(appContext, this, builder.toConfig(), builder.toOptions(), builder.toSummary(), valueOf = { it.value })
}

/**
 * Binds this [DeltaList] of self-identifying items to system-tray notifications.
 *
 * Use this when the item type already implements [Stable]; the item itself is the
 * notification payload, so no `.withStableIds()` wrapping is needed. (It has a distinct name
 * from [notifier] only because, under an invariant `Delta`, the two cannot be disambiguated
 * by overload resolution for `StableItem` inputs.)
 *
 * ```kotlin
 * data class Download(override val stableId: Int, val name: String) : Stable
 * val notifier = downloads.notifierForStable(context) {
 *     channel("downloads", "Downloads")
 *     content { dl -> notification { setContentTitle(dl.name) } }
 * }.bind(lifecycleOwner)
 * ```
 */
fun <T : Stable> DeltaList<T>.notifierForStable(
    context: Context,
    build: NotifierBuilder<T, T>.() -> Unit,
): DeltaNotifier<T, T> {
    val appContext = context.applicationContext
    val builder = NotifierBuilder<T, T>().apply(build)
    return DeltaNotifier(appContext, this, builder.toConfig(), builder.toOptions(), builder.toSummary(), valueOf = { it })
}

/**
 * DSL for configuring a [DeltaNotifier].
 *
 * @param E the list element type (a [StableItem] wrapper or a self-identifying [Stable] type).
 * @param T the notification payload type fed to [content] and the interaction handlers.
 */
class NotifierBuilder<E : Stable, T> internal constructor() {
    private val channels = mutableListOf<ChannelSpec>()
    private var tag: String = "deltalist"
    private var idBase: Int = 0
    private var groupKey: String? = null
    private var groupSummary: (NotificationSummaryScope.(List<E>) -> Notification)? = null
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
        summary: (NotificationSummaryScope.(items: List<E>) -> Notification)? = null,
    ) {
        groupKey = key
        groupSummary = summary
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
        return NotifierConfig(tag, idBase, channels.toList(), map, groupKey, onDismiss, onContentTap, onAction)
    }

    internal fun toSummary(): (NotificationSummaryScope.(List<E>) -> Notification)? = groupSummary

    internal fun toOptions(): NotifierOptions<T> =
        NotifierOptions(cancelOnUnbind, rateLimitPerSecond, stateAccessor, stateInitial)
}

internal class ChannelSpec(
    val id: String,
    val name: CharSequence,
    val importance: Int,
    val configure: NotificationChannelCompat.Builder.() -> Unit,
)

internal class NotifierConfig<T>(
    val tag: String,
    val idBase: Int,
    val channels: List<ChannelSpec>,
    val content: NotificationScope<T>.(T) -> Notification,
    val groupKey: String?,
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
class DeltaNotifier<E : Stable, T> internal constructor(
    context: Context,
    private val deltaList: DeltaList<E>,
    private val config: NotifierConfig<T>,
    private val options: NotifierOptions<T>,
    summary: (NotificationSummaryScope.(List<E>) -> Notification)?,
    private val valueOf: (E) -> T,
) {
    private val sink: NotificationSink<E, T> = ManagerNotificationSink(context, config, summary)
    private val grouped: Boolean = summary != null

    private var controller: TrayController<E, T>? = null
    private var job: Job? = null

    fun bind(owner: LifecycleOwner): DeltaNotifier<E, T> {
        unbind()
        val scope = owner.lifecycleScope
        val controller = TrayController(
            scope = scope,
            sink = sink,
            grouped = grouped,
            stateAccessor = options.stateAccessor,
            stateInitial = options.stateInitial,
            rateLimitPerSecond = options.rateLimitPerSecond,
            valueOf = valueOf,
        )
        this.controller = controller
        NotifierRegistry.register(config.tag, this)
        job = scope.launch {
            deltaList.collect { delta: Delta<E> -> controller.applyDelta(delta) }
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
