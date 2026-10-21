import Foundation

public let statusOK = "OK"
public let statusDQ = "Дисквалификация"
public let statusERR = "Ошибка"
public let statusDNS = "Не стартовал"

public struct OrientMark: Equatable, Sendable {
    public var cp: Int
    public var seconds: Int
    public var seq: Int
    public var added: Bool

    public init(cp: Int, seconds: Int, seq: Int = 0, added: Bool = false) {
        self.cp = cp
        self.seconds = seconds
        self.seq = seq
        self.added = added
    }
}

public struct OrientSettings: Equatable, Sendable {
    public var startCp: Int
    public var finishCp: Int
    public var controlMinutes: Int

    public init(startCp: Int = cpStart, finishCp: Int = cpFinish, controlMinutes: Int = 120) {
        self.startCp = startCp
        self.finishCp = finishCp
        self.controlMinutes = controlMinutes
    }
}

public struct OrientExclusion: Equatable, Sendable {
    public var courseKey: String?
    public var fromCp: Int
    public var toCp: Int
    public var maxLegSeconds: Int?
}

public struct OrientCalc: Equatable, Sendable {
    public var status: String
    public var taken: Int
    public var elapsedSeconds: Int
    public var diagnostics: [String]

    public var ok: Bool { status == statusOK }
}

public func punchesToMarks(_ punches: [Punch]) -> [OrientMark] {
    punches.enumerated().map { OrientMark(cp: $0.element.cp, seconds: $0.element.daySeconds, seq: $0.offset) }
}

public func orientMarkOrder(_ marks: [OrientMark]) -> [OrientMark] {
    marks.sorted {
        if $0.seconds != $1.seconds { return $0.seconds < $1.seconds }
        if $0.seq != $1.seq { return $0.seq < $1.seq }
        return (!$0.added && $1.added)
    }
}

public func filterCourseCps(_ controls: [Int], removed: Set<Int>) -> [Int] {
    controls.filter { !removed.contains($0) }
}

public func calculateOrientResult(
    marks: [OrientMark],
    settings: OrientSettings,
    courseName: String,
    courses: [String: [Int]],
    removedCps: Set<Int> = [],
    exclusions: [OrientExclusion] = [],
    addCpCounts: [Int: Int] = [:]
) -> OrientCalc {
    var diagnostics: [String] = []
    let name = courseName.trimmingCharacters(in: .whitespaces)
    let key = courseKey(name)
    let required: [Int]?
    if name.isEmpty {
        diagnostics.append("missing_course")
        required = nil
    } else if courses[key] == nil {
        diagnostics.append("course_unknown")
        required = nil
    } else if courses[key]!.isEmpty {
        diagnostics.append("course_empty")
        required = nil
    } else {
        required = filterCourseCps(courses[key]!, removed: removedCps)
    }
    let startCp = settings.startCp
    let sorted = orientMarkOrder(marks)
    if sorted.isEmpty {
        diagnostics.append("no_marks")
        return OrientCalc(status: statusDNS, taken: 0, elapsedSeconds: 0, diagnostics: diagnostics)
    }
    guard let startIdx = sorted.firstIndex(where: { $0.cp == startCp }) else {
        diagnostics.append("start_missing")
        return OrientCalc(status: statusDNS, taken: 0, elapsedSeconds: 0, diagnostics: diagnostics)
    }
    let finishIdx = sorted.indices.reversed().first { i in
        i > startIdx && sorted[i].cp == settings.finishCp
    }
    if finishIdx == nil { diagnostics.append("finish_missing") }
    let windowEnd = finishIdx ?? (sorted.count - 1)
    let window = Array(sorted[startIdx...windowEnd])
    let expanded = required.map { applyAddedCps(window, required: $0, addCounts: addCpCounts, startCp: startCp, finishCp: settings.finishCp) } ?? window
    let punches = expanded.map(\.cp)
    let taken = required.map { courseSubsequenceTaken(punches, required: $0) } ?? 0
    let orderBroken = required.map {
        classifyActualPunches(expanded, courseCps: $0, startCp: startCp, finishCp: settings.finishCp).contains(where: \.wrong)
    } ?? false
    let complete = required.map { taken == $0.count && !orderBroken } ?? false
    if required != nil, !complete { diagnostics.append("course_incomplete") }
    var elapsed = window.last!.seconds - window.first!.seconds
    if elapsed < 0 { elapsed += 24 * 3600 }
    if let required {
        elapsed = applyOrientExclusionRules(
            window: window,
            required: required,
            startCp: startCp,
            finishCp: settings.finishCp,
            courseKeyValue: key,
            rules: exclusions,
            elapsedSeconds: elapsed
        )
    }
    let controlSeconds = max(settings.controlMinutes, 0) * 60
    if elapsed > controlSeconds { diagnostics.append("overtime") }
    let status: String
    if diagnostics.contains(where: { ["missing_course", "course_unknown", "course_empty", "start_cp_not_configured"].contains($0) }) {
        status = statusERR
    } else if !complete || finishIdx == nil || elapsed > controlSeconds {
        status = statusDQ
    } else {
        status = statusOK
    }
    return OrientCalc(status: status, taken: taken, elapsedSeconds: elapsed, diagnostics: diagnostics)
}

public func assignPlaces(_ results: [OrientCalc]) -> [Int?] {
    let ok = results.enumerated().filter { $0.element.ok }.sorted { $0.element.elapsedSeconds < $1.element.elapsedSeconds }
    var places = Array<Int?>(repeating: nil, count: results.count)
    var place = 1
    var prevElapsed: Int?
    var seen = 0
    for item in ok {
        seen += 1
        if prevElapsed != item.element.elapsedSeconds {
            place = seen
            prevElapsed = item.element.elapsedSeconds
        }
        places[item.offset] = place
    }
    return places
}

private func applyAddedCps(
    _ marks: [OrientMark],
    required: [Int],
    addCounts: [Int: Int],
    startCp: Int,
    finishCp: Int
) -> [OrientMark] {
    if required.isEmpty || addCounts.values.allSatisfy({ $0 <= 0 }) { return marks }
    var remaining = addCounts.filter { $0.value > 0 }
    if remaining.isEmpty { return marks }
    var result = marks
    var p = 0
    if result.first?.cp == startCp && required.first != startCp { p = 1 }
    for cp in required {
        var q = p
        while q < result.count && (result[q].cp != cp || (result[q].cp == finishCp && cp != finishCp)) {
            q += 1
        }
        if q < result.count {
            p = q + 1
            continue
        }
        guard let left = remaining[cp], left > 0 else { break }
        remaining[cp] = left - 1
        var at = p
        let finishAt = result.lastIndex(where: { $0.cp == finishCp })
        if let finishAt, at >= finishAt, cp != finishCp { at = finishAt }
        let prev = at > 0 ? result[at - 1] : nil
        let next = at < result.count ? result[at] : nil
        result.insert(syntheticAddedMark(cp: cp, prev: prev, next: next), at: at)
        p = at + 1
    }
    return result
}

private func syntheticAddedMark(cp: Int, prev: OrientMark?, next: OrientMark?) -> OrientMark {
    let seconds: Int
    if let prev, let next, next.seconds > prev.seconds {
        seconds = prev.seconds + (next.seconds - prev.seconds) / 2
    } else if let prev {
        seconds = prev.seconds
    } else {
        seconds = next?.seconds ?? 0
    }
    let seq = (prev?.seconds == seconds) ? (prev?.seq ?? 0) : 0
    return OrientMark(cp: cp, seconds: seconds, seq: seq, added: true)
}

private struct ClassifiedPunch {
    var mark: OrientMark
    var kind: Kind
    var wrong: Bool { kind == .outOfOrder }
    enum Kind { case ok, start, finish, extra, outOfOrder }
}

private func classifyActualPunches(
    _ marks: [OrientMark],
    courseCps: [Int],
    startCp: Int,
    finishCp: Int
) -> [ClassifiedPunch] {
    let sorted = orientMarkOrder(marks)
    if sorted.isEmpty { return [] }
    var startIdx = sorted.firstIndex(where: { $0.cp == startCp }) ?? 0
    let finishIdx = sorted.indices.reversed().first { i in
        i > startIdx && sorted[i].cp == finishCp
    } ?? (sorted.count - 1)
    var next = 0
    var broken = false
    func afterError(_ mark: OrientMark) -> ClassifiedPunch {
        broken = true
        return ClassifiedPunch(mark: mark, kind: .outOfOrder)
    }
    func keep(_ mark: OrientMark, _ kind: ClassifiedPunch.Kind) -> ClassifiedPunch {
        if broken { return afterError(mark) }
        return ClassifiedPunch(mark: mark, kind: kind)
    }
    return sorted.enumerated().map { i, mark in
        if i < startIdx || i > finishIdx { return keep(mark, .extra) }
        if i == finishIdx && mark.cp == finishCp {
            if next < courseCps.count && courseCps[next] == finishCp { next += 1 }
            return keep(mark, .finish)
        }
        if mark.cp == startCp && (next >= courseCps.count || courseCps[next] != startCp) {
            return keep(mark, .start)
        }
        if courseCps.isEmpty { return keep(mark, .ok) }
        if next < courseCps.count && mark.cp == courseCps[next] {
            next += 1
            let kind: ClassifiedPunch.Kind
            switch mark.cp {
            case startCp: kind = .start
            case finishCp: kind = .finish
            default: kind = .ok
            }
            return keep(mark, kind)
        }
        if next < courseCps.count && courseCps[next...].contains(mark.cp) {
            let laterSame = i < finishIdx && sorted[(i + 1)...finishIdx].contains { $0.cp == mark.cp }
            if laterSame { return keep(mark, .extra) }
            return afterError(mark)
        }
        return keep(mark, courseCps.contains(mark.cp) ? .ok : .extra)
    }
}

private func courseSubsequenceTaken(_ punches: [Int], required: [Int]) -> Int {
    var p = 0
    var taken = 0
    for cp in required {
        if let q = punches[p...].firstIndex(of: cp) {
            p = q + 1
            taken += 1
        } else {
            break
        }
    }
    return taken
}

private func applyOrientExclusionRules(
    window: [OrientMark],
    required: [Int],
    startCp: Int,
    finishCp: Int,
    courseKeyValue: String?,
    rules: [OrientExclusion],
    elapsedSeconds: Int
) -> Int {
    if window.count < 2 || rules.isEmpty { return elapsedSeconds }
    var adjusted = elapsedSeconds
    for rule in rules {
        let ruleCourse = rule.courseKey?.trimmingCharacters(in: .whitespaces)
        let named = (ruleCourse?.isEmpty == false) ? ruleCourse : nil
        if let named, named != courseKeyValue { continue }
        if named == nil, courseKeyValue != nil,
           rules.contains(where: { $0.fromCp == rule.fromCp && $0.courseKey == courseKeyValue }) {
            continue
        }
        let toCp: Int
        if rule.toCp > 0 {
            toCp = rule.toCp
        } else if let resolved = resolveOrientLegTo(fromCp: rule.fromCp, required: required, startCp: startCp, finishCp: finishCp) {
            toCp = resolved
        } else {
            continue
        }
        var pending: Int?
        for mark in window {
            if mark.cp == rule.fromCp {
                pending = mark.seconds
            } else if mark.cp == toCp {
                if let t0 = pending {
                    var delta = mark.seconds - t0
                    if delta < 0 { delta += 24 * 3600 }
                    let cap = rule.maxLegSeconds.map { min(delta, $0) } ?? delta
                    adjusted = max(adjusted - cap, 0)
                }
                pending = nil
            }
        }
    }
    return adjusted
}

private func resolveOrientLegTo(fromCp: Int, required: [Int], startCp: Int, finishCp: Int) -> Int? {
    if fromCp == startCp { return required.first }
    guard let i = required.firstIndex(of: fromCp) else { return nil }
    return required.indices.contains(i + 1) ? required[i + 1] : finishCp
}
