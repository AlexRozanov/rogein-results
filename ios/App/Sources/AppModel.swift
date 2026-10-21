import Foundation
import SfrKit
import SwiftUI

enum ChipMode: String, CaseIterable, Identifiable {
    case issue = "Выдача"
    case finish = "Финиш"
    case station = "Станция"
    var id: String { rawValue }
}

enum StationChoice: String, CaseIterable, Identifiable {
    case control = "КП"
    case start = "Старт"
    case finish = "Финиш"
    case clear = "Очистка"
    case active = "Активный режим"
    case passive = "Пассивный режим"
    case battery = "Проверка батареи"

    var id: String { rawValue }

    var command: StationCommand {
        switch self {
        case .control: return .control
        case .start: return .start
        case .finish: return .finish
        case .clear: return .clear
        case .active: return .active
        case .passive: return .passive
        case .battery: return .battery
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var settings = EventSettings()
    @Published var people: [StartPerson] = []
    @Published var courses: [StartCourse] = []
    @Published var finishes: [QueuedFinish] = []
    @Published var mode: ChipMode = .finish
    @Published var station = StationChoice.control
    @Published var stationControlNumber = ""
    @Published var selectedPersonId: Int?
    @Published var status = "Приложите чип"
    @Published var lastDump: ChipDump?
    @Published var liveNote = ""
    @Published var busy = false
    @Published var confirmStation = false
    @Published var pendingStation: (order: BlockOrder, pointerEnd: Int?, blockCount: Int)?

    let nfc = NfcController()
    private let store = AppStore()

    init() {
        if let snapshot = store.load() {
            settings = snapshot.settings
            people = snapshot.people
            courses = snapshot.courses
            finishes = snapshot.finishes
        }
        if settings.competitionDate.isEmpty {
            let f = DateFormatter()
            f.calendar = Calendar(identifier: .gregorian)
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd"
            settings.competitionDate = f.string(from: Date())
        }
        prompt()
    }

    var scored: [ScoredFinish] {
        LivePublish.score(items: finishes, people: people, courses: courses, settings: settings)
    }

    var selectedPerson: StartPerson? {
        selectedPersonId.flatMap { id in people.first { $0.id == id } }
    }

    func persist() {
        store.save(AppSnapshot(settings: settings, people: people, courses: courses, finishes: finishes))
    }

    func prompt() {
        switch mode {
        case .issue:
            status = selectedPerson == nil
                ? "Выберите участника или приложите чип"
                : "Приложите чип — номер запишется, отметки очистятся"
        case .finish:
            status = "Приложите чип — отметки уйдут в live"
        case .station:
            if station == .control, parsedControlNumber() == nil {
                status = "Укажите номер КП (1…255, кроме 240, 241 и 243), затем приложите чип."
            } else {
                status = "Станция «\(stationLabel())». Приложите чип — запись после подтверждения."
            }
        }
    }

    func parsedControlNumber() -> Int? {
        guard let n = Int(stationControlNumber) else { return nil }
        return (1...255).contains(n) && n != cpStart && n != cpFinish && n != cpClear ? n : nil
    }

    func stationLabel() -> String {
        if station == .control, let n = parsedControlNumber() { return "КП \(n)" }
        return station.rawValue.lowercased()
    }

    func importProtocol(_ text: String) {
        do {
            let rows = try CsvImport.protocolFile(text)
            people = rows.map {
                StartPerson(id: $0.id, name: $0.name, currentCourse: $0.course, courses: $0.course.isEmpty ? [] : [$0.course])
            }
            persist()
            status = "Протокол: \(people.count) участников"
        } catch {
            status = error.localizedDescription
        }
    }

    func importCourses(_ text: String) {
        do {
            let rows = try CsvImport.courses(text)
            courses = rows.map { StartCourse(name: $0.name, controls: $0.controls) }
            persist()
            status = "Дистанции: \(courses.count)"
        } catch {
            status = error.localizedDescription
        }
    }

    func addPerson(id: Int, name: String, course: String) {
        guard (1...maxLogicalId).contains(id), !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            status = "Укажите номер (1…\(maxLogicalId)) и ФИО"
            return
        }
        people.removeAll { $0.id == id }
        people.append(StartPerson(id: id, name: name.trimmingCharacters(in: .whitespaces), currentCourse: course, courses: course.isEmpty ? [] : [course]))
        people.sort { $0.id < $1.id }
        selectedPersonId = id
        persist()
        prompt()
    }

    func scan() {
        busy = true
        status = "Приложите чип к верхней части iPhone"
        nfc.scan { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                switch result {
                case .failure(let error):
                    self.status = error.localizedDescription
                case .success(let tag):
                    await self.handleTag(tag)
                }
            }
        }
    }

    private func handleTag(_ tag: NfcTagSession) async {
        do {
            let read = try await tag.readForMode(mode)
            let dump = try SfrChipParser.parse(
                uid: androidUid(fromIosIdentifier: tag.identifier),
                rawBlocks: read.blocks,
                declaredBlockCount: read.declaredBlockCount
            )
            lastDump = dump
            switch mode {
            case .finish:
                try await handleFinish(dump, blocks: read.blocks)
                nfc.invalidate()
            case .issue:
                try await handleIssue(dump, tag: tag)
            case .station:
                handleStation(dump, tag: tag)
            }
        } catch {
            status = error.localizedDescription
        }
    }

    private func handleFinish(_ dump: ChipDump, blocks: [Data]) async throws {
        guard let logicalId = dump.logicalId else {
            status = SfrChipParser.finishChipError(blocks)
            return
        }
        let person = people.first { $0.id == logicalId }
        let item = QueuedFinish(
            id: UUID().uuidString,
            uid: dump.uid,
            logicalId: logicalId,
            personName: person?.name ?? "",
            courseName: person?.currentCourse ?? "",
            punches: dump.punches,
            createdAt: Date()
        )
        finishes.removeAll { $0.logicalId == logicalId && $0.courseName == item.courseName }
        finishes.append(item)
        persist()
        let who = person.map { "\($0.name) · № \(logicalId)" } ?? "№ \(logicalId)"
        status = "\(who) · \(dump.punches.count) отметок"
        await sendLive()
    }

    private func handleIssue(_ dump: ChipDump, tag: NfcTagSession) async throws {
        guard let person = selectedPerson ?? dump.logicalId.flatMap({ id in people.first { $0.id == id } }) else {
            status = dump.logicalId == nil
                ? "Выберите участника из списка, затем приложите чип."
                : "№ \(dump.logicalId!) нет в протоколе."
            return
        }
        guard !person.currentCourse.isEmpty else {
            selectedPersonId = person.id
            status = "Укажите дистанцию для № \(person.id), затем приложите чип."
            return
        }
        let now = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
        let plan = try SfrChipMemory.issuePlan(
            id: person.id,
            pointerEnd: dump.pointerEnd,
            hour: now.hour ?? 0,
            minute: now.minute ?? 0,
            second: now.second ?? 0,
            blockCount: dump.blockCount
        )
        try await tag.write(plan: plan, order: dump.blockOrder)
        if let i = people.firstIndex(where: { $0.id == person.id }) {
            people[i].issued = true
            people[i].issuedCourse = person.currentCourse
        }
        persist()
        status = "\(person.name) · \(person.currentCourse). Чип выдан."
    }

    private func handleStation(_ dump: ChipDump, tag: NfcTagSession) {
        if station == .control, parsedControlNumber() == nil {
            status = "Укажите номер КП (1…255, кроме 240, 241 и 243)."
            return
        }
        pendingStation = (dump.blockOrder, dump.pointerEnd, dump.blockCount)
        nfc.keepSession(tag)
        confirmStation = true
        status = "Чип на месте. Подтвердите запись станции «\(stationLabel())»."
    }

    func confirmStationWrite() {
        confirmStation = false
        guard let pending = pendingStation else { return }
        pendingStation = nil
        busy = true
        Task {
            defer { busy = false }
            do {
                let now = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
                let plan = try SfrChipMemory.stationProgramPlan(
                    command: station.command,
                    hour: now.hour ?? 0,
                    minute: now.minute ?? 0,
                    second: now.second ?? 0,
                    oldPointerEnd: pending.pointerEnd,
                    blockCount: pending.blockCount,
                    controlNumber: parsedControlNumber()
                )
                try await nfc.writeKept(plan: plan, order: pending.order)
                status = "На чип записана станция «\(stationLabel())». Приложите этот чип к станции."
            } catch {
                status = error.localizedDescription
            }
        }
    }

    func cancelStationWrite() {
        confirmStation = false
        pendingStation = nil
        nfc.invalidate()
        status = "Запись станции отменена. Чип не изменён."
    }

    func sendLive() async {
        guard settings.liveReady else {
            liveNote = "live: укажите сайт, токен, slug и название"
            return
        }
        do {
            let rows = scored
            let data = try LivePublish.payloadJSON(settings: settings, courses: courses, scored: rows)
            guard let url = LivePublish.liveURL(settings: settings) else {
                liveNote = "live: некорректный адрес"
                return
            }
            liveNote = try await LiveClient.put(url: url, token: settings.publishToken, body: data)
        } catch {
            liveNote = error.localizedDescription
        }
        persist()
    }

    func deleteFinish(_ id: String) {
        finishes.removeAll { $0.id == id }
        persist()
        Task { await sendLive() }
    }
}

struct AppSnapshot: Codable {
    var settings: EventSettings
    var people: [StartPerson]
    var courses: [StartCourse]
    var finishes: [QueuedFinish]
}

final class AppStore {
    private var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("malahit-state.json")
    }

    func load() -> AppSnapshot? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(AppSnapshot.self, from: data)
    }

    func save(_ snapshot: AppSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

enum LiveClient {
    static func put(url: URL, token: String, body: Data) async throws -> String {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 20
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8) ?? ""
        if code == 409 { throw SfrError.message("этот старт уже в архиве сайта") }
        guard (200...299).contains(code) else {
            throw SfrError.message("online \(code): \(text.prefix(200))")
        }
        return "online отправлен"
    }
}
