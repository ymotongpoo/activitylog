package net.ymotongpoo.activitylog.garmin

/** Wire keys, numeric bounds and units shared by decoding, storage, UI and OTLP export. */
enum class GarminMetric(
    val key: String,
    val metricName: String,
    val label: String,
    val unit: String,
    val displayUnit: String,
    val minimum: Double,
    val maximum: Double,
    val integer: Boolean,
    /** History has a measurement timestamp; current-state APIs only have our observation time. */
    val measured: Boolean,
) {
    BODY_BATTERY("bodyBattery", "garmin.body_battery", "Body Battery", "{score}", "/100", 0.0, 100.0, true, true),
    STRESS("stress", "garmin.stress", "Stress", "{score}", "/100", 0.0, 100.0, true, true),
    HEART_RATE("heartRate", "garmin.heart_rate", "Heart rate", "{beat}/min", " bpm", 1.0, 254.0, false, true),
    OXYGEN("oxygenSaturation", "garmin.oxygen_saturation", "SpO2", "%", "%", 1.0, 100.0, false, true),
    RESPIRATION("respirationRate", "garmin.respiration_rate", "Respiration", "{breath}/min", " breaths/min", 1.0, 100.0, false, false),
    STEPS("stepsToday", "garmin.steps_today", "Steps today", "{step}", " steps", 0.0, 2_147_483_647.0, true, false),
    WATCH_BATTERY("watchBattery", "garmin.watch_battery", "Watch battery", "%", "%", 0.0, 100.0, false, false),
    SOLAR("solarIntensity", "garmin.solar_intensity", "Solar intensity", "{score}", "/100", 0.0, 100.0, true, false);

    fun reading(sample: GarminProtocol.Sample): GarminProtocol.Value? = when (this) {
        BODY_BATTERY -> sample.bodyBattery
        STRESS -> sample.stress
        else -> sample.additional[key]
    }
}
