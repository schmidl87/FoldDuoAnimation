package de.fold8.duoanimation

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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.app.NotificationCompat
import kotlin.math.sin

class HingeAnimationService : Service(), SensorEventListener {

    companion object {
        const val ACTION_TEST_ANIMATION = "de.fold8.duoanimation.TEST_ANIMATION"
    }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var windowManager: WindowManager
    private var overlayView: HingeEffectView? = null
    private var isOverlayAttached = false
    private var lastAngle = -1f
    private var isTesting = false

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = HingeEffectView(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        hingeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_ANIMATION && Settings.canDrawOverlays(this)) {
            runSimulatedAnimation()
        }
        return START_STICKY
    }

    private fun runSimulatedAnimation() {
        isTesting = true
        attachOverlay()
        val animator = ValueAnimator.ofFloat(10f, 178f).apply {
            duration = 950L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val simulatedAngle = anim.animatedValue as Float
                overlayView?.updateHingeAngle(simulatedAngle)
                if (simulatedAngle >= 175f) {
                    overlayView?.finishAndDetach {
                        detachOverlay()
                        isTesting = false
                    }
                }
            }
        }
        animator.start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isTesting || event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        if (!Settings.canDrawOverlays(this)) return

        val angle = event.values[0]

        if (lastAngle < 0f) {
            lastAngle = angle
            return
        }

        if (angle in 8f..174f) {
            attachOverlay()
            overlayView?.updateHingeAngle(angle)
        } else if (isOverlayAttached && (angle >= 175f || angle <= 5f)) {
            overlayView?.finishAndDetach { detachOverlay() }
        }
        lastAngle = angle
    }

    private fun attachOverlay() {
        if (isOverlayAttached || overlayView == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            windowManager.addView(overlayView, params)
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
            .setContentText("Scharnier-Sensor wird überwacht")
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
        sensorManager.unregisterListener(this)
        detachOverlay()
        super.onDestroy()
    }
}

class HingeEffectView(context: Context) : View(context) {
    private var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spring = OvershootInterpolator(1.08f)

    fun updateHingeAngle(angle: Float) {
        animate().cancel()
        alpha = 1f
        val linear = ((angle - 8f) / 166f).coerceIn(0f, 1f)
        progress = spring.getInterpolation(linear)
        invalidate()
    }

    fun finishAndDetach(onEnd: () -> Unit) {
        animate()
            .alpha(0f)
            .setDuration(240)
            .withEndAction {
                alpha = 1f
                onEnd()
            }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val curve = sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat()

        // 1. Weicher Tiefen- & Lichtverlauf vom Scharnier aus
        val waveRadius = (w * 0.62f * progress).coerceAtLeast(12f)
        val glowAlpha = (curve * 185).toInt().coerceIn(0, 255)

        paint.shader = RadialGradient(
            centerX, h / 2f,
            waveRadius,
            intArrayOf(
                Color.argb(glowAlpha, 210, 235, 255),
                Color.argb((glowAlpha * 0.5f).toInt(), 90, 155, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. Feine vertikale Lichtlinie direkt entlang der Faltachse
        val seamAlpha = (curve * 230).toInt().coerceIn(0, 255)
        val seamWidth = (w * 0.04f * (1f - progress * 0.6f)).coerceAtLeast(4f)
        seamPaint.shader = LinearGradient(
            centerX - seamWidth, 0f,
            centerX + seamWidth, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(seamAlpha, 235, 248, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(centerX - seamWidth, 0f, centerX + seamWidth, h, seamPaint)
    }
}
