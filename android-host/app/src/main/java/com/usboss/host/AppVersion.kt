package com.usboss.host

/**
 * Version identity for the host app. Shown in the UI and shared with connected
 * Linux clients so both ends of the bridge can report what they are running.
 */
object AppVersion {
    /** Marketing version, e.g. `0.2.1`. */
    val name: String = BuildConfig.VERSION_NAME

    /** Display form of [name], e.g. `v0.2.1`. */
    val label: String = "v$name"

    /** Git revision and build date, or null when built without git metadata. */
    val buildStamp: String? = when {
        BuildConfig.GIT_DESCRIBE == "unknown" -> null
        BuildConfig.BUILD_DATE == "unknown" -> BuildConfig.GIT_DESCRIBE
        else -> "${BuildConfig.GIT_DESCRIBE} (${BuildConfig.BUILD_DATE})"
    }

    /** One-line summary, e.g. `0.2.1 [v0.2.1-1-g1ee8932 (2026-08-24)]`. */
    val summary: String = buildStamp?.let { "$name [$it]" } ?: name
}
