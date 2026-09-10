package expo.modules.glasssdk.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import expo.modules.glasssdk.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class HealthTool(context: Context) : BaseTool(context) {

    override val toolName = "getHealthData"

    override val requiredPermissions = arrayOf<String>()

    companion object {
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )

        var pendingPermission: CompletableDeferred<Set<String>>? = null

        @JvmStatic
        fun onPermissionResult(granted: Set<String>) {
            pendingPermission?.complete(granted)
            pendingPermission = null
        }
    }

    private fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    override fun hasPermissions(): Boolean = isAvailable()

    suspend fun requestAuthorization(): Boolean {
        if (!isAvailable()) return false
        val act = activity ?: return false
        if (hasAllGranted()) return true

        val deferred = CompletableDeferred<Set<String>>()
        pendingPermission = deferred
        act.startActivity(Intent(context, HealthPermissionActivity::class.java))
        val granted = deferred.await()
        return granted.containsAll(READ_PERMISSIONS)
    }

    suspend fun hasAllGranted(): Boolean {
        if (!isAvailable()) return false
        return try {
            client().permissionController.getGrantedPermissions().containsAll(READ_PERMISSIONS)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun execute(params: JSONObject): String {
        if (!isAvailable()) {
            return """{"success":false,"error":"Health Connect is not available on this device"}"""
        }
        if (!hasAllGranted()) {
            return """{"success":false,"error":"Health permission not granted"}"""
        }

        return try {
            val metrics = parseMetrics(params)
            val (start, end) = parseRange(params)
            val c = client()
            val range = TimeRangeFilter.between(start, end)

            val data = JSONObject()
            if (metrics.contains("activity")) data.put("activity", readActivity(c, range))
            if (metrics.contains("vitals")) data.put("vitals", readVitals(c, range))
            if (metrics.contains("sleep")) data.put("sleep", readSleep(c, range))
            if (metrics.contains("workouts")) data.put("workouts", readWorkouts(c, range))

            JSONObject().apply {
                put("success", true)
                put("range", JSONObject().apply {
                    put("start", start.toEpochMilli())
                    put("end", end.toEpochMilli())
                })
                put("data", data)
            }.toString()
        } catch (e: Exception) {
            AppLog.e("HealthTool: read failed", e)
            """{"success":false,"error":"${e.message ?: "Failed to read health data"}"}"""
        }
    }

    private suspend fun readActivity(c: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val out = JSONObject()
        try {
            val agg = c.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
                    ),
                    timeRangeFilter = range
                )
            )
            out.put("steps", agg[StepsRecord.COUNT_TOTAL] ?: 0L)
            out.put("distanceMeters", agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0)
            out.put("activeEnergyKcal", agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0)
            out.put("flightsClimbed", agg[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: 0.0)
        } catch (e: Exception) {
            AppLog.e("HealthTool: activity aggregate failed", e)
        }
        return out
    }

    private suspend fun readVitals(c: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val out = JSONObject()

        try {
            val agg = c.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = range
                )
            )
            val hr = JSONObject()
            agg[HeartRateRecord.BPM_AVG]?.let { hr.put("average", it) }
            val records = c.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, range, ascendingOrder = false, pageSize = 1)
            ).records
            records.firstOrNull()?.samples?.lastOrNull()?.let { hr.put("latest", it.beatsPerMinute) }
            if (hr.length() > 0) out.put("heartRate", hr)
        } catch (e: Exception) {
            AppLog.e("HealthTool: heartRate failed", e)
        }

        try {
            val records = c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records
            if (records.isNotEmpty()) {
                val values = records.map { it.percentage.value }
                out.put("oxygenSaturation", JSONObject().apply {
                    put("average", values.average())
                    put("latest", records.last().percentage.value)
                })
            }
        } catch (e: Exception) {
            AppLog.e("HealthTool: spo2 failed", e)
        }

        try {
            val records = c.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range)).records
            if (records.isNotEmpty()) {
                val values = records.map { it.rate }
                out.put("respiratoryRate", JSONObject().apply {
                    put("average", values.average())
                    put("latest", records.last().rate)
                })
            }
        } catch (e: Exception) {
            AppLog.e("HealthTool: respiratory failed", e)
        }

        return out
    }

    private suspend fun readSleep(c: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val out = JSONObject()
        try {
            val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
            var asleepSeconds = 0.0
            var inBedSeconds = 0.0
            val byStage = HashMap<String, Double>()
            for (session in sessions) {
                inBedSeconds += (session.endTime.epochSecond - session.startTime.epochSecond).toDouble()
                for (stage in session.stages) {
                    val dur = (stage.endTime.epochSecond - stage.startTime.epochSecond).toDouble()
                    val (label, isAsleep) = sleepStage(stage.stage)
                    byStage[label] = (byStage[label] ?: 0.0) + dur
                    if (isAsleep) asleepSeconds += dur
                }
            }
            out.put("asleepMinutes", asleepSeconds / 60.0)
            out.put("inBedMinutes", inBedSeconds / 60.0)
            val stagesJson = JSONObject()
            byStage.forEach { (k, v) -> stagesJson.put(k, v / 60.0) }
            out.put("stagesMinutes", stagesJson)
        } catch (e: Exception) {
            AppLog.e("HealthTool: sleep failed", e)
        }
        return out
    }

    private fun sleepStage(stage: Int): Pair<String, Boolean> = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake" to false
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light" to true
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep" to true
        SleepSessionRecord.STAGE_TYPE_REM -> "rem" to true
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "asleep" to true
        else -> "unknown" to false
    }

    private suspend fun readWorkouts(c: HealthConnectClient, range: TimeRangeFilter): JSONArray {
        val arr = JSONArray()
        try {
            val sessions = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            for (s in sessions) {
                arr.put(JSONObject().apply {
                    put("type", workoutName(s.exerciseType))
                    put("start", s.startTime.toEpochMilli())
                    put("end", s.endTime.toEpochMilli())
                    put("durationMinutes", (s.endTime.epochSecond - s.startTime.epochSecond) / 60.0)
                    s.title?.let { put("title", it) }
                })
            }
        } catch (e: Exception) {
            AppLog.e("HealthTool: workouts failed", e)
        }
        return arr
    }

    private fun workoutName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        else -> "other"
    }

    private fun parseMetrics(params: JSONObject): Set<String> {
        val arr = params.optJSONArray("metrics") ?: return setOf("activity", "vitals", "sleep", "workouts")
        if (arr.length() == 0) return setOf("activity", "vitals", "sleep", "workouts")
        return (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
    }

    private fun parseRange(params: JSONObject): Pair<Instant, Instant> {
        val end = if (params.has("endDate")) Instant.ofEpochMilli(params.getLong("endDate")) else Instant.now()
        val start = if (params.has("startDate")) {
            Instant.ofEpochMilli(params.getLong("startDate"))
        } else {
            val zone = java.time.ZoneId.systemDefault()
            end.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        }
        return start to end
    }
}

class HealthPermissionActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted: Set<String> ->
        HealthTool.onPermissionResult(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestPermissions.launch(HealthTool.READ_PERMISSIONS)
        } catch (e: Throwable) {
            AppLog.e("HealthPermissionActivity: failed to launch", e)
            HealthTool.onPermissionResult(emptySet())
            finish()
        }
    }
}
