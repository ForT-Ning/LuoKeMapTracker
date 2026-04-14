package com.lkwg.maptracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.lkwg.maptracker.cv.MapMatcher
import com.lkwg.maptracker.util.ConfigManager
import kotlinx.coroutines.*
import java.io.File

/**
 * 屏幕捕获服务
 * MediaProjection → ImageReader → 裁剪小地图 → ORB 匹配 → 广播结果
 */
class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "map_tracker_capture"
        const val NOTIFICATION_ID = 1

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // 匹配间隔（毫秒）
        const val MATCH_INTERVAL_MS = 200L

        // 广播匹配结果
        const val ACTION_MATCH_RESULT = "com.lkwg.maptracker.MATCH_RESULT"
        const val EXTRA_POS_X = "pos_x"
        const val EXTRA_POS_Y = "pos_y"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_ROTATION = "rotation"

        // 本地广播（同进程内更高效安全）
        const val ACTION_STATUS = "com.lkwg.maptracker.STATUS"
        const val EXTRA_STATUS_MSG = "status_msg"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val matcher = MapMatcher()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var isCapturing = false
    private var mapLoaded = false

    // 惯性导航状态
    private var lastKnownX = 0.0
    private var lastKnownY = 0.0
    private var consecutiveFails = 0

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("🚀 正在初始化..."))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(projectionCallback, null)

            setupCapture()
            loadMap()
            startLoop()
        }

        return START_NOT_STICKY
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MapTracker",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, mainHandler
        )

        Log.d(TAG, "屏幕捕获: ${screenWidth}x${screenHeight}")
        broadcastStatus("屏幕捕获已启动")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 加载大地图 - 按优先级查找：
     * 1. 配置的文件路径
     * 2. 外部存储 map_full.png
     * 3. assets/map_full.png
     */
    private fun loadMap() {
        serviceScope.launch {
            try {
                var bitmap: Bitmap? = null

                // 1. 用户配置的路径
                val configPath = ConfigManager.getMapFilePath(this@ScreenCaptureService)
                if (configPath.isNotEmpty() && File(configPath).exists()) {
                    bitmap = BitmapFactory.decodeFile(configPath)
                    Log.d(TAG, "从配置路径加载: $configPath")
                }

                // 2. 外部存储默认路径
                if (bitmap == null) {
                    val extFile = File(getExternalFilesDir(null), "map_full.png")
                    if (extFile.exists()) {
                        bitmap = BitmapFactory.decodeFile(extFile.absolutePath)
                        Log.d(TAG, "从外部存储加载: ${extFile.absolutePath}")
                    }
                }

                // 3. assets 目录
                if (bitmap == null) {
                    try {
                        val stream = assets.open("map_full.png")
                        bitmap = BitmapFactory.decodeStream(stream)
                        stream.close()
                        Log.d(TAG, "从 assets 加载")
                    } catch (_: Exception) {}
                }

                if (bitmap != null) {
                    matcher.loadFullMap(bitmap)
                    mapLoaded = true
                    showNotification("✅ 地图已加载 ${bitmap.width}x${bitmap.height}")
                    broadcastStatus("地图加载成功")
                } else {
                    showNotification("⚠️ 未找到大地图文件 map_full.png")
                    broadcastStatus("未找到大地图，请放入 map_full.png")
                    Log.w(TAG, "未找到大地图文件")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载地图失败", e)
                broadcastStatus("地图加载失败: ${e.message}")
            }
        }
    }

    private fun startLoop() {
        isCapturing = true
        captureJob = serviceScope.launch {
            while (isActive && isCapturing) {
                if (!mapLoaded) {
                    delay(1000)
                    continue
                }

                val frame = captureFrame()
                if (frame != null) {
                    processFrame(frame)
                }

                delay(MATCH_INTERVAL_MS)
            }
        }
    }

    private fun processFrame(frame: Bitmap) {
        try {
            val result = matcher.match(frame)
            frame.recycle()

            if (result != null && result.confidence >= ConfigManager.getConfidenceThreshold(this@ScreenCaptureService)) {
                lastKnownX = result.x
                lastKnownY = result.y
                consecutiveFails = 0
                broadcastResult(result)
                showNotification("📍 (${result.x.toInt()}, ${result.y.toInt()}) 置信度:${(result.confidence * 100).toInt()}%")
            } else {
                handleMatchFail()
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理帧异常", e)
            frame.recycle()
        }
    }

    private fun handleMatchFail() {
        consecutiveFails++
        when {
            consecutiveFails < 3 -> {
                // 短暂丢失，保持位置
                broadcastResult(MapMatcher.MatchResult(lastKnownX, lastKnownY, 0.1f, 0.0))
            }
            consecutiveFails < 8 -> {
                showNotification("⚠️ 位置暂时丢失... ($consecutiveFails)")
            }
            consecutiveFails % 10 == 0 -> {
                showNotification("❌ 持续丢失，等待恢复...")
                broadcastStatus("位置丢失中，可能需要调整校准参数")
            }
        }
    }

    /**
     * 从 ImageReader 截取帧并裁剪小地图区域
     */
    private fun captureFrame(): Bitmap? {
        val image: Image? = imageReader?.acquireLatestImage() ?: return null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // 从配置读取裁剪区域
            val rect = ConfigManager.getMinimapRect(this)
            val x = rect[0].coerceIn(0, fullBitmap.width - 1)
            val y = rect[1].coerceIn(0, fullBitmap.height - 1)
            val w = rect[2].coerceAtMost(fullBitmap.width - x)
            val h = rect[3].coerceAtMost(fullBitmap.height - y)

            if (w <= 0 || h <= 0) {
                fullBitmap.recycle()
                return null
            }

            val miniMap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()
            return miniMap
        } catch (e: Exception) {
            Log.e(TAG, "截取失败", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun broadcastResult(result: MapMatcher.MatchResult) {
        sendBroadcast(Intent(ACTION_MATCH_RESULT).apply {
            putExtra(EXTRA_POS_X, result.x)
            putExtra(EXTRA_POS_Y, result.y)
            putExtra(EXTRA_CONFIDENCE, result.confidence)
            putExtra(EXTRA_ROTATION, result.rotation)
        })
    }

    private fun broadcastStatus(msg: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
        })
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection 停止")
            stopCapture()
        }
    }

    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopSelf()
    }

    // ─── 通知 ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "地图追踪",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "游戏地图实时跟点"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("🗺️ 洛克王国地图追踪")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
    }

    private fun showNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        matcher.release()
        serviceScope.cancel()
    }
}
