package com.necromagik.purecam2

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PureCamera"
        private const val REQUEST_CODE_PERMISSIONS = 101
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.READ_MEDIA_IMAGES
    )

    private lateinit var viewfinderContainer: FrameLayout
    private lateinit var captureButton: ImageButton
    private lateinit var textureView: TextureView

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var previewSize: Size? = null
    private var captureSize: Size? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraId: String? = null
    private var isTakingPicture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewfinderContainer = findViewById(R.id.container)
        captureButton = findViewById(R.id.capture_button)

        textureView = TextureView(this)
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        layoutParams.gravity = Gravity.CENTER
        viewfinderContainer.addView(textureView, layoutParams)

        setupCaptureButton()

        if (hasRequiredPermissions()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupCaptureButton() {
        captureButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    view.postDelayed({ takePhoto() }, 50)
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            true
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && hasRequiredPermissions()) {
            startCamera()
        } else {
            Toast.makeText(this, "Нужны разрешения для работы камеры", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        backgroundThread = HandlerThread("CameraThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        try {
            cameraManager?.cameraIdList?.forEach { id ->
                val characteristics = cameraManager!!.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    selectOptimalSizes(characteristics)
                    return@forEach
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка доступа к камере", e)
        }

        if (cameraId == null) {
            Toast.makeText(this, "Задняя камера не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        textureView.surfaceTextureListener = createSurfaceTextureListener()
    }

    private fun selectOptimalSizes(characteristics: CameraCharacteristics) {
        val configMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return

        val previewSizes = configMap.getOutputSizes(SurfaceTexture::class.java)
        val captureSizes = configMap.getOutputSizes(ImageFormat.JPEG)

        previewSize = previewSizes.maxByOrNull { it.width * it.height }
        captureSize = captureSizes.maxByOrNull { it.width * it.height }

        Log.d(TAG, "Selected Preview: ${previewSize?.width}x${previewSize?.height}")
        Log.d(TAG, "Selected Capture: ${captureSize?.width}x${captureSize?.height}")
    }

    private fun createSurfaceTextureListener() = object : TextureView.SurfaceTextureListener {
        @RequiresPermission(Manifest.permission.CAMERA)
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            adjustTextureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        if (cameraId == null || !hasRequiredPermissions()) return

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Timeout waiting for camera")
            }

            cameraManager?.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    Log.d(TAG, "Camera opened")
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private fun startPreview() {
        if (cameraDevice == null || previewSize == null) return

        textureView.surfaceTexture?.setDefaultBufferSize(
            previewSize!!.width,
            previewSize!!.height
        )

        val surface = Surface(textureView.surfaceTexture)

        try {
            previewRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequest!!.addTarget(surface)

            previewRequest!!.set(
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_DISABLED
            )
            previewRequest!!.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
            previewRequest!!.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(
                            previewRequest!!.build(),
                            null,
                            backgroundHandler
                        )
                        textureView.post {
                            adjustTextureTransform(textureView.width, textureView.height)
                        }
                        Log.d(TAG, "Preview started")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting preview", e)
        }
    }

    /**
     * Принудительное центрирование видео
     */
    private fun adjustTextureTransform(viewWidth: Int, viewHeight: Int) {
        if (previewSize == null) return

        val videoWidth = previewSize!!.width
        val videoHeight = previewSize!!.height

        val scaleW = viewWidth.toFloat() / videoWidth
        val scaleH = viewHeight.toFloat() / videoHeight
        val scale = if (scaleW > scaleH) scaleW else scaleH

        val scaledWidth = videoWidth * scale
        val scaledHeight = videoHeight * scale
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f

        textureView.scaleX = scale
        textureView.scaleY = scale
        textureView.translationX = left
        textureView.translationY = top

        Log.d(TAG, "Transform: scale=$scale, left=$left, top=$top")
    }

    private fun takePhoto() {
        if (isTakingPicture) return

        if (cameraDevice == null || captureSession == null || captureSize == null) {
            Toast.makeText(this, "Камера не готова", Toast.LENGTH_SHORT).show()
            return
        }

        isTakingPicture = true
        Log.d(TAG, "Taking picture...")

        val imageReader = ImageReader.newInstance(
            captureSize!!.width,
            captureSize!!.height,
            ImageFormat.JPEG,
            2
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                val buffer = it.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                it.close()
                backgroundHandler?.post { savePhoto(bytes) }
            }
            reader.close()
        }, backgroundHandler)

        val captureRequest = try {
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture request", e)
            isTakingPicture = false
            return
        }

        captureRequest.addTarget(imageReader.surface)

        captureRequest.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        captureRequest.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

        val rotation = windowManager.defaultDisplay.rotation
        captureRequest.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

        try {
            captureSession!!.stopRepeating()
            val surfaces = listOf(Surface(textureView.surfaceTexture), imageReader.surface)

            cameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                recreatePreviewSession()
                                isTakingPicture = false
                            }
                        }, backgroundHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        recreatePreviewSession()
                        isTakingPicture = false
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture", e)
            recreatePreviewSession()
            isTakingPicture = false
        }
    }

    private fun recreatePreviewSession() {
        if (cameraDevice == null || previewSize == null) return

        textureView.surfaceTexture?.setDefaultBufferSize(
            previewSize!!.width,
            previewSize!!.height
        )

        val surface = Surface(textureView.surfaceTexture)

        try {
            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        previewRequest?.let {
                            session.setRepeatingRequest(it.build(), null, backgroundHandler)
                        }
                        textureView.post {
                            adjustTextureTransform(textureView.width, textureView.height)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error recreating preview session", e)
        }
    }

    private fun savePhoto(bytes: ByteArray) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "IMG_${timestamp}.jpg"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PureCamera")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { stream -> stream.write(bytes) }
                    runOnUiThread { Toast.makeText(this, "Фото сохранено: $filename", Toast.LENGTH_SHORT).show() }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PureCamera")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { stream -> stream.write(bytes) }
                val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = android.net.Uri.fromFile(file)
                sendBroadcast(intent)
                runOnUiThread { Toast.makeText(this, "Фото сохранено: ${file.absolutePath}", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo", e)
            runOnUiThread { Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_LONG).show() }
        }
    }

    private fun getOrientation(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> 90
        Surface.ROTATION_90 -> 0
        Surface.ROTATION_180 -> 270
        Surface.ROTATION_270 -> 180
        else -> 90
    }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermissions()) startCamera()
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        backgroundThread?.quitSafely()
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}