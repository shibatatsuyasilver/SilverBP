package com.silverbp.android.exercise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.silverbp.android.MainActivity
import com.silverbp.android.R
import com.silverbp.android.coach.CoachNotifier
import com.silverbp.android.ui.nav.Routes

/**
 * Foreground notification for an active exercise session.
 *
 * Surfaces it serves:
 *   - Lock screen (VISIBILITY_PUBLIC) — stats + route thumbnail without unlock.
 *   - Notification shade (collapsed + expanded) — route polyline as big
 *     picture on Android < 16; segmented progress bar on Android 16+.
 *   - OriginOS 6 / Pixel / Samsung Atomic Island (Android 16+ only) via
 *     [NotificationCompat.ProgressStyle].
 *
 * Channel is created once via [createChannel] in Application.onCreate; the
 * service rebuilds the notification every second so live stats refresh.
 * Bitmap re-render is cached and only re-fires when the route changes or a
 * 5 s fallback elapses — see [obtainBitmaps].
 *
 * Channel-level lock-screen visibility is intentionally NOT overridden:
 * builder-level [NotificationCompat.Builder.setVisibility] is honored by
 * stock Android, and bumping the channel ID to reset defaults would orphan
 * any per-user customisations in system settings.
 */
object ExerciseNotification {

    const val CHANNEL_ID = "exercise_tracking"
    const val NOTIF_ID = 4242

    // Distinct request codes so FLAG_UPDATE_CURRENT updates the right slot.
    private const val RC_OPEN = 1
    private const val RC_PAUSE = 2
    private const val RC_RESUME = 3
    private const val RC_STOP = 4

    private const val BIG_PICTURE_MAX_WIDTH_PX = 1024
    private const val BIG_PICTURE_ASPECT = 0.5f             // 2:1
    private const val THUMB_SIZE_PX = 128
    private const val FORCE_REFRESH_INTERVAL_MS = 5_000L

    private data class CachedBitmaps(
        val pointCount: Int,
        val runStateOrd: Int,
        val widthPx: Int,
        val bigBmp: Bitmap,
        val thumbBmp: Bitmap,
        val renderedAtMs: Long,
    )
    @Volatile private var cached: CachedBitmaps? = null

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.exercise_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.exercise_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun build(ctx: Context, live: SessionLive?): Notification {
        val title = buildTitle(ctx, live)
        val body = buildBody(ctx, live)
        val (bigBmp, thumbBmp) = obtainBitmaps(ctx, live)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openSessionPendingIntent(ctx))

        if (thumbBmp != null) builder.setLargeIcon(thumbBmp)

        addToggleAction(ctx, builder, live)
        addStopAction(ctx, builder)

        // Style choice:
        //  - API 36+ : ProgressStyle so OriginOS 6 / Pixel / Samsung surface
        //    the notification in their respective OEM Live-Update island. The
        //    big route picture is sacrificed; full route is one tap away.
        //  - API < 36: BigPictureStyle with route polyline as big image.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && live != null) {
            // OEM islands compact the notification to a short label + tracker
            // icon — the duration (mm:ss, ~5 chars) is the most useful glance
            // value and fits the suggested 7-char budget.
            builder.setShortCriticalText(ExerciseMath.formatDuration(live.activeDurationMillis))
            applyProgressStyle(builder, live, thumbBmp, title, buildSummary(ctx, live))
        } else if (bigBmp != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigBmp)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    .setSummaryText(buildSummary(ctx, live)),
            )
        }

        return builder.build()
    }

    // ─── Title / body / summary ───────────────────────────────────────────

    private fun buildTitle(ctx: Context, live: SessionLive?): String {
        if (live == null) return ctx.getString(R.string.exercise_recording_title)
        val base = when (live.kind) {
            ActivityKind.Walking -> ctx.getString(R.string.exercise_notification_title_walk)
            ActivityKind.Running -> ctx.getString(R.string.exercise_notification_title_run)
        }
        val pausedSuffix = if (live.runState == RunState.Paused || live.runState == RunState.AutoPaused) {
            ctx.getString(R.string.exercise_notification_paused_suffix)
        } else ""
        return base + pausedSuffix
    }

    private fun buildBody(ctx: Context, live: SessionLive?): String {
        if (live == null) return ctx.getString(R.string.exercise_recording_starting)
        if (live.routePoints.isEmpty() && live.runState == RunState.Running) {
            return ctx.getString(R.string.exercise_recording_starting)
        }
        val dist = ExerciseMath.formatDistance(live.accumulatedDistanceMeters)
        val time = ExerciseMath.formatDuration(live.activeDurationMillis)
        val pace = live.paceSecPerKm
        return if (pace != null) {
            ctx.getString(R.string.exercise_recording_body_with_pace, dist, time, ExerciseMath.formatPace(pace))
        } else {
            ctx.getString(R.string.exercise_recording_body, dist, time)
        }
    }

    private fun buildSummary(ctx: Context, live: SessionLive?): String {
        if (live == null) return ""
        val pace = ExerciseMath.formatPace(live.paceSecPerKm)
        val steps = live.stepCount
        return if (steps != null && steps > 0) {
            ctx.getString(R.string.exercise_notification_big_summary_format, pace, steps)
        } else {
            ctx.getString(R.string.exercise_notification_big_summary_no_steps, pace)
        }
    }

    // ─── Bitmap cache ──────────────────────────────────────────────────────

    private fun obtainBitmaps(ctx: Context, live: SessionLive?): Pair<Bitmap?, Bitmap?> {
        if (live == null) return null to null
        val dm = ctx.resources.displayMetrics
        val widthPx = minOf(dm.widthPixels, BIG_PICTURE_MAX_WIDTH_PX)
        val heightPx = (widthPx * BIG_PICTURE_ASPECT).toInt().coerceAtLeast(1)
        val now = android.os.SystemClock.uptimeMillis()

        val prev = cached
        if (prev != null &&
            prev.pointCount == live.routePoints.size &&
            prev.runStateOrd == live.runState.ordinal &&
            prev.widthPx == widthPx &&
            (now - prev.renderedAtMs) < FORCE_REFRESH_INTERVAL_MS
        ) {
            return prev.bigBmp to prev.thumbBmp
        }

        val bigBmp = RouteBitmapRenderer.render(
            live.routePoints,
            RouteBitmapRenderer.Params(
                widthPx = widthPx,
                heightPx = heightPx,
                density = dm.density,
                kind = live.kind,
                runState = live.runState,
            ),
        )
        val thumbBmp = RouteBitmapRenderer.renderThumbnail(
            points = live.routePoints,
            sizePx = THUMB_SIZE_PX,
            density = dm.density,
            kind = live.kind,
            runState = live.runState,
        )

        // The previously cached bitmaps were handed to the prior notification
        // post; once we hand the new ones to NotificationManager below, the
        // system releases its references to the old ones and we can free.
        prev?.let {
            if (!it.bigBmp.isRecycled) it.bigBmp.recycle()
            if (!it.thumbBmp.isRecycled) it.thumbBmp.recycle()
        }
        cached = CachedBitmaps(
            pointCount = live.routePoints.size,
            runStateOrd = live.runState.ordinal,
            widthPx = widthPx,
            bigBmp = bigBmp,
            thumbBmp = thumbBmp,
            renderedAtMs = now,
        )
        return bigBmp to thumbBmp
    }

    // ─── Actions ──────────────────────────────────────────────────────────

    private fun addToggleAction(ctx: Context, builder: NotificationCompat.Builder, live: SessionLive?) {
        when (live?.runState) {
            RunState.Running -> builder.addAction(
                android.R.drawable.ic_media_pause,
                ctx.getString(R.string.exercise_notification_action_pause),
                servicePi(ctx, LocationTrackingService.ACTION_PAUSE, RC_PAUSE),
            )
            RunState.Paused, RunState.AutoPaused -> builder.addAction(
                android.R.drawable.ic_media_play,
                ctx.getString(R.string.exercise_notification_action_resume),
                servicePi(ctx, LocationTrackingService.ACTION_RESUME, RC_RESUME),
            )
            else -> Unit
        }
    }

    private fun addStopAction(ctx: Context, builder: NotificationCompat.Builder) {
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            ctx.getString(R.string.exercise_notification_action_stop),
            servicePi(ctx, LocationTrackingService.ACTION_STOP, RC_STOP),
        )
    }

    private fun servicePi(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, LocationTrackingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openSessionPendingIntent(ctx: Context): PendingIntent {
        // Reuse CoachNotifier's deep-link extra key so the existing
        // MainActivity.forwardDeepLink → DeepLinkBus → AppNavHost wiring
        // applies without changes. The constant name is misleading for an
        // exercise deep link but a rename is out of scope.
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(CoachNotifier.EXTRA_COACH_ROUTE, Routes.EXERCISE_SESSION)
        }
        return PendingIntent.getActivity(
            ctx, RC_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ─── Live Updates (Android 16+ / OriginOS 6 Atomic Island) ────────────

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun applyProgressStyle(
        builder: NotificationCompat.Builder,
        live: SessionLive,
        thumb: Bitmap?,
        title: String,
        summary: String,
    ) {
        // Single segment representing the current kilometre. The tracker
        // icon (= small route thumbnail) moves along the bar as distance
        // accumulates; once the user passes a km, the bar resets to 0.
        val nextKmProgress = ((live.accumulatedDistanceMeters % 1000.0) / 10.0)
            .toInt()
            .coerceIn(1, 100)
        val strokeColor = RouteBitmapRenderer.strokeColorFor(live.kind, live.runState) or 0xFF000000.toInt()
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(strokeColor)))
            .setProgress(nextKmProgress)
            .setProgressIndeterminate(false)
        if (thumb != null) {
            style.setProgressTrackerIcon(IconCompat.createWithBitmap(thumb))
        }
        builder.setStyle(style)
    }
}
