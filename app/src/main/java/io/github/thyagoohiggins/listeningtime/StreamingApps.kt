package io.github.thyagoohiggins.listeningtime

/** Pacotes dos apps de streaming monitorados (celular e Android TV). */
object StreamingApps {
    val packages: Map<String, String> = linkedMapOf(
        // Netflix
        "com.netflix.mediaclient" to "Netflix",
        "com.netflix.ninja" to "Netflix",
        // Prime Video
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.amazon.avod" to "Prime Video",
        // YouTube
        "com.google.android.youtube" to "YouTube",
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.apps.youtube.kids" to "YouTube Kids",
        "app.revanced.android.youtube" to "YouTube",
        // HBO Max / Max
        "com.wbd.stream" to "HBO Max",
        "com.hbo.hbonow" to "HBO Max",
        "com.hbo.max.android.tv" to "HBO Max",
        "com.hbo.go" to "HBO Max",
        // Crunchyroll
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        // Apple TV
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.apple.atve.amazon.appletv" to "Apple TV",
        "com.apple.atve.sony.appletv" to "Apple TV",
        "com.apple.tv" to "Apple TV",
        // Disney+
        "com.disney.disneyplus" to "Disney+",
        "com.disney.disneyplus.prod" to "Disney+",
    )

    fun isTarget(pkg: String?): Boolean = pkg != null && packages.containsKey(pkg)

    fun nameOf(pkg: String): String = packages[pkg] ?: pkg

    /** Nomes únicos, na ordem, para exibir as estatísticas. */
    val displayNames: List<String> get() = packages.values.distinct()
}
