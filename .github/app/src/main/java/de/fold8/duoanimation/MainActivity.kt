package de.fold8.duoanimation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 120, 64, 64)
            setBackgroundColor(Color.parseColor("#111318"))
        }

        val title = TextView(this).apply {
            text = "Fold Duo Animation"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#A0C4FF"))
            setPadding(0, 0, 0, 16)
        }

        angleText = TextView(this).apply {
            text = if (hingeSensor != null) "Scharnierwinkel: Bereit" else "Kein Scharnier-Sensor erkannt"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 48)
        }

        val btnPermission = Button(this).apply {
            text = "1. Overlay-Berechtigung erlauben"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        val btnStart = Button(this).apply {
            text = "2. Hintergrund-Dienst starten"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Bitte zuerst Schritt 1 (Overlay-Berechtigung) erlauben!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                requestNotificationPermissionIfNeeded()
                val serviceIntent = Intent(this@MainActivity, HingeAnimationService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                Toast.makeText(this@MainActivity, "Animation im Hintergrund aktiv!", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

        val btnTest = Button(this).apply {
            text = "3. Animation jetzt testen"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Overlay-Berechtigung fehlt noch.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val testIntent = Intent(this@MainActivity, HingeAnimationService::class.java).apply {
                    action = HingeAnimationService.ACTION_TEST_ANIMATION
                }
                ContextCompat.startForegroundService(this@MainActivity, testIntent)
            }
        }

        val btnStop = Button(this).apply {
            text = "Dienst beenden"
            setOnClickListener {
                stopService(Intent(this@MainActivity, HingeAnimationService::class.java))
                Toast.makeText(this@MainActivity, "Dienst gestoppt", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(angleText)
        layout.addView(btnPermission)
        layout.addView(btnStart)
        layout.addView(btnTest)
        layout.addView(btnStop)

        setContentView(layout)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        hingeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HINGE_ANGLE) {
            angleText.text = "Live-Scharnierwinkel: ${event.values[0].toInt()}°"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        statusText.text = if (canOverlay) {
            "Overlay-Berechtigung: Erteilt"
        } else {
            "Overlay-Berechtigung: Fehlt noch (Schritt 1 antippen)"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
