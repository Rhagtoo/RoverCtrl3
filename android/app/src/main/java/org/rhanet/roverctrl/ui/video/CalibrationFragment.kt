package org.rhanet.roverctrl.ui.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.rhanet.roverctrl.R
import org.rhanet.roverctrl.tracking.CalibrationData
import org.rhanet.roverctrl.ui.RoverViewModel

// ──────────────────────────────────────────────────────────────────────────
// CalibrationFragment
//
// Пошаговая калибровка лазера:
//   1. Турель ставится в позицию (pan, tilt)
//   2. Пользователь совмещает крестик на экране с лазерной точкой
//   3. Нажимает Capture → записываем ориентацию телефона
//   4. Повторяем для нескольких позиций по pan и tilt
//   5. Вычисляем: визуальных градусов / серво градус
//   6. Пересчитываем PID kp
//
// Требует подключения к роверу (для отправки pan/tilt команд).
// ──────────────────────────────────────────────────────────────────────────

class CalibrationFragment : Fragment(), SensorEventListener {

    companion object {
        private const val TAG = "Calibration"

        // Калибровочные позиции серво (в единицах -100..100)
        // Pan: 5 точек от -60 до +60 (серво ~63° .. ~117°)
        private val PAN_STEPS  = intArrayOf(-60, -30, 0, 30, 60)
        // Tilt: 5 точек от -40 до +40 (серво ~48° .. ~112°)
        private val TILT_STEPS = intArrayOf(-40, -20, 0, 20, 40)
    }

    private val vm: RoverViewModel by activityViewModels()

    private lateinit var previewView: PreviewView
    private lateinit var tvStep:      TextView
    private lateinit var tvServo:     TextView
    private lateinit var tvHint:      TextView
    private lateinit var btnCapture:  Button
    private lateinit var btnSkip:     Button
    private lateinit var btnCancel:   Button

    private var cameraProvider: ProcessCameraProvider? = null

    // ── Sensor ───────────────────────────────────────────────────────────
    private var sensorManager: SensorManager? = null
    private val orientation = FloatArray(3)       // azimuth, pitch, roll (°)
    private val rotMatrix   = FloatArray(9)
    private val rotValues   = FloatArray(3)

    // ── Calibration state ────────────────────────────────────────────────

    private enum class Phase { PAN, TILT, DONE }

    private var phase     = Phase.PAN
    private var stepIndex = 0

    // Captured points: (servoDegrees, phoneDegrees)
    private val panPoints  = mutableListOf<Pair<Float, Float>>()
    private val tiltPoints = mutableListOf<Pair<Float, Float>>()

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_calibration, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView = view.findViewById(R.id.calib_preview)
        tvStep      = view.findViewById(R.id.tv_calib_step)
        tvServo     = view.findViewById(R.id.tv_calib_servo)
        tvHint      = view.findViewById(R.id.tv_calib_hint)
        btnCapture  = view.findViewById(R.id.btn_calib_capture)
        btnSkip     = view.findViewById(R.id.btn_calib_skip)
        btnCancel   = view.findViewById(R.id.btn_calib_cancel)

        // Sensor
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Buttons
        btnCapture.setOnClickListener { capturePoint() }
        btnSkip.setOnClickListener    { nextStep() }
        btnCancel.setOnClickListener  { findNavController().popBackStack() }

        // Check connection
        if (!vm.connected.value) {
            Toast.makeText(requireContext(),
                "Connect to rover first (Settings tab)", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
            return
        }

        // Start camera
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }

        // Start calibration
        phase = Phase.PAN
        stepIndex = 0
        panPoints.clear()
        tiltPoints.clear()
        applyStep()
    }

    override fun onResume() {
        super.onResume()
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // ── Sensor callbacks ─────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        SensorManager.getOrientation(rotMatrix, rotValues)

        // Радианы → градусы
        orientation[0] = Math.toDegrees(rotValues[0].toDouble()).toFloat()  // azimuth
        orientation[1] = Math.toDegrees(rotValues[1].toDouble()).toFloat()  // pitch
        orientation[2] = Math.toDegrees(rotValues[2].toDouble()).toFloat()  // roll
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Camera ───────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            provider.unbindAll()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.bindToLifecycle(
                viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── Calibration logic ────────────────────────────────────────────────

    private val currentSteps: IntArray
        get() = when (phase) {
            Phase.PAN  -> PAN_STEPS
            Phase.TILT -> TILT_STEPS
            Phase.DONE -> intArrayOf()
        }

    private val totalSteps: Int
        get() = PAN_STEPS.size + TILT_STEPS.size

    private val currentGlobalStep: Int
        get() = when (phase) {
            Phase.PAN  -> stepIndex + 1
            Phase.TILT -> PAN_STEPS.size + stepIndex + 1
            Phase.DONE -> totalSteps
        }

    private fun applyStep() {
        if (phase == Phase.DONE) {
            finishCalibration()
            return
        }

        val steps = currentSteps
        if (stepIndex >= steps.size) {
            // Переход к следующей фазе
            if (phase == Phase.PAN) {
                phase = Phase.TILT
                stepIndex = 0
                applyStep()
                return
            } else {
                phase = Phase.DONE
                finishCalibration()
                return
            }
        }

        val cmd = steps[stepIndex]

        // Отправляем команду турели
        when (phase) {
            Phase.PAN -> {
                vm.setPanTilt(cmd, 0)     // pan=cmd, tilt=0
                val servoDeg = CalibrationData.panCmdToDeg(cmd)
                tvServo.text = "Pan: ${servoDeg.toInt()}°  Tilt: center"
                tvHint.text = "Совместите крестик с лазерной точкой\n(калибровка PAN, позиция ${cmd})"
            }
            Phase.TILT -> {
                vm.setPanTilt(0, cmd)     // pan=0, tilt=cmd
                val servoDeg = CalibrationData.tiltCmdToDeg(cmd)
                tvServo.text = "Pan: center  Tilt: ${servoDeg.toInt()}°"
                tvHint.text = "Совместите крестик с лазерной точкой\n(калибровка TILT, позиция ${cmd})"
            }
            else -> {}
        }

        tvStep.text = "Step ${currentGlobalStep}/${totalSteps}"
        btnCapture.text = "CAPTURE"
        btnCapture.isEnabled = true

        Log.d(TAG, "Step: phase=$phase idx=$stepIndex cmd=$cmd")
    }

    private fun capturePoint() {
        val cmd = currentSteps[stepIndex]

        when (phase) {
            Phase.PAN -> {
                val servoDeg = CalibrationData.panCmdToDeg(cmd)
                val phoneAz  = orientation[0]   // azimuth
                panPoints.add(servoDeg to phoneAz)
                Log.d(TAG, "PAN capture: servo=${servoDeg}° phone_az=${phoneAz}°")
            }
            Phase.TILT -> {
                val servoDeg = CalibrationData.tiltCmdToDeg(cmd)
                val phonePitch = orientation[1]  // pitch
                tiltPoints.add(servoDeg to phonePitch)
                Log.d(TAG, "TILT capture: servo=${servoDeg}° phone_pitch=${phonePitch}°")
            }
            else -> return
        }

        // Visual feedback
        btnCapture.text = "✓"
        btnCapture.isEnabled = false

        // Следующий шаг через 300мс (даём серво время доехать)
        btnCapture.postDelayed({ nextStep() }, 300)
    }

    private fun nextStep() {
        stepIndex++
        applyStep()
    }

    private fun finishCalibration() {
        // Вернуть турель в центр
        vm.setPanTilt(0, 0)

        if (panPoints.size < 2 && tiltPoints.size < 2) {
            Toast.makeText(requireContext(),
                "Недостаточно точек для калибровки", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val calib = CalibrationData.compute(panPoints, tiltPoints)
        CalibrationData.save(requireContext(), calib)

        // Применяем к PID
        val panKp  = calib.optimalPanKp()
        val tiltKp = calib.optimalTiltKp()
        vm.pidPan.kp  = panKp
        vm.pidTilt.kp = tiltKp

        Log.i(TAG, "Calibration done! panKp=$panKp tiltKp=$tiltKp")
        Log.i(TAG, "  panVisualPerServo=${calib.panVisualPerServo}")
        Log.i(TAG, "  tiltVisualPerServo=${calib.tiltVisualPerServo}")

        Toast.makeText(requireContext(),
            "Calibration done!\nPan Kp=${panKp.toInt()} Tilt Kp=${tiltKp.toInt()}",
            Toast.LENGTH_LONG).show()

        tvHint.text = "Pan Kp=${panKp.toInt()}  Tilt Kp=${tiltKp.toInt()}\n" +
                      "Pan: ${calib.panVisualPerServo}°/servo°  " +
                      "Tilt: ${calib.tiltVisualPerServo}°/servo°"
        tvStep.text = "Done!"
        btnCapture.text = "OK"
        btnCapture.isEnabled = true
        btnCapture.setOnClickListener { findNavController().popBackStack() }
        btnSkip.visibility = View.GONE
        btnCancel.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
    }
}
