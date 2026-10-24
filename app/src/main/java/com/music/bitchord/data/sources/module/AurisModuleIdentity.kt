package com.music.bitchord.data.sources.module

/**
 * What an Auris-locked module checks before it will run.
 *
 * [ID] alone is not enough to be a lock: any host can define a global with an
 * id. [KEY] is a value only this build hands to a module sandbox, so a module
 * that requires it fails to initialise — and fails every call — in a host that
 * has the shape but not the secret.
 *
 * The key is compiled into the APK and therefore extractable by someone who
 * deliberately reverse-engineers this app. That is the ceiling for a lock whose
 * code runs on the other side's machine: it stops every unmodified host and
 * every cached copy of the addon, not a determined patcher. The modules
 * themselves are obfuscated so finding the check is work rather than a grep.
 */
internal object AurisModuleIdentity {
    const val ID = "auris"
    const val NAME = "Auris"
    const val WEBSITE = "https://auris.shehriyar.ink"

    /** XOR-packed so the key is not a readable string in the APK. */
    private val PACKED_KEY = intArrayOf(
        0x3F, 0x04, 0x44, 0xE5, 0x6C, 0x82, 0x09, 0x2B, 0x3C, 0x55, 0x4D, 0xE0, 0x3D, 0x80, 0x5F, 0x2F,
        0x6A, 0x57, 0x4C, 0xB1, 0x3C, 0xD5, 0x0D, 0x7B, 0x6C, 0x50, 0x1F, 0xB7, 0x38, 0xD5, 0x53, 0x29,
        0x3B, 0x07, 0x4D, 0xEA, 0x68, 0xD5, 0x0D, 0x2D, 0x62, 0x09, 0x1F, 0xE5, 0x3C, 0xD1, 0x58, 0x2E,
        0x62, 0x53, 0x1F, 0xEA, 0x3D, 0xD3, 0x0A, 0x7E, 0x6A, 0x08, 0x4C, 0xE0, 0x6A, 0xD3, 0x0E, 0x2C,
    )
    private val MASK = intArrayOf(0x5A, 0x31, 0x7C, 0xD2, 0x09, 0xE4, 0x6B, 0x18)

    private val KEY: String by lazy {
        buildString(PACKED_KEY.size) {
            for (i in PACKED_KEY.indices) {
                append(((PACKED_KEY[i] xor MASK[i % MASK.size]) and 0xFF).toChar())
            }
        }
    }

    /** JSON object literal handed to every module as its `context.app`. */
    val json: String by lazy {
        """{"id":"$ID","key":"$KEY","name":"$NAME","website":"$WEBSITE"}"""
    }
}
