package app.tempo.music

import android.content.Context
import android.net.ConnectivityManager

object NetworkState {
    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }
}
