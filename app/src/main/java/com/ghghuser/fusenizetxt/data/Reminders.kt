package com.ghghuser.fusenizetxt.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ghghuser.fusenizetxt.FusenApp
import com.ghghuser.fusenizetxt.R
import com.ghghuser.fusenizetxt.ui.EditActivity
import java.security.MessageDigest
import kotlinx.coroutines.launch
import org.json.JSONObject

/** メモごとのリマインダー。repeat: 0=1回だけ 1=毎日 2=毎週 */
data class Reminder(val time: Long, val repeat: Int)

object Reminders {
    const val ACTION = "com.ghghuser.fusenizetxt.REMINDER"
    const val EXTRA_URI = "uri"
    private const val CHANNEL = "reminder"
    private val lock = Any()
    private const val DAY = 24L * 60 * 60 * 1000

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fusen_reminders", Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONObject = try {
        JSONObject(sp(ctx).getString("all", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun writeAll(ctx: Context, o: JSONObject) {
        sp(ctx).edit().putString("all", o.toString()).apply()
    }

    fun get(ctx: Context, uri: String): Reminder? = synchronized(lock) {
        readAll(ctx).optJSONObject(uri)?.let { Reminder(it.optLong("t"), it.optInt("r")) }
    }

    fun all(ctx: Context): Map<String, Reminder> = synchronized(lock) {
        val o = readAll(ctx)
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to Reminder(it.optLong("t"), it.optInt("r")) } }.toMap()
    }

    fun set(ctx: Context, uri: String, r: Reminder) {
        synchronized(lock) {
            val o = readAll(ctx)
            o.put(uri, JSONObject().put("t", r.time).put("r", r.repeat.coerceIn(0, 2)))
            writeAll(ctx, o)
        }
        schedule(ctx, uri, r.time)
    }

    fun clear(ctx: Context, uri: String) {
        synchronized(lock) {
            val o = readAll(ctx)
            if (o.has(uri)) {
                o.remove(uri)
                writeAll(ctx, o)
            }
        }
        cancel(ctx, uri)
    }

    /** ファイル名変更などでURIが変わった時に引き継ぐ */
    fun rename(ctx: Context, old: String, new: String) {
        if (old == new) return
        val r = get(ctx, old) ?: return
        clear(ctx, old)
        set(ctx, new, r)
    }

    /** 次回の時刻（繰り返しなら now より後まで進める。1回だけなら null） */
    fun nextAfter(r: Reminder, now: Long): Long? {
        val step = when (r.repeat) {
            1 -> DAY
            2 -> 7 * DAY
            else -> return null
        }
        var t = r.time
        if (t > now) return t
        t += ((now - t) / step + 1) * step
        return t
    }

    /** 再起動・アップデート後などに全アラームを登録し直す。過ぎている1回限りは今すぐ通知する */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for ((uri, r) in all(ctx)) {
            if (r.time > now) {
                schedule(ctx, uri, r.time)
            } else if (r.repeat == 0) {
                ctx.sendBroadcast(intentFor(ctx, uri))
            } else {
                val n = nextAfter(r, now) ?: continue
                set(ctx, uri, Reminder(n, r.repeat))
            }
        }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun intentFor(ctx: Context, uri: String): Intent =
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("fusen://reminder/" + key(uri)))
            .putExtra(EXTRA_URI, uri)

    private fun pending(ctx: Context, uri: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, intentFor(ctx, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun schedule(ctx: Context, uri: String, time: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, uri)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi) // 正確な時刻の許可がない場合は多少ずれる
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }

    private fun cancel(ctx: Context, uri: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, uri))
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "リマインダー", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun notifyNow(ctx: Context, uri: String) {
        ensureChannel(ctx)
        val u = Uri.parse(uri)
        val name = MemoRepo.displayName(ctx, u) ?: return
        val body = MemoRepo.readText(ctx, u).getOrNull()?.text?.trim()?.take(120)?.ifEmpty { null } ?: "メモを開く"
        val open = Intent(ctx, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_URI, uri)
            .setData(Uri.parse("fusen://open/" + key(uri)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(MemoRepo.displayTitle(name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) {
            try {
                nm.notify(key(uri).hashCode(), n)
            } catch (e: SecurityException) {
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION) return
        val uri = intent.getStringExtra(Reminders.EXTRA_URI) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        FusenApp.scope.launch {
            try {
                val r = Reminders.get(app, uri)
                if (r == null) return@launch
                if (!MemoRepo.exists(app, Uri.parse(uri))) {
                    Reminders.clear(app, uri)
                    return@launch
                }
                Reminders.notifyNow(app, uri)
                val next = Reminders.nextAfter(r, System.currentTimeMillis() + 1000)
                if (next != null) Reminders.set(app, uri, Reminder(next, r.repeat)) else Reminders.clear(app, uri)
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != Intent.ACTION_TIME_CHANGED && a != Intent.ACTION_TIMEZONE_CHANGED
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        FusenApp.scope.launch {
            try {
                Reminders.rescheduleAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
