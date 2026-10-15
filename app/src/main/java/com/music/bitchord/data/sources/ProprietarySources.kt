package com.music.bitchord.data.sources

/**
 * Handles sources that are proprietary/services with special handling.
 *
 * Currently only the Octave addon service falls here. These are addons where
 * the service itself is the product (not a self-hosted server), so we:
 * - Show a consistent branded name instead of the hostname
 * - Mask the URL in health details so the key isn't leaked
 * - Mask the label so the branded name shows everywhere
 *
 * This is deliberately narrow: only sources the app knows about at build
 * time get this treatment. A random self-hosted addon never hits this.
 */
object ProprietarySources {

    /** The display name used everywhere this service appears. */
    const val DISPLAY_NAME = "Octave"

    /** Known base URLs (and their variations) that identify the Octave service. */
    private val OCTAVE_BASES = listOf(
        "octave",               // e.g. https://octave.example.com
        "unified-addon",        // the reference deployment
    )

    fun isProprietaryUrl(url: String): Boolean {
        // Hand-rolled, not android.net.Uri: this runs in plain-JVM unit
        // tests too, where android stubs return null for everything.
        val host = url.trim().substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':')
            .takeIf { it.isNotBlank() } ?: return false
        return OCTAVE_BASES.any { host.contains(it, ignoreCase = true) }
    }

    /**
     * Whether a display name or label belongs to the Octave service.
     *
     * The display name might already be "Octave" or "Unified · Quality First"
     * (the reference instance's manifest name). We treat those as proprietary.
     */
    private fun isProprietaryName(name: String): Boolean =
        name.equals("Octave", ignoreCase = true) ||
            name.equals("Unified · Quality First", ignoreCase = true) ||
            name.contains("Octave", ignoreCase = true)

    /**
     * Whether the given string (URL or display name) identifies the Octave service.
     */
    fun isProprietary(input: String): Boolean = isProprietaryUrl(input) || isProprietaryName(input)

    /**
     * Whether this source config is for a proprietary service.
     */
    fun isProprietary(baseUrl: String, label: String): Boolean =
        isProprietary(baseUrl) || isProprietary(label)

    /**
     * The masked label shown in the sources list and on the card.
     *
     * Always shows the branded name so a hostname never appears.
     */
    fun maskLabel(baseUrl: String, displayName: String): String =
        if (isProprietary(baseUrl)) DISPLAY_NAME else displayName

    /**
     * The health detail shown on the source row.
     *
     * For proprietary sources we show the branded name + version instead of
     * the raw hostname + version, so an API key in the hostname never leaks.
     */
    fun maskHealthDetail(baseUrl: String, displayName: String?, version: String?): String? {
        if (!isProprietary(baseUrl)) return null
        val name = DISPLAY_NAME
        return if (version?.isNotBlank() == true) "$name · v$version" else name
    }
}