// No package - exports at module root for clean JS imports

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.softGetOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@JsModule("react")
@JsNonModule
private external object ReactLib {
    fun useState(initialState: dynamic): Array<dynamic>
    fun useEffect(effect: dynamic, deps: Array<Any?> = definedExternally)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useDeltaList(deltaList: Any): Array<Any?> {
    val state = ReactLib.useState(js("[]"))
    val setItems = state[1]

    ReactLib.useEffect({
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val flow = deltaList as Flow<Delta<Any?>>

        scope.launch {
            flow.collect { delta ->
                val result = mutableListOf<Any?>()
                for (i in delta.items.indices) {
                    when (val soft = delta.items.softGetOrNull(i)) {
                        is SoftValue.Present -> result.add(soft.value)
                        is SoftValue.NotLoaded -> break
                        null -> break
                    }
                }
                setItems(result.toTypedArray())
            }
        }

        val cleanup: () -> Unit = { scope.cancel() }
        cleanup
    }, arrayOf(deltaList))

    return state[0].unsafeCast<Array<Any?>>()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useFlow(flow: Any, initial: Any? = null): Any? {
    val state = ReactLib.useState(initial)
    val setValue = state[1]

    ReactLib.useEffect({
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val kotlinFlow = flow as Flow<Any?>

        scope.launch {
            kotlinFlow.collect { value ->
                setValue(value)
            }
        }

        val cleanup: () -> Unit = { scope.cancel() }
        cleanup
    }, arrayOf(flow))

    return state[0]
}
