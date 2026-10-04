package io.github.yingdu

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.OutcomeReceiver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日步数。两个来源：
 *  1. Health Connect（安卓 14 起系统自带）：手环、健康 app（Fitbit 等）同步进来的步数，最准；
 *  2. 手机自带的计步传感器：只统计手机本身走的步数，而且只能从 app 开始运行后算起（第一天会偏少）。
 * 优先用 1，拿不到再用 2。
 */
class StepsProvider(private val ctx: Context, private val onChange: () -> Unit) : SensorEventListener {
    companion object {
        const val PERM_HEALTH = "android.permission.health.READ_STEPS"
        const val PERM_SENSOR = "android.permission.ACTIVITY_RECOGNITION"
    }

    var steps: Long? = null; private set
    var source = ""; private set

    private val prefs = ctx.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private var sensorOn = false

    fun healthConnectAvailable() = Build.VERSION.SDK_INT >= 34

    fun hasHealthPermission() = healthConnectAvailable() &&
        ctx.checkSelfPermission(PERM_HEALTH) == PackageManager.PERMISSION_GRANTED

    fun hasSensorPermission() = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(PERM_SENSOR) == PackageManager.PERMISSION_GRANTED

    /** 需要申请的权限。 */
    fun neededPermissions(): List<String> = buildList {
        if (healthConnectAvailable() && !hasHealthPermission()) add(PERM_HEALTH)
        if (Build.VERSION.SDK_INT >= 29 && !hasSensorPermission()) add(PERM_SENSOR)
    }

    fun refresh() {
        if (hasHealthPermission()) readHealthConnect() else startSensor()
    }

    fun stop() {
        if (sensorOn) (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        sensorOn = false
    }

    // ---------- Health Connect ----------

    @SuppressLint("NewApi")
    private fun readHealthConnect() {
        try {
            val hc = ctx.getSystemService(android.health.connect.HealthConnectManager::class.java) ?: return startSensor()
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val filter = android.health.connect.TimeInstantRangeFilter.Builder()
                .setStartTime(start).setEndTime(Instant.now()).build()
            // 用"聚合"而不是逐条相加：多个来源（手机 + 手环）同时记录时，系统会自动去重
            val req = android.health.connect.AggregateRecordsRequest.Builder<Long>(filter)
                .addAggregationType(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL)
                .build()
            hc.aggregate(req, ctx.mainExecutor,
                object : OutcomeReceiver<android.health.connect.AggregateRecordsResponse<Long>, android.health.connect.HealthConnectException> {
                    override fun onResult(r: android.health.connect.AggregateRecordsResponse<Long>) {
                        val v = r.get(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL)
                        if (v == null) { startSensor(); return }
                        steps = v
                        source = "Health Connect"
                        onChange()
                    }
                    override fun onError(e: android.health.connect.HealthConnectException) { startSensor() }
                })
        } catch (e: Exception) {
            startSensor()
        }
    }

    // ---------- 手机计步传感器 ----------

    private fun startSensor() {
        if (!hasSensorPermission()) return
        if (sensorOn) { publishSensor(); return }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        sensorOn = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // 传感器给的是开机以来的累计步数；按天记一个起点，相减得到今天的步数
        val total = e.values[0].toLong()
        val today = LocalDate.now().toString()
        var base = prefs.getLong("base", -1)
        val day = prefs.getString("day", "")
        val last = prefs.getLong("last", -1)
        when {
            day != today -> base = if (last in 0..total) last else total   // 新的一天：以昨天最后一次读数为起点
            total < base -> base = 0                                         // 重启过手机，累计数清零了
        }
        prefs.edit().putLong("base", base).putString("day", today).putLong("last", total).apply()
        sensorValue = total - base
        publishSensor()
    }

    private var sensorValue: Long? = null

    private fun publishSensor() {
        val v = sensorValue ?: return
        if (hasHealthPermission() && source == "Health Connect") return
        steps = v
        source = "手机计步"
        onChange()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
