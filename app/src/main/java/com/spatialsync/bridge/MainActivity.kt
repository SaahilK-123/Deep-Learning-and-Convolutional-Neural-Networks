package com.spatialsync.bridge

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private var announcementReceiver: AnnouncementReceiver? = null
    private lateinit var spatialTTS: SpatialTTS
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var registerBtn: Button

    private var frameSender: FrameSender? = null
    private val permMutex = Mutex()
    private var permContinuation: kotlinx.coroutines.CancellableContinuation<DatResult<PermissionStatus, PermissionError>>? = null

    private val permLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result: DatResult<PermissionStatus, PermissionError> ->
        permContinuation?.resume(result) {}
    }

    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.CAMERA
    )
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestAllPermissions()

        spatialTTS = SpatialTTS(this)
        spatialTTS.init()
        android.os.Handler(mainLooper).postDelayed({
            spatialTTS.routeToGlasses()
        }, 1500)

        statusText = findViewById(R.id.statusText)
        ipInput = findViewById(R.id.ipInput)
        connectBtn = findViewById(R.id.connectBtn)
        registerBtn = findViewById(R.id.registerBtn)

        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                log("Registration state: $state")
            }
        }

        lifecycleScope.launch {
            Wearables.devices.collect { devices ->
                log("Devices visible to SDK: $devices")
            }
        }

        registerBtn.setOnClickListener {
            log("Starting registration with Meta AI app...")
            Wearables.startRegistration(this)
        }

        connectBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                startStreaming(ip)
            } else {
                log("Please enter the PC IP address")
            }
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val results = permissions.zip(grantResults.toList())
            val denied = results
                .filter { it.second != android.content.pm.PackageManager.PERMISSION_GRANTED }
                .map { it.first.split(".").last() }
            val granted = results
                .filter { it.second == android.content.pm.PackageManager.PERMISSION_GRANTED }
                .map { it.first.split(".").last() }

            if (granted.isNotEmpty()) log("Granted: $granted")
            if (denied.isNotEmpty()) log("DENIED — go to Settings > Apps > SpatialSyncBridge > Permissions and grant: $denied")
            if (denied.isEmpty()) log("All system permissions granted — tap Register next")
        }
    }

    private fun startStreaming(pcIp: String) {
        lifecycleScope.launch {

            // Step 1: Check system permissions
            val missingPermissions = REQUIRED_PERMISSIONS.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.map { it.split(".").last() }

            if (missingPermissions.isNotEmpty()) {
                log("Missing permissions: $missingPermissions — go to Settings > Apps > SpatialSyncBridge > Permissions")
                return@launch
            }

            // Step 2: Check registration
            val currentRegState = Wearables.registrationState.value
            log("Registration state: $currentRegState")
            if (currentRegState != RegistrationState.REGISTERED) {
                log("Not registered — tap Register first and approve in the Meta AI app")
                return@launch
            }

            // Step 3: Log visible devices
            val visibleDevices = Wearables.devices.value
            log("SDK visible devices at connect time: $visibleDevices")
            if (visibleDevices.isEmpty()) {
                log("WARNING: SDK sees no devices — make sure glasses are worn and connected in Meta AI app")
                return@launch
            }

            // Step 4: Start frame sender and announcement receiver
            log("Connecting to PC at $pcIp:9999...")
            frameSender?.stop()
            frameSender = FrameSender(pcIp).also { it.start() }

            announcementReceiver?.stop()
            announcementReceiver = AnnouncementReceiver(
                host = pcIp,
                onAnnouncement = { className, quadrant, distance ->
                    spatialTTS.speak(className, quadrant, distance)
                }
            ).also { it.start() }

            // Step 5: Check DAT camera permission
//            log("Checking DAT camera permission...")
//            var permissionGranted = false
//            requestPermission(Permission.CAMERA)
            // Step 5: Check DAT camera permission
            log("Checking DAT camera permission...")

            log("Registration state = ${Wearables.registrationState.value}")
            log("Devices = ${Wearables.devices.value}")

            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    log("DAT Camera status before request = $status")
                }
                .onFailure { error, _ ->
                    log("Permission check error = ${error.description}")
                }

            var permissionGranted = false

            requestPermission(Permission.CAMERA)
                .onSuccess { status ->
                    permissionGranted = (status == PermissionStatus.Granted)
                    log("DAT camera permission result = $status")
                }
                .onFailure { error, _ ->
                    log("DAT permission request failed = ${error.description}")
                }

            // Step 6: Create session using SpecificDeviceSelector
            val deviceId = visibleDevices.first()
            log("Creating session with device: $deviceId")

            val session = Wearables.createSession(
                SpecificDeviceSelector(deviceId)
            ).getOrElse { error ->
                log("Session failed: $error")
                log("Session error type: ${error::class.java.name}")
                return@launch
            }

            // Step 7: Observe session state — attach stream only when STARTED
            log("Session created — calling session.start()...")
            launch {
                session.state.collect { state ->
                    log("Session state: $state")
                    if (state == DeviceSessionState.STARTED) {
                        log("Session is STARTED — attaching stream...")
                        attachStream(session)
                    }
                }
            }

            session.start()
        }
    }

    private fun attachStream(session: DeviceSession) {
        lifecycleScope.launch {
            log("Calling addStream...")
            val stream = session.addStream(
                StreamConfiguration(
                    videoQuality = VideoQuality.LOW,
                    frameRate = 60, //Edited from 15
                )
            ).getOrElse { error ->
                log("addStream FAILED: $error")
                log("addStream error type: ${error::class.java.name}")
                return@launch
            }

            log("addStream succeeded — observing stream state...")
            launch {
                stream.state.collect { state ->
                    log("Stream state: $state")
                }
            }

            log("Calling stream.start()...")
            stream.start().getOrElse { error ->
                log("stream.start() FAILED: $error")
                log("stream.start() error type: ${error::class.java.name}")
                return@launch
            }

            log("stream.start() succeeded — collecting frames...")
            launch {
                try {
                    stream.videoStream.collect { frame ->
                        log("Frame received: ${frame.width}x${frame.height}")
                        try {
                            val bitmap = i420ToBitmap(frame.buffer, frame.width, frame.height)
                            frameSender?.sendFrame(bitmap)
                        } catch (e: Exception) {
                            log("Frame conversion error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    log("videoStream collect error: ${e.message}")
                }
            }
        }
    }

    private fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)
        buffer.rewind()
        buffer.get(nv21, 0, ySize)
        val uPlane = ByteArray(uvSize)
        val vPlane = ByteArray(uvSize)
        buffer.get(uPlane)
        buffer.get(vPlane)
        for (i in 0 until uvSize) {
            nv21[ySize + i * 2] = vPlane[i]
            nv21[ySize + i * 2 + 1] = uPlane[i]
        }
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun requestPermission(
        permission: Permission
    ): DatResult<PermissionStatus, PermissionError> =
        permMutex.withLock {
            val current = Wearables.checkPermissionStatus(permission)
            var alreadyGranted = false
            current.onSuccess { status ->
                alreadyGranted = (status == PermissionStatus.Granted)
            }
            if (alreadyGranted) return current
            suspendCancellableCoroutine { cont ->
                permContinuation = cont
                permLauncher.launch(permission)
            }
        }

    private fun log(msg: String) {
        Log.d("SpatialSync", msg)
        runOnUiThread { statusText.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        frameSender?.stop()
        announcementReceiver?.stop()
        spatialTTS.shutdown()
    }
}