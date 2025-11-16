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

        val dataUri = intent?.data
        if (dataUri == null) {
            finish()
            return
        }

        val rawUrl = dataUri.toString()

        // Convertimos el enlace a cubari:<source>/<slug>
        val proxyQuery = try {
            convertToCubariPrefix(rawUrl)
        } catch (e: Exception) {
            Log.e("CubariUrlActivity", "Invalid Cubari URL", e)
            finish()
            return
        }

        val tachiyomiIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.SEARCH"
            putExtra("query", proxyQuery)
            putExtra("filter", packageName)
        }

        try {
            startActivity(tachiyomiIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("CubariUrlActivity", "Unable to find Tachiyomi activity", e)
        }

        finish()
    }

    /**
     * Convierte enlaces directos a Cubari o enlaces externos
     * a un prefijo estándar: cubari:<source>/<slug>
     */
    private fun convertToCubariPrefix(url: String): String {
        val (source, slug) = CubariHybrid("all").run {
            val method = this::class.java.getDeclaredMethod("safeDeepLink", String::class.java)
            method.isAccessible = true
            method.invoke(this, url) as Pair<String, String>
        }

        return "${CubariHybrid.PROXY_PREFIX}$source/$slug"
    }
}
