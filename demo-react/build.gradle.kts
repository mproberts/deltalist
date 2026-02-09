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

            // Babel for JSX support in JS source files
            implementation(devNpm("babel-loader", "9.2.1"))
            implementation(devNpm("@babel/core", "7.26.0"))
            implementation(devNpm("@babel/preset-react", "7.26.3"))
        }
    }
}
