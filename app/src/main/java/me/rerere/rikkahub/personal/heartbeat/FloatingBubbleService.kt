package me.rerere.rikkahub.personal.heartbeat

import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Heartbeat floating bubble (ported from orangechat FloatingBubbleService, Telegram-style
 * transient round bubble — adapted to the fork's heartbeat delivery instead of orangechat's
 * ProactiveMessageService):
 *
 *  - When the heartbeat delivers a user-visible message / question notification, the
 *    workflow calls [showIfEnabled]; if the per-assistant heartbeat config enables it and
 *    the overlay permission is held, a round bubble pops near the screen edge.
 *  - Tap the bubble -> opens the conversation (RouteActivity + "conversationId" extra, the
 *    same route HeartbeatNotifications uses) and the bubble dismisses.
 *  - Drag -> repositions, snaps to the nearest edge on release.
 *  - Auto-dismisses after [MAX_ALIVE_MS].
 *  - Silent degradation: no overlay permission, toggle off, or a blocked startForegroundService
 *    simply means no bubble — the regular heartbeat notification has already fired.
 *
 * Runs as a short-lived foreground service (same fork convention as QuickCaptureOverlayService)
 * so startForegroundService from the heartbeat path satisfies the Android 8+ background
 * service-start rules; the notification is PRIORITY_MIN and removed on dismiss.
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "HeartbeatBubble"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SENDER_NAME = "sender_name"
        const val EXTRA_AVATAR_EMOJI = "avatar_emoji"
        const val ACTION_DISMISS = "me.rerere.rikkahub.action.HEARTBEAT_BUBBLE_DISMISS"

        private const val BUBBLE_SIZE_DP = 56
        private const val RED_DOT_SIZE_DP = 14
        private const val EDGE_MARGIN_DP = 12
        /** Movement below this threshold is a tap, not a drag. */
        private const val CLICK_THRESHOLD_PX = 16f
        /** Longest a bubble may stay — no forgotten ball floating on the screen. */
        private const val MAX_ALIVE_MS = 60_000L
        private const val NOTIFICATION_ID = 47025
        private const val CHANNEL_ID = "heartbeat_floating_bubble"

        /**
         * Entry point for the heartbeat workflow. Gated here so call sites stay one-liners:
         * [enabled] (per-assistant HeartbeatConfig.floatingBubbleEnabled), the overlay
         * permission, and the service start itself are all best-effort with silent
         * degradation.
         */
        fun showIfEnabled(
            context: Context,
            enabled: Boolean,
            conversationId: String,
            senderName: String,
            avatarEmoji: String? = null,
        ) {
            if (!enabled) return
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_SENDER_NAME, senderName)
                avatarEmoji?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_AVATAR_EMOJI, it) }
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_DISMISS
            }
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var shakeAnimator: android.animation.ValueAnimator? = null
    private var popAnimator: android.animation.ObjectAnimator? = null

    private val autoDismissRunnable = Runnable { stopBubble() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService contract: promote immediately, whatever the intent holds.
        startForegroundCompat()

        if (intent?.action == ACTION_DISMISS) {
            stopBubble()
            return START_NOT_STICKY
        }

        // Belt-and-braces permission check (showIfEnabled already gated).
        if (!Settings.canDrawOverlays(this)) {
            stopBubble()
            return START_NOT_STICKY
        }

        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (conversationId.isNullOrBlank()) {
            stopBubble()
            return START_NOT_STICKY
        }
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME).orEmpty().ifBlank { "AI" }
        val avatarEmoji = intent.getStringExtra(EXTRA_AVATAR_EMOJI)

        removeBubbleInternal()
        showBubble(conversationId, senderName, avatarEmoji)

        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, MAX_ALIVE_MS)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(autoDismissRunnable)
        stopAnimations()
        removeBubbleInternal()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopBubble()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopBubble() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.floating_bubble_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.floating_bubble_notification_channel))
                .setVibrationEnabled(false)
                .build(),
        )
    }

    private fun showBubble(conversationId: String, senderName: String, avatarEmoji: String?) {
        val sizePx = dp(BUBBLE_SIZE_DP)
        val accentColor = resolveAccentColor()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            contentDescription = getString(R.string.floating_bubble_content_description)
        }

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
            setStroke(dp(2), Color.WHITE)
        }

        // Emoji avatar when the assistant carries one; otherwise the sender's initial.
        // orangechat also loads image avatars via coil here — skipped in this port to keep
        // the transient bubble free of async image-loading failure modes.
        val label = TextView(this).apply {
            text = avatarEmoji?.takeIf { it.isNotBlank() }
                ?: (senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "AI")
            textSize = if (avatarEmoji.isNullOrBlank()) 24f else 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bgDrawable
        }
        container.addView(label, FrameLayout.LayoutParams(sizePx, sizePx))

        // Unread dot.
        val redDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        container.addView(
            redDot,
            FrameLayout.LayoutParams(
                dp(RED_DOT_SIZE_DP),
                dp(RED_DOT_SIZE_DP),
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                marginEnd = dp(2)
                bottomMargin = dp(2)
            },
        )
        container.elevation = dp(8).toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // minSdk 26 — TYPE_APPLICATION_OVERLAY is always available.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth() - sizePx - dp(EDGE_MARGIN_DP)).coerceAtLeast(0)
            y = screenHeight() / 3
        }

        setupTouchListener(container, params, conversationId)

        runCatching { windowManager.addView(container, params) }
            .onFailure {
                stopBubble()
                return
            }

        bubbleView = container
        startAnimations(container)
    }

    private fun setupTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        conversationId: String,
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    stopAnimations()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = (initialY + dy.toInt()).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        openConversation(conversationId)
                        stopBubble()
                    } else {
                        snapToEdge(view, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = dp(BUBBLE_SIZE_DP)
        val targetX = if (params.x + sizePx / 2 < screenWidth() / 2) {
            dp(EDGE_MARGIN_DP)
        } else {
            (screenWidth() - sizePx - dp(EDGE_MARGIN_DP)).coerceAtLeast(0)
        }
        shakeAnimator?.cancel()
        shakeAnimator = android.animation.ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }.also { it.start() }
    }

    private fun openConversation(conversationId: String) {
        runCatching {
            val intent = Intent(this, RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra("conversationId", conversationId)
            }
            startActivity(intent)
        }
    }

    /** Pop-in bounce once, then a gentle looping shake until stopped or touched. */
    private fun startAnimations(view: View) {
        stopAnimations()
        popAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            view,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.6f, 1.2f, 1f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.6f, 1.2f, 1f),
        ).apply {
            duration = 350
            interpolator = BounceInterpolator()
            start()
        }
        handler.postDelayed({
            if (bubbleView == null) return@postDelayed
            shakeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                repeatMode = android.animation.ValueAnimator.RESTART
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    val phase = animator.animatedValue as Float
                    view.rotation = (sin(phase * 2 * Math.PI) * 8).toFloat()
                }
            }.also { it.start() }
        }, 400)
    }

    private fun stopAnimations() {
        shakeAnimator?.cancel()
        shakeAnimator = null
        popAnimator?.cancel()
        popAnimator = null
        bubbleView?.rotation = 0f
    }

    private fun removeBubbleInternal() {
        bubbleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        bubbleView = null
    }

    private fun resolveAccentColor(): Int = 0xFF334155.toInt()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
}
