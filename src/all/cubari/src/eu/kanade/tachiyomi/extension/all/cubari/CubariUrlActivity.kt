package eu.kanade.tachiyomi.extension.all.cubari

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

class CubariUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }

        val rawUrl = data.toString()

        // Convertimos a cubari:source/slug (lo que usa tu Hiirbaf)
        val proxyQuery = try {
            convertToCubariProxy(rawUrl)
        } catch (e: Exception) {
            Log.e("CubariUrlActivity", "Invalid Cubari link: $rawUrl", e)
            finish()
            return
        }

        // Intent estándar de Tachiyomi/Yokai para búsquedas directas
        val searchIntent = Intent("eu.kanade.tachiyomi.SEARCH").apply {
            putExtra("query", proxyQuery)
            putExtra("filter", packageName) // para filtrar solo tu extensión
        }

        try {
            startActivity(searchIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("CubariUrlActivity", "Tachiyomi/Yokai not found", e)
        }

        finish()
    }

    /**
     * Convierte cualquier URL válida en el formato:
     *      cubari:<source>/<slug>
     */
    private fun convertToCubariProxy(url: String): String {
        // Llamamos al método interno deepLinkHandler() del Hybrid
        val hybrid = CubariHybrid("all")
        val method = hybrid::class.java.getDeclaredMethod("safeDeepLink", String::class.java)
        method.isAccessible = true

        val (source, slug) = method.invoke(hybrid, url) as Pair<String, String>
        return "cubari:$source/$slug"
    }
}
