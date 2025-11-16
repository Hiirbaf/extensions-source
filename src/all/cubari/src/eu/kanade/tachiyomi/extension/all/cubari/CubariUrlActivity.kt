package eu.kanade.tachiyomi.extension.all.cubari

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

class CubariUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val host = uri?.host
        val pathSegments = uri?.pathSegments

        if (host == null || pathSegments == null) {
            Log.e("CubariUrlActivity", "Invalid URI: $intent")
            finish()
            return
        }

        // Convert URL to Cubari-compatible query
        val query = when {
            host.equals("m.imgur.com") || host.equals("imgur.com") ->
                fromSource("imgur", pathSegments)

            host.equals("m.reddit.com") || host.equals("reddit.com") || host.equals("www.reddit.com") ->
                fromSource("reddit", pathSegments)

            host.equals("imgchest.com") ->
                fromSource("imgchest", pathSegments)

            host.equals("catbox.moe") || host.equals("www.catbox.moe") ->
                fromSource("catbox", pathSegments)

            else ->
                fromCubari(pathSegments)
        }

        if (query == null) {
            Log.e("CubariUrlActivity", "Unable to parse URI: $uri")
            finish()
            return
        }

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.SEARCH"
            putExtra("query", query)
            putExtra("filter", packageName)
        }

        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("CubariUrlActivity", "Unable to start SEARCH", e)
        }

        // NO exitProcess()
        finish()
    }

    private fun fromSource(source: String, pathSegments: List<String>): String? {
        return if (pathSegments.size >= 2) {
            val id = pathSegments[1]
            "${Cubari.PROXY_PREFIX}$source/$id"
        } else null
    }

    private fun fromCubari(pathSegments: List<String>): String? {
        return if (pathSegments.size >= 3) {
            val source = pathSegments[1]
            val slug = pathSegments[2]
            "${Cubari.PROXY_PREFIX}$source/$slug"
        } else null
    }
}
