import XCTest
@testable import SfrKit

final class SfrChipParserTests: XCTestCase {
    func testParsesNfcToolsExport() throws {
        let raw = try DumpLoader.loadExport("export")
        let dump = try SfrChipParser.parse(uid: "E0:02:24:01:CB:73:CD:9F", rawBlocks: raw)
        XCTAssertEqual(dump.blockOrder, .nfcTools)
        XCTAssertEqual(dump.logicalId, 606)
        XCTAssertEqual(dump.clearedAt, "11:42:41")
        XCTAssertEqual(dump.pointerEnd, 27)
        XCTAssertEqual(dump.punches.count, 22)
        XCTAssertEqual(dump.punches.first?.cp, 45)
        XCTAssertEqual(dump.punches.first?.time, "11:45:50")
        XCTAssertEqual(dump.punches.last?.cp, cpFinish)
        XCTAssertEqual(dump.punches.last?.time, "12:39:00")
        XCTAssertEqual(dump.punches[dump.punches.count - 2].cp, 100)
        XCTAssertTrue(zip(dump.punches, dump.punches.dropFirst()).allSatisfy { a, b in
            a.daySeconds <= b.daySeconds
        })
    }

    func testParses64BlockFieldDump() throws {
        let dump = try SfrChipParser.parse(uid: "uid", rawBlocks: DumpLoader.loadExport("field-64-error"))
        XCTAssertEqual(dump.blockCount, 64)
        XCTAssertEqual(dump.blockOrder, .nfcTools)
        XCTAssertEqual(dump.logicalId, 81)
        XCTAssertEqual(dump.clearedAt, "11:47:17")
        XCTAssertEqual(dump.pointerEnd, 36)
        XCTAssertEqual(dump.punches.count, 32)
        XCTAssertTrue(dump.punches.first?.isStart ?? false)
        XCTAssertTrue(dump.punches.last?.isFinish ?? false)
        XCTAssertEqual(dump.punches.first?.time, "11:51:59")
        XCTAssertEqual(dump.punches.last?.time, "12:55:12")
    }

    func testParses128BlockFieldDump() throws {
        let dump = try SfrChipParser.parse(uid: "uid", rawBlocks: DumpLoader.loadExport("field-128-ok"))
        XCTAssertEqual(dump.blockCount, 128)
        XCTAssertEqual(dump.logicalId, 98)
        XCTAssertEqual(dump.pointerEnd, 36)
        XCTAssertEqual(dump.punches.count, 32)
        XCTAssertTrue(dump.punches.last?.isFinish ?? false)
    }

    func testFinishRangeFromNfcToolsDumpMatchesFullParse() throws {
        let full = try DumpLoader.loadExport("export")
        let dumpFull = try SfrChipParser.parse(uid: "uid", rawBlocks: full)
        let end = SfrChipParser.maxPointerCandidate(block4: full[4], blockCount: full.count)
        XCTAssertEqual(end, 27)
        XCTAssertEqual(SfrChipParser.wireOrder(block3: full[3], block4: full[4], blockCount: full.count), .nfcTools)
        var sparse = Array(repeating: Data(count: 4), count: end + 1)
        sparse[3] = full[3]
        sparse[4] = full[4]
        for i in 5...end { sparse[i] = full[i] }
        let dump = try SfrChipParser.parse(uid: "uid", rawBlocks: sparse, declaredBlockCount: full.count)
        XCTAssertEqual(dump.logicalId, dumpFull.logicalId)
        XCTAssertEqual(dump.punches, dumpFull.punches)
        XCTAssertEqual(dump.blockCount, full.count)
        XCTAssertEqual(dump.blockOrder, .nfcTools)
    }

    func testHidPointerUsesFirstByte() {
        let block4 = Data([0x06, 0, 0, 0])
        let block3 = Data([0x06, 0x03, 0, 0])
        XCTAssertEqual(SfrChipParser.maxPointerCandidate(block4: block4, blockCount: 64), 6)
        XCTAssertEqual(SfrChipParser.wireOrder(block3: block3, block4: block4, blockCount: 64), .hid)
    }

    func testDetectsStationRoles() throws {
        for (command, cp) in [(StationCommand.start, cpStart), (.finish, cpFinish), (.clear, cpClear)] {
            let hid = try DumpLoader.stationChip(command)
            XCTAssertEqual(SfrChipParser.stationCommand(hid), command)
            XCTAssertEqual(SfrChipParser.stationProgramCp(hid), cp)
            XCTAssertNil(try SfrChipParser.parse(uid: "uid", rawBlocks: hid, declaredBlockCount: 64).logicalId)
            let nfc = hid.map(reversed4)
            XCTAssertEqual(SfrChipParser.stationCommand(nfc), command)
        }
        let control = try DumpLoader.stationChip(.control, controlNumber: 31)
        XCTAssertEqual(SfrChipParser.stationProgramCp(control), 31)
        XCTAssertEqual(SfrChipParser.stationCommand(control.map(reversed4)), .control)
        for command in [StationCommand.active, .passive, .battery] {
            let hid = try DumpLoader.stationChip(command)
            XCTAssertEqual(SfrChipParser.stationCommand(hid), command)
            XCTAssertNil(SfrChipParser.stationProgramCp(hid))
        }
        XCTAssertNil(SfrChipParser.stationCommand(try DumpLoader.loadExport("export")))
    }

    func testFinishRejectsChipWithoutNumber() throws {
        let empty = Array(repeating: Data(count: 4), count: 7)
        XCTAssertTrue(SfrChipParser.finishChipError(empty).contains("нет логического номера"))
        let start = try DumpLoader.stationChip(.start)
        XCTAssertTrue(SfrChipParser.finishChipError(start).contains("старт"))
    }

    func testCsvProtocol() throws {
        let rows = try CsvImport.protocolFile("31;Иванов;Спринт\n32;Петров;Спринт\n")
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].id, 31)
        XCTAssertEqual(rows[0].name, "Иванов")
    }

    func testLivePayloadHasResults() throws {
        var settings = EventSettings()
        settings.title = "Тест"
        settings.competitionDate = "2026-10-15"
        settings.slug = "test"
        let item = QueuedFinish(
            id: UUID().uuidString,
            uid: "E0:01",
            logicalId: 31,
            personName: "Иванов",
            courseName: "Спринт",
            punches: [
                Punch(cp: cpStart, hour: 10, minute: 0, second: 0),
                Punch(cp: 31, hour: 10, minute: 5, second: 0),
                Punch(cp: cpFinish, hour: 10, minute: 10, second: 0),
            ],
            createdAt: Date()
        )
        let courses = [StartCourse(name: "Спринт", controls: [cpStart, 31, cpFinish])]
        let scored = LivePublish.score(items: [item], people: [], courses: courses, settings: settings)
        let data = try LivePublish.payloadJSON(settings: settings, courses: courses, scored: scored)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(json["sport_kind"] as? String, "orient")
        XCTAssertEqual((json["results"] as? [Any])?.count, 1)
    }
}
