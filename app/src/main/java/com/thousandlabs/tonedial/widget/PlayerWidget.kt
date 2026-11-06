package com.thousandlabs.tonedial.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.thousandlabs.tonedial.MainActivity
import com.thousandlabs.tonedial.R
import com.thousandlabs.tonedial.playback.PlaybackService

/** Widget d'écran d'accueil : titre en cours, précédent, lecture/pause, suivant. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val player = PlaybackService.instance?.player
        when (intent.action) {
            ACTION_PLAY -> player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == androidx.media3.common.Player.STATE_IDLE) it.prepare(); it.play() } }
            ACTION_NEXT -> player?.seekToNextMediaItem()
            ACTION_PREV -> player?.seekToPrevious()
        }
    }

    companion object {
        private const val ACTION_PLAY = "com.thousandlabs.tonedial.widget.PLAY"
        private const val ACTION_NEXT = "com.thousandlabs.tonedial.widget.NEXT"
        private const val ACTION_PREV = "com.thousandlabs.tonedial.widget.PREV"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PlayerWidget::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            if (ids.isEmpty()) return

            val player = PlaybackService.instance?.player
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val meta = player?.mediaMetadata
            val title = meta?.title?.toString()?.takeIf { it.isNotBlank() && (player?.mediaItemCount ?: 0) > 0 }
            views.setTextViewText(R.id.widget_title, title ?: "Tone Dial")
            views.setTextViewText(
                R.id.widget_artist,
                if (title != null) meta?.artist?.toString() ?: "" else "Appuyez pour ouvrir",
            )
            views.setImageViewResource(
                R.id.widget_play,
                if (player?.isPlaying == true) R.drawable.ic_w_pause else R.drawable.ic_w_play,
            )

            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            if (player != null && player.mediaItemCount > 0) {
                views.setOnClickPendingIntent(R.id.widget_play, broadcast(context, ACTION_PLAY, 11))
                views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 12))
                views.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV, 13))
            } else {
                views.setOnClickPendingIntent(R.id.widget_play, open)
                views.setOnClickPendingIntent(R.id.widget_next, open)
                views.setOnClickPendingIntent(R.id.widget_prev, open)
            }
            manager.updateAppWidget(component, views)
        }

        private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, PlayerWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
