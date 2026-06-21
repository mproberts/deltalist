plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":demo-core"))
            implementation(libs.kotlinx.coroutines.core)

            // React via npm (consumed by JS source files)
            implementation(npm("react", "18.3.1"))
            implementation(npm("react-dom", "18.3.1"))

            // Virtualized list for the paginated demo (renders only visible rows so
            // off-screen placeholder cells don't trigger fetches — mirrors the lazy
            // List/LazyColumn/RecyclerView the iOS and Android demos use).
            implementation(npm("react-virtualized", "9.22.5"))

            // Babel for JSX support in JS source files
            implementation(devNpm("babel-loader", "9.2.1"))
            implementation(devNpm("@babel/core", "7.26.0"))
            implementation(devNpm("@babel/preset-react", "7.26.3"))
        }
    }
}
