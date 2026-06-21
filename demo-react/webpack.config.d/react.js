// Wires the hand-written JSX layer into the Kotlin/JS webpack build.
//
// Main.kt references the entry point via @JsModule("app-entry"), and the JSX
// files import "demo-core" / "deltalist-react" by friendly name. None of those
// are real npm packages, so map them to their files and teach webpack to run
// JSX through babel. __dirname here is build/js/packages/deltalist-demo-react.
const path = require('path');

const repoRoot = path.resolve(__dirname, '../../../..');

// This package's own node_modules holds the React copy the Kotlin code resolves
// to (via @JsModule("react")). The JSX sources live outside the package, so point
// resolution here too — otherwise they'd pick up a second React copy and every
// hook call would fail with "Invalid hook call" (two dispatchers, two Reacts).
const pkgModules = path.resolve(__dirname, 'node_modules');

config.resolve = config.resolve || {};
config.resolve.extensions = (config.resolve.extensions || []).concat(['.js', '.jsx']);
config.resolve.modules = (config.resolve.modules || []).concat([pkgModules]);
config.resolve.alias = Object.assign({}, config.resolve.alias, {
    // @JsModule("app-entry") in Main.kt -> JSX bootstrap that mounts React.
    'app-entry': path.resolve(repoRoot, 'demo-react/js/mount.jsx'),
    // App.jsx imports the hooks facade exposed by demo-core.
    'demo-core': path.resolve(repoRoot, 'demo-core/js/hooks.js'),
    // hooks.js re-exports the Kotlin-compiled React hooks.
    'deltalist-react': path.resolve(__dirname, 'kotlin/deltalist-deltalist-react.js'),
    // Force a single React instance across the Kotlin and JSX layers.
    'react': path.resolve(pkgModules, 'react'),
    'react-dom': path.resolve(pkgModules, 'react-dom'),
});

config.module.rules.push({
    test: /\.jsx$/,
    exclude: /node_modules/,
    use: {
        loader: 'babel-loader',
        options: {
            presets: ['@babel/preset-react'],
        },
    },
});
