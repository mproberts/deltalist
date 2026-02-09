import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

export function mount(app) {
    const root = createRoot(document.getElementById('root'));
    root.render(<App app={app} />);
}
