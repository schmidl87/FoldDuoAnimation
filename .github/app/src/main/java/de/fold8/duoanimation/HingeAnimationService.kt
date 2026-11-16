package de.fold8.duoanimation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sin

class HingeAnimationService : Service(), SensorEventListener {

    companion object {
        const val ACTION_TEST_ANIMATION = "de.fold8.duoanimation.TEST_ANIMATION"
    }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var windowManager: WindowManager
    private var overlayView: HingeEffectView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false

    private var lastAngle = -1f
    private var currentProgress = 0f
    private var activeAnimator: ValueAnimator? = null
    private var currentDirectionClosing: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = HingeEffectView(this)
        overlayParams = createOverlayParams()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        hingeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_ANIMATION && Settings.canDrawOverlays(this)) {
            currentProgress = 1.0f
            startSmoothAnimation(isClosing = false, durationMs = 950L)
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        if (!Settings.canDrawOverlays(this)) return

        val angle = event.values[0]

        if (lastAngle < 0f) {
            lastAngle = angle
            currentProgress = if (angle >= 150f) 0f else 1f
            return
        }

        val delta = angle - lastAngle
        if (abs(delta) < 5f) return

        val isClosing = delta < 0f
        lastAngle = angle

        if (activeAnimator?.isRunning == true && currentDirectionClosing == isClosing) {
            return
        }

        if (isClosing) {
            startSmoothAnimation(isClosing = true, durationMs = 550L)
        } else {
            if (currentProgress < 0.2f) {
                currentProgress = 1.0f
            }
            startSmoothAnimation(isClosing = false, durationMs = 650L)
        }
    }

    private fun startSmoothAnimation(isClosing: Boolean, durationMs: Long) {
        activeAnimator?.cancel()
        currentDirectionClosing = isClosing
        attachOverlay()

        val startVal = currentProgress
        val endVal = if (isClosing) 1.0f else 0.0f

        activeAnimator = ValueAnimator.ofFloat(startVal, endVal).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)

            addUpdateListener { anim ->
                currentProgress = anim.animatedValue as Float
                applyVisualState(currentProgress)
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentDirectionClosing = null
                    overlayView?.animate()
                        ?.alpha(0f)
                        ?.setDuration(180L)
                        ?.withEndAction {
                            detachOverlay()
                            currentProgress = if (isClosing) 1.0f else 0.0f
                        }
                        ?.start()
                }
            })
            start()
        }
    }

    private fun applyVisualState(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        overlayView?.updateProgress(clamped)

        val params = overlayParams ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.blurBehindRadius = (clamped * 95f).toInt()
        }
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (_: Exception) { }
        }
    }

    private fun createOverlayParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 0
            }
        }
    }

    private fun attachOverlay() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        view.animate().cancel()
        view.alpha = 1f

        if (isOverlayAttached) return
        try {
            windowManager.addView(view, params)
            isOverlayAttached = true
        } catch (_: Exception) { }
    }

    private fun detachOverlay() {
        if (!isOverlayAttached || overlayView == null) return
        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) { }
        isOverlayAttached = false
    }

    private fun startForegroundNotification() {
        val channelId = "hinge_anim_channel"
        val channel = NotificationChannel(
            channelId,
            "Fold Duo Animation",
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Fold Duo Animation aktiv")
            .setContentText("Scharnier-Animation bereit")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeAnimator?.cancel()
        sensorManager.unregisterListener(this)
        detachOverlay()
        super.onDestroy()
    }
}

class HingeEffectView(context: Context) : View(context) {
    private var progress = 0f
    private val leftPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun updateProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || progress <= 0.005f) return

        val centerX = w / 2f

        val outerLeftAlpha = (progress * 55f).toInt().coerceIn(0, 255)
        val innerLeftAlpha = (progress * 150f).toInt().coerceIn(0, 255)
        leftPanelPaint.shader = LinearGradient(
            0f, 0f,
            centerX, 0f,
            intArrayOf(
                Color.argb(outerLeftAlpha, 18, 22, 30),
                Color.argb(innerLeftAlpha, 10, 12, 18)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, centerX, h, leftPanelPaint)

        val rightAlpha = (progress * 30f).toInt().coerceIn(0, 255)
        rightPanelPaint.color = Color.argb(rightAlpha, 220, 235, 255)
        canvas.drawRect(centerX, 0f, w, h, rightPanelPaint)

        val curve = sin(progress * Math.PI).toFloat()
        val seamAlpha = (curve * 220f).toInt().coerceIn(0, 255)
        val seamHalfWidth = (w * 0.045f * (0.4f + progress * 0.6f)).coerceAtLeast(6f)

        seamPaint.shader = LinearGradient(
            centerX - seamHalfWidth, 0f,
            centerX + seamHalfWidth, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(seamAlpha, 235, 246, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(centerX - seamHalfWidth, 0f, centerX + seamHalfWidth, h, seamPaint)
    }
}
