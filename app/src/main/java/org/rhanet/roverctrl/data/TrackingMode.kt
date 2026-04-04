package org.rhanet.roverctrl.data

/**
 * Режимы трекинга/управления камерой.
 * Используется в VideoFragment и RoverViewModel.
 */
enum class TrackingMode {
    /** Ручное управление джойстиком */
    MANUAL,
    
    /** Автоматическое слежение за лазерной точкой */
    LASER_DOT,
    
    /** Обнаружение и трекинг объектов YOLO */
    OBJECT_TRACK,
    
    /** Стабилизация по гироскопу телефона */
    GYRO_TILT
}