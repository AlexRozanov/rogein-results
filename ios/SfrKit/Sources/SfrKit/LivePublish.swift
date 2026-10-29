import Foundation

public struct EventSettings: Equatable, Codable, Sendable {
    public var startCp: Int
    public var finishCp: Int
    public var controlMinutes: Int
    public var competitionDate: String
    public var apiBaseUrl: String
    public var publishToken: String
    public var slug: String
    public var title: String

    public init(
        startCp: Int = cpStart,
        finishCp: Int = cpFinish,
        controlMinutes: Int = 120,
        competitionDate: String = "",
        apiBaseUrl: String = "",
        publishToken: String = "",
        slug: String = "",
        title: String = ""
    ) {
        self.startCp = startCp
        self.finishCp = finishCp
        self.controlMinutes = controlMinutes
        self.competitionDate = competitionDate
        self.apiBaseUrl = apiBaseUrl
        self.publishToken = publishToken
        self.slug = slug
        self.title = title
    }

    public var liveReady: Bool {
        !apiBaseUrl.trimmingCharacters(in: .whitespaces).isEmpty
            && !publishToken.trimmingCharacters(in: .whitespaces).isEmpty
            && !slug.trimmingCharacters(in: .whitespaces).isEmpty
            && !title.trimmingCharacters(in: .whitespaces).isEmpty
    }

    public var normalizedBase: String {
        apiBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
}

public struct StartCourse: Equatable, Codable, Sendable {
    public var name: String
    public var controls: [Int]
    public init(name: String, controls: [Int]) {
        self.name = name
        self.controls = controls
    }
}

public struct StartPerson: Equatable, Codable, Identifiable, Sendable {
    public var id: Int
    public var name: String
    public var courses: [String]
    public var currentCourse: String
    public var issued: Bool
    public var issuedCourse: String

    public init(
        id: Int,
        name: String,
        courses: [String] = [],
        currentCourse: String = "",
        issued: Bool = false,
        issuedCourse: String = ""
    ) {
        self.id = id
        self.name = name
        self.courses = courses
        self.currentCourse = currentCourse
        self.issued = issued
        self.issuedCourse = issuedCourse
    }
}

public struct QueuedFinish: Equatable, Codable, Identifiable, Sendable {
    public var id: String
    public var uid: String
    public var logicalId: Int?
    public var personName: String
    public var courseName: String
    public var punches: [Punch]
    public var createdAt: Date
}

public struct ScoredFinish: Equatable, Sendable {
    public var queue: QueuedFinish
    public var calc: OrientCalc
    public var place: Int?
}

public enum LivePublish {
    public static func score(
        items: [QueuedFinish],
        people: [StartPerson],
        courses: [StartCourse],
        settings: EventSettings,
        removedCps: Set<Int> = []
    ) -> [ScoredFinish] {
        let courseMap = Dictionary(uniqueKeysWithValues: courses.map { (courseKey($0.name), $0.controls) })
        let orient = OrientSettings(
            startCp: settings.startCp,
            finishCp: settings.finishCp,
            controlMinutes: settings.controlMinutes
        )
        var scored = items.map { item -> ScoredFinish in
            let person = item.logicalId.flatMap { id in people.first { $0.id == id } }
            let course = item.courseName.isEmpty ? (person?.currentCourse ?? "") : item.courseName
            let calc = calculateOrientResult(
                marks: punchesToMarks(item.punches),
                settings: orient,
                courseName: course,
                courses: courseMap,
                removedCps: removedCps
            )
            var next = item
            if next.personName.isEmpty { next.personName = person?.name ?? "" }
            if next.courseName.isEmpty { next.courseName = course }
            return ScoredFinish(queue: next, calc: calc, place: nil)
        }
        let places = assignPlaces(scored.map(\.calc))
        for i in scored.indices { scored[i].place = places[i] }
        return scored
    }

    public static func payloadJSON(
        settings: EventSettings,
        courses: [StartCourse],
        scored: [ScoredFinish],
        removed: Set<Int> = []
    ) throws -> Data {
        guard !scored.isEmpty else { throw SfrError.message("Нет считанных результатов") }
        var groups: [[String: Any]] = []
        var results: [[String: Any]] = []
        var participants: [[String: Any]] = []
        var usedSourceIds = Set<Int64>()
        let byCourse = Dictionary(grouping: scored, by: { courseKey($0.queue.courseName) })
        var publishedKeys = Set<String>()

        func addGroup(groupId: Int, name: String, sortOrder: Int, controls: [Int], rows: [ScoredFinish]) {
            guard !rows.isEmpty else { return }
            groups.append([
                "source_id": groupId,
                "name": name,
                "gender_mode": "any",
                "min_age": NSNull(),
                "sort_order": sortOrder,
                "course_cps": controls,
            ])
            for row in rows {
                let sourceId = uniqueParticipantSourceId(queueId: row.queue.id, logicalId: row.queue.logicalId, used: &usedSourceIds)
                var result: [String: Any] = [
                    "participant_source_id": sourceId,
                    "award_group_source_id": groupId,
                    "points_raw": row.calc.taken,
                    "penalty_points": 0,
                    "points_final": 0,
                    "elapsed_seconds": row.calc.elapsedSeconds,
                    "status": row.calc.status,
                    "diagnostics": row.calc.diagnostics,
                ]
                if let place = row.place {
                    result["place"] = place
                } else {
                    result["place"] = NSNull()
                }
                results.append(result)
                let marks: [[String: Any]] = row.queue.punches.enumerated().map { seq, punch in
                    [
                        "seq": seq + 1,
                        "cp_number": punch.cp,
                        "mark_time": String(
                            format: "%@T%02d:%02d:%02d",
                            settings.competitionDate,
                            punch.hour,
                            punch.minute,
                            punch.second
                        ),
                    ]
                }
                var person: [String: Any] = [
                    "source_id": sourceId,
                    "bib": row.queue.logicalId.map(String.init) ?? "",
                    "chip_physical": row.queue.uid,
                    "name": row.queue.personName.isEmpty ? (row.queue.logicalId.map { "№ \($0)" } ?? "без номера") : row.queue.personName,
                    "format_name": row.queue.courseName,
                    "marks": marks,
                    "path": [] as [Any],
                    "added_cps": [:] as [String: Any],
                ]
                if let logical = row.queue.logicalId {
                    person["chip_logical"] = String(logical)
                } else {
                    person["chip_logical"] = NSNull()
                }
                participants.append(person)
            }
        }

        for (index, course) in courses.enumerated() {
            let key = courseKey(course.name)
            publishedKeys.insert(key)
            addGroup(
                groupId: index + 1,
                name: course.name,
                sortOrder: index,
                controls: filterCourseCps(course.controls, removed: removed),
                rows: byCourse[key] ?? []
            )
        }
        let extras = byCourse.filter { !publishedKeys.contains($0.key) }
        for (extraIndex, entry) in extras.enumerated() {
            let sortOrder = courses.count + extraIndex
            addGroup(
                groupId: sortOrder + 1,
                name: entry.value.first?.queue.courseName.isEmpty == false ? entry.value[0].queue.courseName : "без дистанции",
                sortOrder: sortOrder,
                controls: [],
                rows: entry.value
            )
        }
        guard !results.isEmpty else { throw SfrError.message("Нет результатов для публикации") }
        let root: [String: Any] = [
            "title": settings.title,
            "competition_date": settings.competitionDate,
            "sport_kind": "orient",
            "start_cp": settings.startCp,
            "finish_cp": settings.finishCp,
            "award_groups": groups,
            "participants": participants,
            "results": results,
            "map_points": [] as [Any],
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    public static func liveURL(settings: EventSettings) -> URL? {
        let slug = settings.slug.trimmingCharacters(in: .whitespaces)
        guard !slug.isEmpty else { return nil }
        return URL(string: "\(settings.normalizedBase)/api/live/\(slug)")
    }

    public static func uniqueParticipantSourceId(queueId: String, logicalId: Int?, used: inout Set<Int64>) -> Int64 {
        var id = stableParticipantSourceId(queueId: queueId, logicalId: logicalId)
        while id <= 0 || !used.insert(id).inserted {
            id += 1
            if id <= 0 { id = 1 }
        }
        return id
    }

    public static func stableParticipantSourceId(queueId: String, logicalId: Int?) -> Int64 {
        if let uuid = UUID(uuidString: queueId) {
            var msb: UInt64 = 0
            var lsb: UInt64 = 0
            let bytes = uuid.uuid
            msb = UInt64(bytes.0) << 56 | UInt64(bytes.1) << 48 | UInt64(bytes.2) << 40 | UInt64(bytes.3) << 32
                | UInt64(bytes.4) << 24 | UInt64(bytes.5) << 16 | UInt64(bytes.6) << 8 | UInt64(bytes.7)
            lsb = UInt64(bytes.8) << 56 | UInt64(bytes.9) << 48 | UInt64(bytes.10) << 40 | UInt64(bytes.11) << 32
                | UInt64(bytes.12) << 24 | UInt64(bytes.13) << 16 | UInt64(bytes.14) << 8 | UInt64(bytes.15)
            let hashed = Int64(bitPattern: msb) ^ Int64(bitPattern: lsb)
            let positive = hashed & 0x7FFF_FFFF
            if positive > 0 { return positive }
        }
        let hashed = Int64(truncatingIfNeeded: queueId.hashValue)
        let positive = hashed & 0x7FFF_FFFF
        if positive > 0 { return positive }
        if let chip = logicalId, chip > 0 { return Int64(chip) }
        return 1
    }
}
