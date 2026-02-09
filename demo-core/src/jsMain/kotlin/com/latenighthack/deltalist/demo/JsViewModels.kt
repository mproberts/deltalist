package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DragState
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.operators.mapItems
import com.latenighthack.deltalist.softGetOrNull
import com.latenighthack.deltalist.softLoadedCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private fun Item.toJs(): dynamic {
    val obj = js("({})")
    obj.id = id
    obj.title = title
    return obj
}

private fun SectionRow.toJs(): dynamic {
    val obj = js("({})")
    when (this) {
        is SectionRow.Header -> {
            obj.type = "header"
            obj.title = header.title
            obj.color = "#${header.color.toString(16).padStart(8, '0').substring(2)}"
            obj.id = null
        }
        is SectionRow.ItemRow -> {
            obj.type = "item"
            obj.title = item.title
            obj.color = null
            obj.id = item.id
        }
    }
    return obj
}

private fun DragState<Item>.toJs(): dynamic {
    val obj = js("({})")
    when (this) {
        is DragState.Idle -> {
            obj.state = "idle"
            obj.itemTitle = null
            obj.fromIndex = -1
            obj.toIndex = -1
        }
        is DragState.Dragging -> {
            obj.state = "dragging"
            obj.itemTitle = item.title
            obj.fromIndex = fromIndex
            obj.toIndex = previewIndex
        }
        is DragState.Committing -> {
            obj.state = "committing"
            obj.itemTitle = item.title
            obj.fromIndex = fromIndex
            obj.toIndex = toIndex
        }
    }
    return obj
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsListViewModel {
    private val vm = ListViewModel()

    val items: Any = vm.items.mapItems { it.toJs() }

    fun addItem() = vm.addItem()
    fun removeItem(index: Int) = vm.removeItem(index)
    fun insertBefore(index: Int) = vm.insertBefore(index)
    fun insertAfter(index: Int) = vm.insertAfter(index)
    fun batchAdd() = vm.batchAdd()
    fun clear() = vm.clear()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsSectionedListViewModel {
    private val vm = SectionedListViewModel()

    val rows: Any = vm.flattenedSections.mapItems { it.toJs() }

    fun addSection() = vm.addSection()
    fun removeSection(index: Int) = vm.removeSection(index)
    fun addItemToSection(sectionIndex: Int) = vm.addItemToSection(sectionIndex)
    fun removeItemFromSection(sectionIndex: Int, itemIndex: Int) = vm.removeItemFromSection(sectionIndex, itemIndex)
    fun moveSectionUp(index: Int) = vm.moveSection(index, index - 1)
    fun moveSectionDown(index: Int) = vm.moveSection(index, index + 1)
    fun clearSections() = vm.clearSections()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsPaginatedListViewModel {
    private val vm = PaginatedListViewModel()
    private var latestItems: List<Any?>? = null

    @Suppress("UNCHECKED_CAST")
    val items: Any = vm.paginatedNumbers.onEach { delta ->
        latestItems = delta.items as List<Any?>
    }
    val loadingDirection: Any = vm.paginatedLoadingDirection.map { it?.name?.lowercase() }
    val loadedCount: Any = vm.paginatedLoadedCount
    val excludeDivisors: Any = vm.excludeDivisors.map { it.toTypedArray() }

    fun requestMore() {
        val items = latestItems ?: return
        val loaded = items.softLoadedCount()
        if (loaded >= items.size) return
        val soft = items.softGetOrNull(loaded)
        if (soft is SoftValue.NotLoaded) {
            soft.request()
        }
    }

    fun toggleDivisorFilter(divisor: Int) = vm.toggleDivisorFilter(divisor)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsDragDropViewModel {
    private val vm = DragDropViewModel()
    private val scope = CoroutineScope(SupervisorJob())

    val items: Any = vm.items.mapItems { it.toJs() }
    val dragState: Any = vm.items.dragState.map { it.toJs() }

    fun addItem() = vm.addItem()
    fun addPinnedItem() = vm.addPinnedItem()
    fun clear() = vm.clear()
    fun reset() = vm.reset()

    fun beginDrag(index: Int): Boolean = vm.items.beginDrag(index)
    fun updateDragPreview(toIndex: Int) = vm.items.updateDragPreview(toIndex)
    fun commitDrag() {
        scope.launch { vm.items.commitDrag() }
    }
    fun cancelDrag() = vm.items.cancelDrag()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
class JsDemoApp {
    val listViewModel = JsListViewModel()
    val sectionedListViewModel = JsSectionedListViewModel()
    val paginatedListViewModel = JsPaginatedListViewModel()
    val dragDropViewModel = JsDragDropViewModel()
}
