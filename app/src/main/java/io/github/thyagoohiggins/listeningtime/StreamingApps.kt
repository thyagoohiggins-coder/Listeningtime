package io.github.thyagoohiggins.listeningtime

/** Um serviço de streaming: nome exibido + cor fixa (validada para daltonismo no fundo escuro). */
data class StreamApp(val name: String, val color: Int)

/** Pacotes dos apps de streaming monitorados (celular e Android TV). */
object StreamingApps {
    // A ORDEM e as cores são fixas: cada app tem sempre a mesma cor nos gráficos.
    val apps: List<StreamApp> = listOf(
        StreamApp("Netflix", 0xFFE66767.toInt()),
        StreamApp("Prime Video", 0xFF3987E5.toInt()),
        StreamApp("YouTube", 0xFFC98500.toInt()),
        StreamApp("Apple TV", 0xFF199E70.toInt()),
        StreamApp("Crunchyroll", 0xFFD95926.toInt()),
        StreamApp("HBO Max", 0xFF9085E9.toInt()),
        StreamApp("Disney+", 0xFFD55181.toInt()),
    )

    val packages: Map<String, String> = mapOf(
        "com.netflix.mediaclient" to "Netflix",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.amazon.avod" to "Prime Video",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube",
        "com.google.android.apps.youtube.kids" to "YouTube",
        "app.revanced.android.youtube" to "YouTube",
        "com.wbd.stream" to "HBO Max",
        "com.hbo.hbonow" to "HBO Max",
        "com.hbo.max.android.tv" to "HBO Max",
        "com.hbo.go" to "HBO Max",
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.apple.atve.amazon.appletv" to "Apple TV",
        "com.apple.atve.sony.appletv" to "Apple TV",
        "com.apple.tv" to "Apple TV",
        "com.disney.disneyplus" to "Disney+",
        "com.disney.disneyplus.prod" to "Disney+",
    )

    fun isTarget(pkg: String?): Boolean = pkg != null && packages.containsKey(pkg)

    fun nameOf(pkg: String): String = packages[pkg] ?: pkg

    fun colorOf(name: String): Int = apps.firstOrNull { it.name == name }?.color ?: 0xFF8B98AE.toInt()
}
