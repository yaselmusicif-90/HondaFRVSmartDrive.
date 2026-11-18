package com.hondafrv.smartdrive

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.media.AudioManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity(), LocationListener {
    private lateinit var web: WebView
    private lateinit var audio: AudioManager
    private lateinit var locationManager: LocationManager
    @Volatile private var obdSocket: BluetoothSocket? = null
    @Volatile private var obdRunning = false
    private var gpsPermissionRequested = false
    private var gpsUpdatesActive = false
    @Volatile private var obdThread: Thread? = null
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        hideSystemUi()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (_: Exception) { false }
            }
        }
        web.addJavascriptInterface(DriveBridge(), "Drive")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        updateNetworkStatus()
        startGpsIfPermitted()
    }


    private fun updateNetworkStatus() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val connected = cm.activeNetworkInfo?.isConnected == true
        js("if(window.onNetworkStatus)window.onNetworkStatus(${connected});")
    }

    private fun startGpsIfPermitted() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!gpsPermissionRequested) {
                gpsPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            }
            return
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                if (!gpsUpdatesActive) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
                    gpsUpdatesActive = true
                }
                val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (last != null) onLocationChanged(last)
            } else {
                js("if(window.onGpsStatus)window.onGpsStatus('GPS DESACTIVADO');")
            }
        } catch (_: SecurityException) {
            js("if(window.onGpsStatus)window.onGpsStatus('PERMISO GPS PENDIENTE');")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else -1f
        js("if(window.onGpsData)window.onGpsData(${location.latitude},${location.longitude},${speedKmh});")
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { js("if(window.onGpsStatus)window.onGpsStatus('GPS ACTIVO');") }
    override fun onProviderDisabled(provider: String) { js("if(window.onGpsStatus)window.onGpsStatus('GPS DESACTIVADO');") }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            gpsPermissionRequested = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGpsIfPermitted()
            else js("if(window.onGpsStatus)window.onGpsStatus('PERMISO GPS DENEGADO');")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationManager.isInitialized) startGpsIfPermitted()
        if (::web.isInitialized) updateNetworkStatus()
    }

    override fun onPause() {
        if (::locationManager.isInitialized && gpsUpdatesActive) {
            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
            gpsUpdatesActive = false
        }
        super.onPause()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun js(script: String) { runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(script, null) } }
    private fun reportStatus(message: String) {
        val safe = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        js("if(window.onObdStatus)window.onObdStatus('$safe');")
    }

    private fun connectObd() {
        if (obdRunning) { reportStatus("Conectando o conectado…"); return }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { reportStatus("Esta tablet no dispone de Bluetooth"); return }
        if (!adapter.isEnabled) { reportStatus("Activa Bluetooth y vuelve a pulsar CONECTAR"); return }
        val device: BluetoothDevice? = try {
            adapter.bondedDevices.firstOrNull { d ->
                val n = (d.name ?: "").uppercase()
                n.contains("ELM") || n.contains("OBD") || n.contains("V-LINK") || n.contains("VGATE")
            }
        } catch (_: SecurityException) { null }
        if (device == null) { reportStatus("No encuentro ELM327 emparejado. Vincúlalo en Ajustes > Bluetooth"); return }
        obdRunning = true
        reportStatus("Conectando con ${device.name ?: "ELM327"}…")
        obdThread = thread(name = "OBD2-ELM327") {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                obdSocket = socket
                socket.connect()
                val input = socket.inputStream
                val output = socket.outputStream
                fun command(cmd: String): String {
                    output.write((cmd + "\r").toByteArray(Charsets.US_ASCII)); output.flush()
                    val result = StringBuilder(); val deadline = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < deadline) {
                        if (input.available() > 0) {
                            val b = input.read()
                            if (b == '>'.code) break
                            if (b >= 0) result.append(b.toChar())
                        } else Thread.sleep(20)
                    }
                    return result.toString().replace("\r", " ").replace("\n", " ").trim()
                }
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it); Thread.sleep(180) }
                reportStatus("ELM327 conectado · consultando centralita")
                fun pid(cmd: String): List<Int> {
                    val raw = command(cmd).uppercase().replace(Regex("[^0-9A-F]"), "")
                    val marker = when (cmd) { "010C" -> "410C"; "010D" -> "410D"; "0105" -> "4105"; "012F" -> "412F"; "0142" -> "4142"; else -> "" }
                    val at = raw.indexOf(marker)
                    if (at < 0 || raw.length < at + marker.length + 2) return emptyList()
                    val bytes = mutableListOf<Int>(); var i = at + marker.length
                    while (i + 1 < raw.length) { bytes.add(raw.substring(i, i + 2).toIntOrNull(16) ?: break); i += 2 }
                    return bytes
                }
                while (obdRunning && !isFinishing) {
                    val rpm = pid("010C").let { if (it.size >= 2) (it[0] * 256 + it[1]) / 4.0 else null }
                    val speed = pid("010D").firstOrNull()?.toDouble()
                    val temp = pid("0105").firstOrNull()?.let { (it - 40).toDouble() }
                    val fuel = pid("012F").firstOrNull()?.let { it * 100.0 / 255.0 }
                    val volts = pid("0142").let { if (it.size >= 2) (it[0] * 256 + it[1]) / 1000.0 else null }
                    val values = "{\"rpm\":${rpm?.toString() ?: "null"},\"speed\":${speed?.toString() ?: "null"},\"temp\":${temp?.toString() ?: "null"},\"fuel\":${fuel?.toString() ?: "null"},\"volts\":${volts?.toString() ?: "null"}}"
                    js("if(window.onObdData)window.onObdData($values);")
                    Thread.sleep(650)
                }
            } catch (e: Exception) {
                reportStatus("OBD2 desconectado: ${e.message ?: "comprueba el adaptador"}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                obdSocket = null; obdRunning = false
                if (!isFinishing) reportStatus("OBD2 desconectado")
            }
        }
    }

    private fun disconnectObd() {
        obdRunning = false
        try { obdSocket?.close() } catch (_: IOException) {}
        reportStatus("Desconectando OBD2…")
    }

    inner class DriveBridge {
        @JavascriptInterface fun setMediaVolume(percent: Int) { runOnUiThread {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent.coerceIn(0, 100) / 100.0).toInt(), 0)
        } }
        @JavascriptInterface fun adjustMediaVolume(delta: Int) { runOnUiThread {
            audio.adjustStreamVolume(if (delta >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } }
        @JavascriptInterface fun openUrl(url: String) { runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(this@MainActivity, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show() } } }
        @JavascriptInterface fun deviceVolume(): Int {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC); val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return current * 100 / max
        }
        @JavascriptInterface fun connectObd() { this@MainActivity.connectObd() }
        @JavascriptInterface fun disconnectObd() { this@MainActivity.disconnectObd() }
        @JavascriptInterface fun openBluetoothSettings() { runOnUiThread { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } }
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        gpsUpdatesActive = false
        disconnectObd(); super.onDestroy()
    }
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }
}
