import React, { useState, useRef, useCallback, useEffect } from 'react';
import { useDeltaList, useFlow } from 'demo-core';

// --- Basic List Demo ---

function BasicListDemo({ vm }) {
    const items = useDeltaList(vm.items);
    const [selectedIndex, setSelectedIndex] = useState(-1);

    return (
        <div className="demo-panel">
            <div className="button-bar">
                <button onClick={() => vm.addItem()}>Add</button>
                <button onClick={() => vm.batchAdd()}>Batch Add</button>
                <button onClick={() => vm.clear()}>Clear</button>
            </div>
            {selectedIndex >= 0 && selectedIndex < items.length && (
                <div className="button-bar">
                    <button onClick={() => vm.insertBefore(selectedIndex)}>Insert Before</button>
                    <button onClick={() => vm.insertAfter(selectedIndex)}>Insert After</button>
                    <button onClick={() => { vm.removeItem(selectedIndex); setSelectedIndex(-1); }}>Remove</button>
                </div>
            )}
            <ul className="item-list">
                {items.map((item, index) => (
                    <li
                        key={item.id}
                        className={`item-card ${index === selectedIndex ? 'selected' : ''}`}
                        onClick={() => setSelectedIndex(index === selectedIndex ? -1 : index)}
                    >
                        <span className="item-title">{item.title}</span>
                        <span className="item-id">ID: {item.id.substring(0, 8)}...</span>
                    </li>
                ))}
            </ul>
            {items.length === 0 && <div className="empty-state">No items. Click "Add" to get started.</div>}
        </div>
    );
}

// --- Sectioned List Demo ---

function SectionedListDemo({ vm }) {
    const rows = useDeltaList(vm.rows);
    const [selectedSection, setSelectedSection] = useState(-1);

    // Count sections for index mapping
    const sectionIndices = [];
    rows.forEach((row, i) => {
        if (row && row.type === 'header') sectionIndices.push(i);
    });

    return (
        <div className="demo-panel">
            <div className="button-bar">
                <button onClick={() => vm.addSection()}>+ Section</button>
                <button onClick={() => { if (selectedSection >= 0) { vm.removeSection(selectedSection); setSelectedSection(-1); } }} disabled={selectedSection < 0}>- Section</button>
                <button onClick={() => vm.clearSections()}>Clear</button>
            </div>
            {selectedSection >= 0 && (
                <div className="button-bar">
                    <button onClick={() => vm.addItemToSection(selectedSection)}>+ Item</button>
                    <button onClick={() => vm.removeItemFromSection(selectedSection, 0)} disabled={selectedSection < 0}>- Item</button>
                    <button onClick={() => { vm.moveSectionUp(selectedSection); setSelectedSection(Math.max(0, selectedSection - 1)); }} disabled={selectedSection <= 0}>Move Up</button>
                    <button onClick={() => { vm.moveSectionDown(selectedSection); setSelectedSection(Math.min(sectionIndices.length - 1, selectedSection + 1)); }} disabled={selectedSection >= sectionIndices.length - 1}>Move Down</button>
                </div>
            )}
            <ul className="item-list">
                {rows.map((row, index) => {
                    if (!row) return null;
                    if (row.type === 'header') {
                        const sectionIdx = sectionIndices.indexOf(index);
                        return (
                            <li
                                key={`header-${index}`}
                                className={`section-header ${sectionIdx === selectedSection ? 'selected' : ''}`}
                                style={{ backgroundColor: row.color, color: '#fff' }}
                                onClick={() => setSelectedSection(sectionIdx === selectedSection ? -1 : sectionIdx)}
                            >
                                {row.title}
                            </li>
                        );
                    } else {
                        return (
                            <li key={row.id || `item-${index}`} className="section-item">
                                <span className="item-title">{row.title}</span>
                                {row.id && <span className="item-id">ID: {row.id.substring(0, 8)}...</span>}
                            </li>
                        );
                    }
                })}
            </ul>
            {rows.length === 0 && <div className="empty-state">No sections. Click "+ Section" to get started.</div>}
        </div>
    );
}

// --- Paginated List Demo ---

function PaginatedListDemo({ vm }) {
    const items = useDeltaList(vm.items);
    const loadingDirection = useFlow(vm.loadingDirection, null);
    const loadedCount = useFlow(vm.loadedCount, 0);
    const excludeDivisors = useFlow(vm.excludeDivisors, []);
    const sentinelRef = useRef(null);

    const hasMore = loadedCount < 10000;

    // Trigger loading when the sentinel element becomes visible
    useEffect(() => {
        const el = sentinelRef.current;
        if (!el) return;
        const observer = new IntersectionObserver(([entry]) => {
            if (entry.isIntersecting) {
                vm.requestMore();
            }
        }, { threshold: 0 });
        observer.observe(el);
        return () => observer.disconnect();
    }, [vm, items.length]);

    const divisors = [2, 3, 5, 7, 11];

    return (
        <div className="demo-panel">
            <div className="status-bar">
                <span>Paginated List (10,000 items)</span>
                {loadingDirection && <span className="loading-badge">Loading: {loadingDirection}</span>}
            </div>
            <div className="status-bar">
                <span>Loaded: {loadedCount} | Showing: {items.length}</span>
            </div>
            <ul className="item-list paginated-list">
                {items.map((item, index) => (
                    <li key={index} className="item-card">
                        <span className="item-title">#{item}</span>
                        <span className="item-id">index: {index}</span>
                    </li>
                ))}
                {hasMore && (
                    <li ref={sentinelRef} className="item-card placeholder">
                        <span className="item-title placeholder-text">Loading more...</span>
                    </li>
                )}
            </ul>
            <div className="filter-bar">
                <span>Exclude divisors of:</span>
                {divisors.map(d => (
                    <label key={d} className="filter-checkbox">
                        <input
                            type="checkbox"
                            checked={excludeDivisors && excludeDivisors.includes(d)}
                            onChange={() => vm.toggleDivisorFilter(d)}
                        />
                        {d}
                    </label>
                ))}
            </div>
        </div>
    );
}

// --- Drag & Drop Demo ---

function DragDropDemo({ vm }) {
    const items = useDeltaList(vm.items);
    const dragState = useFlow(vm.dragState, { state: 'idle', itemTitle: null, fromIndex: -1, toIndex: -1 });
    const dragItemRef = useRef(null);

    const handleDragStart = useCallback((e, index) => {
        const success = vm.beginDrag(index);
        if (!success) {
            e.preventDefault();
            return;
        }
        dragItemRef.current = index;
        e.dataTransfer.effectAllowed = 'move';
    }, [vm]);

    const handleDragOver = useCallback((e, index) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        if (dragItemRef.current !== null && dragItemRef.current !== index) {
            vm.updateDragPreview(index);
        }
    }, [vm]);

    const handleDrop = useCallback((e) => {
        e.preventDefault();
        vm.commitDrag();
        dragItemRef.current = null;
    }, [vm]);

    const handleDragEnd = useCallback(() => {
        if (dragItemRef.current !== null) {
            vm.cancelDrag();
            dragItemRef.current = null;
        }
    }, [vm]);

    const state = dragState || { state: 'idle' };
    const statusText = state.state === 'idle'
        ? 'Drag items to reorder'
        : state.state === 'dragging'
            ? `Dragging "${state.itemTitle}" (${state.fromIndex} \u2192 ${state.toIndex})`
            : `Committing move of "${state.itemTitle}"...`;

    return (
        <div className="demo-panel">
            <div className={`status-bar drag-status ${state.state}`}>
                {statusText}
            </div>
            <div className="button-bar">
                <button onClick={() => vm.addItem()}>Add</button>
                <button onClick={() => vm.addPinnedItem()}>Add Pinned</button>
                <button onClick={() => vm.clear()}>Clear</button>
                <button onClick={() => vm.reset()}>Reset</button>
            </div>
            <ul className="item-list drag-list">
                {items.map((item, index) => {
                    if (!item) return null;
                    const isPinned = item.title.toLowerCase().includes('pinned');
                    return (
                        <li
                            key={item.id}
                            className={`item-card ${isPinned ? 'pinned' : 'draggable'}`}
                            draggable={!isPinned}
                            onDragStart={(e) => handleDragStart(e, index)}
                            onDragOver={(e) => handleDragOver(e, index)}
                            onDrop={handleDrop}
                            onDragEnd={handleDragEnd}
                        >
                            {!isPinned && <span className="drag-handle">&#x2630;</span>}
                            <span className="item-title">{item.title}</span>
                            <span className="item-subtitle">{isPinned ? 'Cannot be moved' : 'Drag to reorder'}</span>
                        </li>
                    );
                })}
            </ul>
            {items.length === 0 && <div className="empty-state">No items. Click "Add" to get started.</div>}
        </div>
    );
}

// --- Main App with Tabs ---

const TABS = [
    { key: 'list', label: 'Basic List' },
    { key: 'paginated', label: 'Paginated' },
    { key: 'sectioned', label: 'Sectioned' },
    { key: 'dragdrop', label: 'Drag & Drop' },
];

export default function App({ app }) {
    const [activeTab, setActiveTab] = useState('list');

    return (
        <div className="app">
            <h1>DeltaList React Demo</h1>
            <p className="subtitle">Reactive list library with efficient mutations</p>
            <nav className="tab-bar">
                {TABS.map(tab => (
                    <button
                        key={tab.key}
                        className={`tab ${activeTab === tab.key ? 'active' : ''}`}
                        onClick={() => setActiveTab(tab.key)}
                    >
                        {tab.label}
                    </button>
                ))}
            </nav>
            <div className="tab-content">
                {activeTab === 'list' && <BasicListDemo vm={app.listViewModel} />}
                {activeTab === 'paginated' && <PaginatedListDemo vm={app.paginatedListViewModel} />}
                {activeTab === 'sectioned' && <SectionedListDemo vm={app.sectionedListViewModel} />}
                {activeTab === 'dragdrop' && <DragDropDemo vm={app.dragDropViewModel} />}
            </div>
        </div>
    );
}
