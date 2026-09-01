package org.rhanet.roverctrl

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.rhanet.roverctrl.tracking.ModelPreloader
import org.rhanet.roverctrl.ui.RoverViewModel
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val vm: RoverViewModel by viewModels()

    private var gpSteer = 0f
    private var gpPan = 0f
    private var gpTilt = 0f
    private var gpThrottle = 0f          // от триггеров

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        ModelPreloader.preload(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setupWithNavController(navController)

        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    // ── Стики + триггеры ───────────────────────────────────────────────
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {

            // Левый стик → только руль
            gpSteer = getCenteredAxis(event, MotionEvent.AXIS_X)

            // Правый стик → pan / tilt
            gpPan  = getCenteredAxis(event, MotionEvent.AXIS_Z)
            gpTilt = -getCenteredAxis(event, MotionEvent.AXIS_RZ)

            // Триггеры LT / RT как газ
            // На большинстве геймпадов (включая Bloody):
            //   AXIS_LTRIGGER / AXIS_BRAKE  = LT (0..1)
            //   AXIS_RTRIGGER / AXIS_GAS    = RT (0..1)
            val lt = getTrigger(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            val rt = getTrigger(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)

            // RT = вперёд, LT = назад
            gpThrottle = rt - lt

            applyGamepadDrive()
            applyGamepadTurret()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ── Кнопки ─────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X -> {
                    vm.laserOn = !vm.laserOn
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {          // B = ручник
                    vm.brakeOn = true
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    vm.setGear(1)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    vm.setGear(2)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                vm.brakeOn = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── Хелперы ────────────────────────────────────────────────────────
    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        return if (abs(value) > range.flat) value else 0f
    }

    private fun getTrigger(event: MotionEvent, primary: Int, fallback: Int): Float {
        var v = event.getAxisValue(primary)
        if (v == 0f) v = event.getAxisValue(fallback)
        return v.coerceIn(0f, 1f)
    }

    private fun applyGamepadDrive() {
        val s = vm.sensitivity.value
        val fwd = (gpThrottle * 100 * s.driveSpeedSens).toInt().coerceIn(-100, 100)
        val str = (gpSteer * 100 * s.driveSteerSens).toInt().coerceIn(-100, 100)
        vm.setDriveCmd(fwd, str, fwd)
    }

    private fun applyGamepadTurret() {
        val pan  = (gpPan  * 100).toInt().coerceIn(-100, 100)
        val tilt = (gpTilt * 100).toInt().coerceIn(-100, 100)
        vm.setPanTilt(pan, tilt)
    }
}
