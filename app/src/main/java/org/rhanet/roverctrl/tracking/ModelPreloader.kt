package org.rhanet.roverctrl.tracking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Предзагрузчик TFLite модели YOLO для устранения подвисания при первом запуске детектирования.
 * Модель загружается в фоновом потоке при старте приложения.
 */
object ModelPreloader {
    private const val TAG = "ModelPreloader"
    private var preloadJob: Job? = null
    private var preloadedTracker: ObjectTracker? = null

    /**
     * Начать предзагрузку модели YOLO в фоновом потоке.
     * @param context Контекст приложения (используется для доступа к assets).
     * @param useGpu Использовать ли GPU delegate (по умолчанию true).
     */
    fun preload(context: Context, useGpu: Boolean = true) {
        if (preloadJob?.isActive == true) {
            Log.d(TAG, "Preload already in progress")
            return
        }
        Log.d(TAG, "Starting YOLO model preload in background")
        preloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val startTime = System.currentTimeMillis()
                // Создаём трекер, но не сохраняем ссылку, чтобы модель загрузилась в память
                // TFLite Interpreter кэширует модель в статическом контексте,
                // поэтому повторная инициализация будет быстрее.
                val tracker = ObjectTracker(
                    context = context.applicationContext,
                    useGpu = useGpu
                )
                val loadTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "YOLO model preloaded in ${loadTime}ms")
                // Сохраняем трекер, чтобы он не был собран GC сразу
                preloadedTracker = tracker
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload YOLO model", e)
            }
        }
    }

    /**
     * Получить предзагруженный трекер, если он готов.
     * @return ObjectTracker или null, если предзагрузка не завершена.
     */
    fun getPreloadedTracker(): ObjectTracker? = preloadedTracker

    /**
     * Очистить предзагруженный трекер (вызвать при закрытии приложения).
     */
    fun cleanup() {
        preloadJob?.cancel()
        preloadedTracker?.close()
        preloadedTracker = null
        Log.d(TAG, "Cleanup completed")
    }
}