// Патч для добавления сглаживания в VideoFragment.kt
// Заменяем обработку LASER_DOT и OBJECT_TRACK в processXiaoFrame и processFrame

// В processXiaoFrame заменить:
/*
TrackingMode.LASER_DOT -> {
    val r = laserTracker?.process(bitmap)
    latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
    if (r != null && r.found) {
        vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
        val label = r.detection?.label ?: ""
        vm.laserOn = label == "cat"
        latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
        handler.post {
            val ov = trackingOverlay()
            ov.sourceImageWidth = bmpW
            ov.sourceImageHeight = bmpH
            ov.detection = r.detection
        }
    } else handler.post {
        val ov = trackingOverlay()
        ov.sourceImageWidth = bmpW
        ov.sourceImageHeight = bmpH
        ov.detection = null
    }
}
*/

// На:
/*
TrackingMode.LASER_DOT -> {
    val r = laserTracker?.process(bitmap)
    latency.mark(ft, LatencyTracker.Stage.INFERENCE_END)
    val smoothedDetection = laserSmoother?.smooth(r?.detection)
    if (r != null && r.found && smoothedDetection != null) {
        vm.setPanTilt(r.panDelta.toInt(), r.tiltDelta.toInt())
        val label = smoothedDetection.label ?: ""
        vm.laserOn = label == "cat"
        latency.mark(ft, LatencyTracker.Stage.CMD_SENT)
        handler.post {
            val ov = trackingOverlay()
            ov.sourceImageWidth = bmpW
            ov.sourceImageHeight = bmpH
            ov.detection = smoothedDetection
        }
    } else handler.post {
        val ov = trackingOverlay()
        ov.sourceImageWidth = bmpW
        ov.sourceImageHeight = bmpH
        ov.detection = null
    }
}
*/

// Аналогично для OBJECT_TRACK заменить r.detection на objectSmoother?.smooth(r?.detection)