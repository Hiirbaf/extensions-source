package eu.kanade.tachiyomi.extension.all.cubari

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class CubariUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // -----------------------------
        // FIX MÍNIMO (1 línea real):
        // -----------------------------
        val decodedUrl = Uri.decode(intent?.data.toString())
        // -----------------------------

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.SEARCH"
            putExtra("query", decodedUrl)   // <-- usamos la URL decodificada
            putExtra("filter", packageName)
        }

        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e("CubariUrlActivity", "Unable to find activity", e)
        }

        finish()
        exitProcess(0)
    }
}
