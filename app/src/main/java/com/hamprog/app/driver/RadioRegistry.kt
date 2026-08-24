package com.hamprog.app.driver

import com.hamprog.app.driver.baofeng.BaofengUv5rDriver

/**
 * Central list of supported radios. To add a new radio:
 *   1. Implement [RadioDriver] in its own package (see driver/baofeng for a
 *      worked example).
 *   2. Add an instance of it to [all] below.
 * The rest of the app (UI, USB layer, channel model) needs no changes.
 */
object RadioRegistry {
    val all: List<RadioDriver> = listOf(
        BaofengUv5rDriver(),
        // Family variants that share the UV-5R protocol/memory map:
        BaofengUv5rDriver(modelOverrideName = "Baofeng UV-82"),
        BaofengUv5rDriver(modelOverrideName = "Baofeng BF-888S"),
        BaofengUv5rDriver(modelOverrideName = "Radioddity/Retevis (UV-5R clones)"),

        // -------------------------------------------------------------
        // Not yet implemented. These stubs show where Kenwood/Yaesu/Icom
        // support goes; each needs its own protocol implementation since
        // none of them share a wire format with the Baofeng family or
        // with each other. See README.md "Adding a new radio" section.
        // -------------------------------------------------------------
        // YaesuFt60Driver(),
        // IcomCiVDriver(),
        // KenwoodTk3402Driver(),
    )
}
