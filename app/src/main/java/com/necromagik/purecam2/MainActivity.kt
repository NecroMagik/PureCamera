package com.necromagik.purecam2

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PureCamera"
        private const val REQUEST_CAMERA_PERMISSION = 200
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080

        private const val JPEG_QUALITY = 100
        private const val HDR_ENABLED = true
        private const val NOISE_REDUCTION_MODE = CaptureRequest.NOISE_REDUCTION_MODE_FAST
        private const val EDGE_MODE = CaptureRequest.EDGE_MODE_FAST
        private const val TONEMAP_MODE = CaptureRequest.TONEMAP_MODE_FAST
    }

    private lateinit var textureView: TextureView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var lastPhotoPreview: ImageView

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val cameraOpenCloseLock = Semaphore(1)

    private var previewSize: Size? = null
    private var captureSize: Size? = null
    private var cameraId: String? = null
    private var isCameraReady = false
    private var lastPhotoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureButton)
        lastPhotoPreview = findViewById(R.id.lastPhotoPreview)

        captureButton.setOnClickListener {
            takePhoto()
        }

        textureView.surfaceTextureListener = surfaceTextureListener
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CAMERA_PERMISSION)
        } else {
            startBackgroundThread()
            openCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    startBackgroundThread()
                    openCamera()
                } else {
                    Toast.makeText(this, "Для работы приложения необходимо разрешение на камеру", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Ошибка остановки фонового потока: ${e.message}")
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
        }
    }

    private fun openCamera() {
        if (!hasCameraPermission()) {
            Log.e(TAG, "Нет разрешения на камеру")
            return
        }

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        try {
            cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            if (cameraId == null) {
                Log.e(TAG, "Задняя камера не найдена")
                Toast.makeText(this, "Камера не найдена", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            characteristics?.let { checkManualSensorSupport(it) }

            if (cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                cameraManager?.openCamera(cameraId!!, stateCallback, backgroundHandler)
            } else {
                Log.e(TAG, "Не удалось получить доступ к камере - устройство занято")
                Toast.makeText(this, "Камера занята другим приложением", Toast.LENGTH_SHORT).show()
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка доступа к камере: ${e.message}")
            Toast.makeText(this, "Ошибка доступа к камере: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Прерывание при открытии камеры: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            Toast.makeText(this, "Нет разрешения на камеру", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkManualSensorSupport(characteristics: CameraCharacteristics) {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

        if (capabilities != null) {
            val hasManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val hasManualPostProcessing = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            val hasRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            Log.d(TAG, "Поддержка ручного сенсора: $hasManualSensor")
            Log.d(TAG, "Поддержка ручной постобработки: $hasManualPostProcessing")
            Log.d(TAG, "Поддержка RAW: $hasRaw")

            val postRawBoostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            if (postRawBoostRange != null) {
                Log.d(TAG, "Диапазон POST_RAW_SENSITIVITY_BOOST: ${postRawBoostRange.lower} - ${postRawBoostRange.upper}")
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            Log.d(TAG, "Камера успешно открыта")
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.d(TAG, "Камера отключена")
            isCameraReady = false
            captureButton.isEnabled = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Ошибка камеры: $error")
            Toast.makeText(this@MainActivity, "Ошибка камеры: $error", Toast.LENGTH_SHORT).show()
            isCameraReady = false
            captureButton.isEnabled = false
        }
    }

    private fun createCameraPreview() {
        try {
            val surfaceTexture = textureView.surfaceTexture ?: return

            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            if (map == null) {
                Log.e(TAG, "Не удалось получить конфигурацию потоков")
                return
            }

            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                textureView.width, textureView.height,
                MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT
            )

            captureSize = chooseOptimalSize(
                map.getOutputSizes(ImageFormat.JPEG),
                MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT,
                Int.MAX_VALUE, Int.MAX_VALUE
            )

            surfaceTexture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            val previewSurface = Surface(surfaceTexture)

            imageReader = ImageReader.newInstance(
                captureSize!!.width, captureSize!!.height,
                ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    cameraExecutor.execute {
                        val image = reader.acquireLatestImage()
                        image?.let { saveImage(it) }
                    }
                }, backgroundHandler)
            }

            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(previewSurface)

            previewRequestBuilder?.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            if (HDR_ENABLED) {
                previewRequestBuilder?.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
                previewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                Log.d(TAG, "HDR режим включён")
            } else {
                previewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                Log.d(TAG, "HDR режим отключён")
            }

            previewRequestBuilder?.set(CaptureRequest.NOISE_REDUCTION_MODE, NOISE_REDUCTION_MODE)
            Log.d(TAG, "Шумоподавление: FAST режим")

            previewRequestBuilder?.set(CaptureRequest.EDGE_MODE, EDGE_MODE)
            Log.d(TAG, "Резкость: FAST режим")

            previewRequestBuilder?.set(CaptureRequest.TONEP_MODE, TONEMAP_MODE)
            Log.d(TAG, "Тональная кривая: FAST режим")

            previewRequestBuilder?.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            Log.d(TAG, "Баланс белого: AUTO")

            try {
                previewRequestBuilder?.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 0)
                Log.d(TAG, "POST_RAW_SENSITIVITY_BOOST отключён")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "POST_RAW_SENSITIVITY_BOOST не поддерживается устройством")
            }

            previewRequest = previewRequestBuilder?.build()

            val surfaces = listOf(previewSurface, imageReader!!.surface)

            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
                            isCameraReady = true
                            captureButton.isEnabled = true
                            Log.d(TAG, "Превью запущено успешно")
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Ошибка запуска превью: ${e.message}")
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "IllegalStateException: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Не удалось настроить сессию захвата")
                        Toast.makeText(this@MainActivity, "Не удалось настроить камеру", Toast.LENGTH_SHORT).show()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка создания превью: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Некорректное состояние: ${e.message}")
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>?, width: Int, height: Int, maxWidth: Int, maxHeight: Int): Size? {
        if (choices == null) return null

        val validSizes = choices.filter { size ->
            size.width <= maxWidth && size.height <= maxHeight &&
                    size.width >= width && size.height >= height
        }

        return if (validSizes.isNotEmpty()) {
            validSizes.maxByOrNull { it.width * it.height }
        } else {
            choices.maxByOrNull { it.width * it.height }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        Log.d(TAG, "Transform configured: $viewWidth x $viewHeight")
    }

    private fun takePhoto() {
        if (!isCameraReady || cameraDevice == null) {
            Log.w(TAG, "Камера не готова для съёмки")
            Toast.makeText(this, "Камера не готова", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(imageReader!!.surface)

            captureBuilder?.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            if (HDR_ENABLED) {
                captureBuilder?.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
                captureBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            } else {
                captureBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            captureBuilder?.set(CaptureRequest.NOISE_REDUCTION_MODE, NOISE_REDUCTION_MODE)
            captureBuilder?.set(CaptureRequest.EDGE_MODE, EDGE_MODE)
            captureBuilder?.set(CaptureRequest.TONEMAP_MODE, TONEMAP_MODE)
            captureBuilder?.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            captureBuilder?.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())

            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            try {
                captureBuilder?.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 0)
            } catch (e: IllegalArgumentException) {
            }

            cameraCaptureSession?.stopRepeating()

            cameraCaptureSession?.capture(captureBuilder?.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d(TAG, "Фото захвачено успешно")
                    try {
                        session.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Ошибка возобновления превью: ${e.message}")
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e(TAG, "Захват фото не удался: ${failure.reason}")
                    Toast.makeText(this@MainActivity, "Не удалось сделать фото", Toast.LENGTH_SHORT).show()
                    try {
                        session.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Ошибка возобновления превью после ошибки: ${e.message}")
                    }
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Ошибка при захвате фото: ${e.message}")
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            try {
                cameraCaptureSession?.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
            } catch (ex: CameraAccessException) {
                Log.e(TAG, "Не удалось возобновить превью: ${ex.message}")
            }
        }
    }

    private fun getOrientation(rotation: Int): Int {
        return when (rotation) {
            android.view.Surface.ROTATION_0 -> 90
            android.view.Surface.ROTATION_90 -> 0
            android.view.Surface.ROTATION_180 -> 270
            else -> 180
        }
    }

    private fun saveImage(image: android.media.Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "IMG_$timestamp.jpg"

        var savedUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/PureCamera")
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(bytes)
                        savedUri = it
                        Log.d(TAG, "Фото сохранено через MediaStore: $fileName")
                    }
                }
            } else {
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val pureCameraDir = File(dcimDir, "PureCamera")

                if (!pureCameraDir.exists()) {
                    pureCameraDir.mkdirs()
                }

                val imageFile = File(pureCameraDir, fileName)
                FileOutputStream(imageFile).use { outputStream ->
                    outputStream.write(bytes)
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    MediaStore.Images.Media.insertImage(
                        contentResolver,
                        imageFile.absolutePath,
                        fileName,
                        null
                    )
                }

                savedUri = Uri.fromFile(imageFile)
                Log.d(TAG, "Фото сохранено напрямую: ${imageFile.absolutePath}")
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && savedUri != null) {
                val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, savedUri)
                sendBroadcast(intent)
            }

            runOnUiThread {
                lastPhotoUri = savedUri
                savedUri?.let { uri ->
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    lastPhotoPreview.setImageBitmap(bitmap)
                    inputStream?.close()
                }
                Toast.makeText(this, "Фото сохранено: $fileName", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения фото: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            isCameraReady = false
            captureButton.isEnabled = false
            Log.d(TAG, "Камера закрыта")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Прерывание при закрытии камеры: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        stopBackgroundThread()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        cameraExecutor.shutdown()
    }
}