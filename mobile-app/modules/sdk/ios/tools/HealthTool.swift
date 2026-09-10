import Foundation
import HealthKit

class HealthTool: BaseTool {

    let toolName = "getHealthData"
    let requiredPermissions = ["health"]

    private let store = HKHealthStore()

    private static let connectedKey = "glass.health.connected"

    static var readTypes: Set<HKObjectType> {
        var types = Set<HKObjectType>()
        let quantityIds: [HKQuantityTypeIdentifier] = [
            .stepCount, .distanceWalkingRunning, .activeEnergyBurned, .flightsClimbed,
            .heartRate, .restingHeartRate, .oxygenSaturation, .respiratoryRate
        ]
        for id in quantityIds {
            if let t = HKObjectType.quantityType(forIdentifier: id) { types.insert(t) }
        }
        if let sleep = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) { types.insert(sleep) }
        types.insert(HKObjectType.workoutType())
        return types
    }

    func hasPermissions() -> Bool {
        return HKHealthStore.isHealthDataAvailable()
    }

    func requestAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        let granted: Bool = await withCheckedContinuation { cont in
            store.requestAuthorization(toShare: [], read: Self.readTypes) { success, _ in
                cont.resume(returning: success)
            }
        }
        if granted { UserDefaults.standard.set(true, forKey: Self.connectedKey) }
        return granted
    }

    func isConnected() -> Bool {
        return HKHealthStore.isHealthDataAvailable()
            && UserDefaults.standard.bool(forKey: Self.connectedKey)
    }

    func execute(params: [String: Any]) async throws -> String {
        guard HKHealthStore.isHealthDataAvailable() else {
            return jsonError("Health data is not available on this device")
        }
        let metrics: Set<String> = {
            if let arr = params["metrics"] as? [String], !arr.isEmpty {
                return Set(arr.map { $0.lowercased() })
            }
            return ["activity", "vitals", "sleep", "workouts"]
        }()
        let (start, end) = parseRange(params)

        var data: [String: Any] = [:]
        if metrics.contains("activity") { data["activity"] = await readActivity(start, end) }
        if metrics.contains("vitals")   { data["vitals"]   = await readVitals(start, end) }
        if metrics.contains("sleep")    { data["sleep"]    = await readSleep(start, end) }
        if metrics.contains("workouts") { data["workouts"] = await readWorkouts(start, end) }

        let result: [String: Any] = [
            "success": true,
            "range": ["start": start.timeIntervalSince1970 * 1000,
                      "end": end.timeIntervalSince1970 * 1000],
            "data": data
        ]
        return jsonString(result)
    }

    private func readActivity(_ start: Date, _ end: Date) async -> [String: Any] {
        var out: [String: Any] = [:]
        if let steps = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            out["steps"] = await sum(steps, unit: .count(), start, end)
        }
        if let dist = HKQuantityType.quantityType(forIdentifier: .distanceWalkingRunning) {
            out["distanceMeters"] = await sum(dist, unit: .meter(), start, end)
        }
        if let energy = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) {
            out["activeEnergyKcal"] = await sum(energy, unit: .kilocalorie(), start, end)
        }
        if let flights = HKQuantityType.quantityType(forIdentifier: .flightsClimbed) {
            out["flightsClimbed"] = await sum(flights, unit: .count(), start, end)
        }
        return out
    }

    private func readVitals(_ start: Date, _ end: Date) async -> [String: Any] {
        var out: [String: Any] = [:]
        let bpm = HKUnit.count().unitDivided(by: .minute())
        if let hr = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            out["heartRate"] = await latestAndAvg(hr, unit: bpm, start, end)
        }
        if let rhr = HKQuantityType.quantityType(forIdentifier: .restingHeartRate) {
            out["restingHeartRate"] = await latestAndAvg(rhr, unit: bpm, start, end)
        }
        if let spo2 = HKQuantityType.quantityType(forIdentifier: .oxygenSaturation) {
            out["oxygenSaturation"] = await latestAndAvg(spo2, unit: .percent(), start, end)
        }
        if let resp = HKQuantityType.quantityType(forIdentifier: .respiratoryRate) {
            out["respiratoryRate"] = await latestAndAvg(resp, unit: bpm, start, end)
        }
        return out
    }

    private func readSleep(_ start: Date, _ end: Date) async -> [String: Any] {
        guard let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else {
            return [:]
        }
        let samples = await sampleQuery(sleepType, start, end)
        var asleepSeconds: Double = 0
        var inBedSeconds: Double = 0
        var byStage: [String: Double] = [:]
        for s in samples {
            guard let c = s as? HKCategorySample else { continue }
            let dur = c.endDate.timeIntervalSince(c.startDate)
            let (label, isAsleep) = Self.sleepStage(c.value)
            byStage[label, default: 0] += dur
            if c.value == HKCategoryValueSleepAnalysis.inBed.rawValue { inBedSeconds += dur }
            if isAsleep { asleepSeconds += dur }
        }
        return [
            "asleepMinutes": asleepSeconds / 60.0,
            "inBedMinutes": inBedSeconds / 60.0,
            "stagesMinutes": byStage.mapValues { $0 / 60.0 }
        ]
    }

    private static func sleepStage(_ value: Int) -> (String, Bool) {
        if #available(iOS 16.0, *) {
            switch value {
            case HKCategoryValueSleepAnalysis.inBed.rawValue: return ("inBed", false)
            case HKCategoryValueSleepAnalysis.awake.rawValue: return ("awake", false)
            case HKCategoryValueSleepAnalysis.asleepCore.rawValue: return ("core", true)
            case HKCategoryValueSleepAnalysis.asleepDeep.rawValue: return ("deep", true)
            case HKCategoryValueSleepAnalysis.asleepREM.rawValue: return ("rem", true)
            case HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue: return ("asleep", true)
            default: return ("unknown", false)
            }
        } else {
            switch value {
            case HKCategoryValueSleepAnalysis.inBed.rawValue: return ("inBed", false)
            case HKCategoryValueSleepAnalysis.awake.rawValue: return ("awake", false)
            default: return ("asleep", true)
            }
        }
    }

    private func readWorkouts(_ start: Date, _ end: Date) async -> [[String: Any]] {
        let samples = await sampleQuery(HKObjectType.workoutType(), start, end)
        var out: [[String: Any]] = []
        for s in samples {
            guard let w = s as? HKWorkout else { continue }
            var item: [String: Any] = [
                "type": Self.workoutName(w.workoutActivityType),
                "start": w.startDate.timeIntervalSince1970 * 1000,
                "end": w.endDate.timeIntervalSince1970 * 1000,
                "durationMinutes": w.duration / 60.0
            ]
            if let energy = w.totalEnergyBurned?.doubleValue(for: .kilocalorie()) {
                item["energyKcal"] = energy
            }
            if let distance = w.totalDistance?.doubleValue(for: .meter()) {
                item["distanceMeters"] = distance
            }
            out.append(item)
        }
        return out
    }

    private static func workoutName(_ type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: return "running"
        case .walking: return "walking"
        case .cycling: return "cycling"
        case .swimming: return "swimming"
        case .traditionalStrengthTraining, .functionalStrengthTraining: return "strength"
        case .highIntensityIntervalTraining: return "hiit"
        case .yoga: return "yoga"
        case .hiking: return "hiking"
        default: return "other"
        }
    }

    private func sum(_ type: HKQuantityType, unit: HKUnit, _ start: Date, _ end: Date) async -> Double {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        return await withCheckedContinuation { cont in
            let q = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: predicate,
                                      options: .cumulativeSum) { _, stats, _ in
                cont.resume(returning: stats?.sumQuantity()?.doubleValue(for: unit) ?? 0)
            }
            store.execute(q)
        }
    }

    private func latestAndAvg(_ type: HKQuantityType, unit: HKUnit, _ start: Date, _ end: Date) async -> [String: Any] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        async let avg: Double? = withCheckedContinuation { cont in
            let q = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: predicate,
                                      options: .discreteAverage) { _, stats, _ in
                cont.resume(returning: stats?.averageQuantity()?.doubleValue(for: unit))
            }
            store.execute(q)
        }
        async let latest: Double? = withCheckedContinuation { cont in
            let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
            let q = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1,
                                  sortDescriptors: [sort]) { _, samples, _ in
                let v = (samples?.first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
                cont.resume(returning: v)
            }
            store.execute(q)
        }
        var out: [String: Any] = [:]
        if let a = await avg { out["average"] = a }
        if let l = await latest { out["latest"] = l }
        return out
    }

    private func sampleQuery(_ type: HKSampleType, _ start: Date, _ end: Date) async -> [HKSample] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return await withCheckedContinuation { cont in
            let q = HKSampleQuery(sampleType: type, predicate: predicate,
                                  limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, samples, _ in
                cont.resume(returning: samples ?? [])
            }
            store.execute(q)
        }
    }

    private func parseRange(_ params: [String: Any]) -> (Date, Date) {
        let end: Date
        if let endMs = (params["endDate"] as? NSNumber)?.doubleValue {
            end = Date(timeIntervalSince1970: endMs / 1000)
        } else {
            end = Date()
        }
        let start: Date
        if let startMs = (params["startDate"] as? NSNumber)?.doubleValue {
            start = Date(timeIntervalSince1970: startMs / 1000)
        } else {
            start = Calendar.current.startOfDay(for: end)
        }
        return (start, end)
    }

    private func jsonString(_ obj: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let str = String(data: data, encoding: .utf8) else {
            return #"{"success":false,"error":"Failed to serialize health data"}"#
        }
        return str
    }

    private func jsonError(_ message: String) -> String {
        return #"{"success":false,"error":"\#(message)"}"#
    }
}
