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

    /**
     * Separate channel + ID for the idle-reminder heads-up notification. Lives
     * alongside (not replacing) the ongoing foreground notification — both are
     * shown when the user has been paused for [ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS].
     *
     * Importance must be HIGH (not LOW like the foreground channel) so the
     * heads-up overlay actually pops; channel importance is locked after
     * creation, which is why we can't reuse [CHANNEL_ID].
     */
    const val IDLE_REMINDER_CHANNEL_ID = "exercise_idle_reminder"
    const val IDLE_REMINDER_NOTIF_ID = 4243

    /**
     * Boolean Intent extra MainActivity reads to know that the Stop button on
     * the notification (lock screen or shade or Atomic Island) was pressed.
     * MainActivity calls [com.silverbp.android.di.ServiceLocator.exerciseController]
     * `.stop()` and deep-links to [Routes.EXERCISE_SUMMARY] so the user lands
     * on the save/discard screen instead of having the notification just
     * vanish.
     */
    const val EXTRA_STOP_AND_REVIEW = "exercise_stop_and_review"

    // Distinct request codes so FLAG_UPDATE_CURRENT updates the right slot.
    private const val RC_OPEN = 1
    private const val RC_PAUSE = 2
    private const val RC_RESUME = 3
    private const val RC_STOP = 4
    private const val RC_IDLE_CONTINUE = 5
    private const val RC_IDLE_STOP_REVIEW = 6

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

    /**
     * Creates the heads-up channel used by [buildIdleReminder]. Separate from
     * [CHANNEL_ID] because channel importance is locked after first creation
     * and the foreground channel is intentionally LOW (silent per-second
     * refresh); this one needs HIGH so the reminder actually surfaces.
     */
    fun createIdleReminderChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(IDLE_REMINDER_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            IDLE_REMINDER_CHANNEL_ID,
            ctx.getString(R.string.exercise_notification_idle_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.exercise_notification_idle_channel_desc)
            setShowBadge(false)
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Heads-up reminder shown when an active session has been continuously in
     * Paused / AutoPaused for [ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS].
     * Lives on its own channel + id so it co-exists with the ongoing
     * foreground notification (which keeps showing live stats). Two actions:
     *
     *  - "Keep going" → broadcasts [LocationTrackingService.ACTION_IDLE_CONTINUE],
     *    which restarts the idle countdown without changing runState.
     *  - "Finish & save" → routes through [stopAndReviewPendingIntent] → the
     *    same MainActivity deep-link as the foreground Stop action.
     */
    fun buildIdleReminder(ctx: Context, live: SessionLive): Notification {
        val smallIconRes: Int
        val accentColor: Int
        when (live.kind) {
            ActivityKind.Running -> {
                smallIconRes = R.drawable.ic_notification_run
                accentColor = 0xFFD32F2F.toInt()
            }
            else -> {
                smallIconRes = R.drawable.ic_notification_walk
                accentColor = 0xFF2E7D32.toInt()
            }
        }
        val minutes = (ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS / 60_000L).toInt()
        return NotificationCompat.Builder(ctx, IDLE_REMINDER_CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setColor(accentColor)
            .setColorized(false)
            .setContentTitle(ctx.getString(R.string.exercise_notification_idle_title))
            .setContentText(ctx.getString(R.string.exercise_notification_idle_body, minutes))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(openSessionPendingIntent(ctx))
            .addAction(
                android.R.drawable.ic_media_play,
                ctx.getString(R.string.exercise_notification_idle_action_continue),
                servicePi(ctx, LocationTrackingService.ACTION_IDLE_CONTINUE, RC_IDLE_CONTINUE),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                ctx.getString(R.string.exercise_notification_idle_action_finish),
                stopAndReviewPendingIntent(ctx, RC_IDLE_STOP_REVIEW),
            )
            .build()
    }

    fun build(ctx: Context, live: SessionLive?): Notification {
        // On Android 16+ keep the title stable across pause/resume so OEM
        // Live-Update islands don't dismiss us on every state flip; the
        // paused state is conveyed by the (frozen) duration + desaturated
        // route thumbnail instead. On older versions the suffix is fine.
        val useStableTitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
        val title = buildTitle(ctx, live, includePausedSuffix = !useStableTitle)
        val body = buildBody(ctx, live)
        val (bigBmp, thumbBmp) = obtainBitmaps(ctx, live)

        // Kind-specific static small icon + accent color so the OEM island's
        // left slot is unambiguously "walking person, green" or "running
        // person, red". setColor() tints the small-icon alpha mask system-
        // wide (status bar, shade, lock screen, OriginOS Atomic Island).
        // Colours match RouteBitmapRenderer's polyline so the island icon
        // and the route preview read as the same accent. live==null is the
        // pre-first-build window between Start tap and service refresh —
        // walk is the safe default (~99% of sessions).
        //
        // No animation: at 24 dp + 1 Hz notification refresh, frame-swap
        // animation reads as a flicker rather than motion; we tried mirror,
        // leg-swap, and march-in-place poses and none read cleanly. Static
        // kind icon + accent colour is the better trade-off.
        val smallIconRes: Int
        val accentColor: Int
        when (live?.kind) {
            ActivityKind.Running -> {
                smallIconRes = R.drawable.ic_notification_run
                accentColor = 0xFFD32F2F.toInt()  // red-700, matches RouteBitmapRenderer
            }
            else -> {
                smallIconRes = R.drawable.ic_notification_walk
                accentColor = 0xFF2E7D32.toInt()  // green-800, matches RouteBitmapRenderer
            }
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setColor(accentColor)
            .setColorized(false)  // only tint the small icon, not the whole notification background
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

        // Subtext appears next to the app name in the notification header — a
        // safe (non-structural) slot to vary across pause/resume, visible on
        // both the lock screen and expanded shade. Title stays stable per the
        // OEM-island concern above; this is the secondary affordance.
        if (live != null && (live.runState == RunState.Paused || live.runState == RunState.AutoPaused)) {
            builder.setSubText(ctx.getString(R.string.exercise_notification_subtext_paused))
        }

        addToggleAction(ctx, builder, live)
        addStopAction(ctx, builder)

        // Style choice:
        //  - API 36+ : ProgressStyle so OriginOS 6 / Pixel / Samsung surface
        //    the notification in their respective OEM Live-Update island. The
        //    big route picture is sacrificed on the main notification; we
        //    bring it back on the lock screen via setPublicVersion below.
        //  - API < 36: BigPictureStyle with route polyline as big image.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && live != null) {
            // OEM islands compact the notification to a short label + tracker
            // icon — the duration (mm:ss, ~5 chars) is the most useful glance
            // value and fits the suggested 7-char budget. When paused, prefix
            // U+23F8 (⏸) so the island shows "⏸ 12:34" — within budget and
            // makes the paused state legible at a glance from the status bar.
            val duration = ExerciseMath.formatDuration(live.activeDurationMillis)
            val isPaused = live.runState == RunState.Paused || live.runState == RunState.AutoPaused
            builder.setShortCriticalText(if (isPaused) "⏸ $duration" else duration)
            applyProgressStyle(builder, live, thumbBmp, title, buildSummary(ctx, live))

            // ProgressStyle replaces the bigPicture slot, so the lock-screen
            // card on Android 16+ loses the route map. Bring it back via
            // setPublicVersion: a separate BigPictureStyle notification the
            // lockscreen renderer uses in place of the redacted view when
            // visibility == VISIBILITY_PRIVATE. The main notification keeps
            // ProgressStyle for the atomic island and unlocked shade; the
            // public version handles the lock screen exclusively. Only flip
            // to PRIVATE when we actually have a publicVersion to attach —
            // otherwise the system would redact us with a generic placeholder.
            if (bigBmp != null) {
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                builder.setPublicVersion(
                    buildLockScreenVariant(
                        ctx, live, title, body,
                        smallIconRes, accentColor,
                        thumbBmp, bigBmp,
                    ),
                )
            }
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

    /**
     * Builds the Android 16+ lock-screen variant returned by
     * `setPublicVersion(...)`. Mirrors the main notification's identity
     * fields (icon, color, title, body, subtext, large icon, actions, content
     * intent) but swaps the style to `BigPictureStyle` so the route polyline
     * appears as a Google-Maps-style mini map on the lock-screen card. The
     * main notification keeps `ProgressStyle` for atomic-island docking; the
     * lock-screen renderer pulls this variant instead.
     *
     * Visibility must be `PUBLIC` on this variant — otherwise the system
     * would redact it again and we'd recurse into a placeholder.
     *
     * Reuses the same cached `bigBmp` / `thumbBmp` references the main
     * builder uses; `NotificationManager` keeps both alive until the next
     * `notify(...)`, so the existing recycle-prev-cache pattern in
     * `obtainBitmaps` is unaffected.
     */
    private fun buildLockScreenVariant(
        ctx: Context,
        live: SessionLive,
        title: String,
        body: String,
        smallIconRes: Int,
        accentColor: Int,
        thumbBmp: Bitmap?,
        bigBmp: Bitmap,
    ): Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setColor(accentColor)
            .setColorized(false)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openSessionPendingIntent(ctx))
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigBmp)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    .setSummaryText(buildSummary(ctx, live)),
            )
        if (thumbBmp != null) b.setLargeIcon(thumbBmp)
        if (live.runState == RunState.Paused || live.runState == RunState.AutoPaused) {
            b.setSubText(ctx.getString(R.string.exercise_notification_subtext_paused))
        }
        addToggleAction(ctx, b, live)
        addStopAction(ctx, b)
        return b.build()
    }

    // ─── Title / body / summary ───────────────────────────────────────────

    private fun buildTitle(ctx: Context, live: SessionLive?, includePausedSuffix: Boolean): String {
        if (live == null) return ctx.getString(R.string.exercise_recording_title)
        val base = when (live.kind) {
            ActivityKind.Walking -> ctx.getString(R.string.exercise_notification_title_walk)
            ActivityKind.Running -> ctx.getString(R.string.exercise_notification_title_run)
        }
        val pausedSuffix = if (includePausedSuffix && (live.runState == RunState.Paused || live.runState == RunState.AutoPaused)) {
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
        // When paused, prefix the body with U+23F8 (⏸) so the lock screen and
        // drop-down shade clearly read "paused" at a glance — the existing
        // header subtext alone is too easy to miss. Pace is dropped: it's
        // frozen while paused and reads as misleading stale data. The body is
        // not consumed by OEM Live-Update islands (they use shortCriticalText),
        // so changing it across pause/resume does not affect island docking.
        if (live.runState == RunState.Paused || live.runState == RunState.AutoPaused) {
            return ctx.getString(R.string.exercise_recording_body_paused, dist, time)
        }
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
        // Always render BOTH Pause and Resume actions so the notification
        // structure stays stable across runState transitions — flipping a
        // button in/out makes OEM Live-Update surfaces (OriginOS Atomic
        // Island, etc.) treat the notification as a major restructure and
        // drop it from the island. Tapping the "wrong" one is a no-op
        // because LiveStore's pause()/resume() only fire on matching state.
        if (live == null) return
        builder.addAction(
            android.R.drawable.ic_media_pause,
            ctx.getString(R.string.exercise_notification_action_pause),
            servicePi(ctx, LocationTrackingService.ACTION_PAUSE, RC_PAUSE),
        )
        builder.addAction(
            android.R.drawable.ic_media_play,
            ctx.getString(R.string.exercise_notification_action_resume),
            servicePi(ctx, LocationTrackingService.ACTION_RESUME, RC_RESUME),
        )
    }

    private fun addStopAction(ctx: Context, builder: NotificationCompat.Builder) {
        // Route Stop through MainActivity (not directly to the service) so the
        // user lands on the Summary screen for save/discard. MainActivity
        // calls controller.stop() itself; the service shuts down as a
        // side-effect of that call.
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            ctx.getString(R.string.exercise_notification_action_stop),
            stopAndReviewPendingIntent(ctx, RC_STOP),
        )
    }

    /**
     * Shared PendingIntent for any "stop the session and route to the Summary
     * screen" action. Used by both the foreground notification's Stop button
     * ([addStopAction]) and the idle-reminder notification's "Finish & save"
     * button ([buildIdleReminder]). Distinct [requestCode] per caller is
     * required so FLAG_UPDATE_CURRENT updates each slot independently.
     */
    private fun stopAndReviewPendingIntent(ctx: Context, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_STOP_AND_REVIEW, true)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
        // Why this bar exists at all: NotificationCompat.ProgressStyle is the
        // gate-keeper API for Android 16's Live Updates surface — OriginOS
        // Atomic Island, Pixel status pill, Samsung Now Brief etc. only pull
        // a notification into their compact capsule if it carries ProgressStyle.
        // Drop the style and the notification stays buried in the shade.
        //
        // What the bar represents: one 100-unit Segment coloured by activity
        // kind (walk green-800 / run red-700). The bar tracks CURRENT
        // KILOMETRE, not total session distance — progress = (distance % 1000) / 10
        // so 0 m → 1, 500 m → 50, 999 m → 99, then it resets and the next km
        // starts. Looping rather than saturating-fill keeps the bar
        // informative past 1 km without requiring the user to set a goal
        // (this app has no exercise-distance target the way coach/medication
        // schedules do).
        //
        // What the tracker icon is: the same route-polyline thumbnail we hand
        // to setLargeIcon, repurposed as the slider so the user sees a
        // recognisable miniature of their route walking along the bar instead
        // of a generic dot.
        //
        // setProgressIndeterminate(false) is deliberate: determinate mode lets
        // the slider position carry real meaning; indeterminate would just be
        // a barber-pole animation with no signal.
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
