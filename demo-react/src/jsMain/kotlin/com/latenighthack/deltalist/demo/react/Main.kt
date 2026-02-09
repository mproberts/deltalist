package com.latenighthack.deltalist.demo.react

import com.latenighthack.deltalist.demo.JsDemoApp

@JsModule("app-entry")
@JsNonModule
external object AppEntry {
    fun mount(app: dynamic)
}

fun main() {
    AppEntry.mount(JsDemoApp())
}
