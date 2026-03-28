package org.rhanet.roverctrl.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────────
// GyroTiltController
//
// Маппинг ориентации телефона (в руках оператора) → pan/tilt турели.
//
// Принцип:
//   1. При активации запоминаем текущую ориентацию как "нулевую"
//   2. Дельта yaw  → pan  (-100..100)
//   3. Дельта pitch → tilt (-100..100)
//   4. Low-pass EMA фильтр убирает дрожание рук
//   5. Dead zone у центра предотвращает микро-движения серво
//
// Использует GAME_ROTATION_VECTOR (фьюженный, без магнитного дрейфа).
// Подходит для удержания телефона в руках — нет зависимости от компаса.
//
// Использование:
//   val gyro = GyroTiltController(context)
//   gyro.start()            // регистрирует сенсор
//   gyro.zero()             // обнулить (текущее положение = центр)
//   val (pan, tilt) = gyro.output   // читать из любого потока
//   gyro.stop()             // отключить сенсор
// ──────────────────────────────────────────────────────────────────────────

class GyroTiltController(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "GyroTilt"

        // Максимальный угол наклона (°) = полный ход серво (-100..100)
        const val DEFAULT_RANGE_DEG = 40f   // ±40° наклона = ±100 команды

        // EMA фильтр: 0.0 = никакой фильтрации, 1.0 = полная (не двигается)
        const val DEFAULT_SMOOTHING = 0.65f

        // Dead zone: в пределах этого угла (°) → выход = 0
        const val DEFAULT_DEADZONE_DEG = 2.5f
    }

    // ── Настройки (можно менять на лету) ─────────────────────────────────
    var rangeDeg:    Float = DEFAULT_RANGE_DEG
    var smoothing:   Float = DEFAULT_SMOOTHING
    var deadzoneDeg: Float = DEFAULT_DEADZONE_DEG

    /** Инвертировать оси (если телефон держат иначе) */
    var invertPan:  Boolean = false
    var invertTilt: Boolean = false

    // ── Выход ────────────────────────────────────────────────────────────
    data class Output(val pan: Int, val tilt: Int)

    @Volatile var output = Output(0, 0)
        private set

    /** Сырые углы дельты (до фильтра), для отладки / HUD */
    @Volatile var rawDeltaYaw:   Float = 0f; private set
    @Volatile var rawDeltaPitch: Float = 0f; private set

    // ── Внутреннее ───────────────────────────────────────────────────────
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensor: Sensor? = null
    private var active = false

    // Baseline (обнулённая ориентация)
    private var baselineSet    = false
    private val baselineQuat   = FloatArray(4) // w, x, y, z
    private val baselineInvQuat = FloatArray(4)

    // EMA-фильтрованные значения
    private var filteredPan:  Float = 0f
    private var filteredTilt: Float = 0f

    // Буферы для расчётов (избегаем аллокации в onSensorChanged)
    private val currentQuat = FloatArray(4)
    private val deltaQuat   = FloatArray(4)
    private val rotMatrix   = FloatArray(9)
    private val euler       = FloatArray(3) // azimuth, pitch, roll

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun start() {
        if (active) return
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            Log.e(TAG, "No rotation vector sensor available!")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) // ~20ms
        active = true
        Log.i(TAG, "Started, sensor=${sensor?.name}")
    }

    fun stop() {
        if (!active) return
        sensorManager.unregisterListener(this)
        active = false
        output = Output(0, 0)
        filteredPan = 0f
        filteredTilt = 0f
        Log.i(TAG, "Stopped")
    }

    /** Обнулить: текущее положение = центр (pan=0, tilt=0) */
    fun zero() {
        baselineSet = false  // следующий onSensorChanged запишет baseline
        filteredPan = 0f
        filteredTilt = 0f
        output = Output(0, 0)
        Log.i(TAG, "Zeroed — next reading becomes baseline")
    }

    // ── SensorEventListener ──────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        // GAME_ROTATION_VECTOR: values = [x, y, z, w?]
        // Если 3 значения → w = sqrt(1 - x²-y²-z²)
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val w = if (event.values.size >= 4) event.values[3]
                else Math.sqrt((1.0 - x*x - y*y - z*z).coerceAtLeast(0.0)).toFloat()

        currentQuat[0] = w; currentQuat[1] = x; currentQuat[2] = y; currentQuat[3] = z

        if (!baselineSet) {
            baselineQuat[0] = w; baselineQuat[1] = x; baselineQuat[2] = y; baselineQuat[3] = z
            // Инверсия кватерниона (сопряжённый для единичного кватерниона)
            baselineInvQuat[0] =  w
            baselineInvQuat[1] = -x
            baselineInvQuat[2] = -y
            baselineInvQuat[3] = -z
            baselineSet = true
            Log.d(TAG, "Baseline set: w=$w x=$x y=$y z=$z")
            return
        }

        // Дельта = current * inverse(baseline)
        // Это даёт поворот от baseline к текущему положению
        quatMultiply(currentQuat, baselineInvQuat, deltaQuat)

        // Кватернион → матрица поворота → углы Эйлера
        SensorManager.getRotationMatrixFromVector(rotMatrix, floatArrayOf(
            deltaQuat[1], deltaQuat[2], deltaQuat[3], deltaQuat[0]
        ))
        SensorManager.getOrientation(rotMatrix, euler)

        // euler[0] = azimuth (yaw), euler[1] = pitch, euler[2] = roll
        val yawDeg   = Math.toDegrees(euler[0].toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(euler[1].toDouble()).toFloat()

        rawDeltaYaw = yawDeg
        rawDeltaPitch = pitchDeg

        // Маппинг: ±rangeDeg → ±100
        var panRaw  = (yawDeg / rangeDeg * 100f).coerceIn(-100f, 100f)
        var tiltRaw = (pitchDeg / rangeDeg * 100f).coerceIn(-100f, 100f)

        // Инверсия осей
        if (invertPan)  panRaw  = -panRaw
        if (invertTilt) tiltRaw = -tiltRaw

        // Dead zone
        val dzCmd = deadzoneDeg / rangeDeg * 100f
        if (abs(panRaw) < dzCmd)  panRaw = 0f
        if (abs(tiltRaw) < dzCmd) tiltRaw = 0f

        // EMA low-pass фильтр: filtered = α * old + (1-α) * new
        filteredPan  = smoothing * filteredPan  + (1f - smoothing) * panRaw
        filteredTilt = smoothing * filteredTilt + (1f - smoothing) * tiltRaw

        output = Output(filteredPan.toInt(), filteredTilt.toInt())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Quaternion multiply: result = a * b ──────────────────────────────
    // Format: [w, x, y, z]
    private fun quatMultiply(a: FloatArray, b: FloatArray, result: FloatArray) {
        result[0] = a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3]
        result[1] = a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2]
        result[2] = a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1]
        result[3] = a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0]
    }
}
