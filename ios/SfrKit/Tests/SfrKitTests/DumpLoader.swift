import Foundation
@testable import SfrKit

enum DumpLoader {
    static func loadExport(_ name: String) throws -> [Data] {
        let url = Bundle.module.url(forResource: name, withExtension: "txt", subdirectory: "Resources")
            ?? Bundle.module.url(forResource: name, withExtension: "txt")
        guard let url else { throw SfrError.message("missing \(name).txt") }
        let text = try String(contentsOf: url, encoding: .utf8)
        let re = try NSRegularExpression(
            pattern: #"\[\s*([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})\s*]\s*Сектор\s*([0-9A-Fa-f]+)"#
        )
        var byIndex: [Int: Data] = [:]
        let ns = text as NSString
        for match in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            func byte(_ i: Int) -> UInt8 {
                UInt8(ns.substring(with: match.range(at: i)), radix: 16)!
            }
            let idx = Int(ns.substring(with: match.range(at: 5)), radix: 16)!
            byIndex[idx] = Data([byte(1), byte(2), byte(3), byte(4)])
        }
        let count = (byIndex.keys.max() ?? -1) + 1
        guard count >= minBlockCount else { throw SfrError.message("export \(name) has \(count) blocks") }
        return (0..<count).map { byIndex[$0] ?? Data(count: 4) }
    }

    static func stationChip(_ command: StationCommand, controlNumber: Int? = nil) throws -> [Data] {
        var hid = Array(repeating: Data(count: 4), count: 7)
        let plan = try SfrChipMemory.stationProgramPlan(
            command: command,
            hour: 12,
            minute: 0,
            second: 0,
            oldPointerEnd: 7,
            blockCount: 64,
            controlNumber: controlNumber
        )
        for write in plan where write.index < hid.count {
            hid[write.index] = write.data
        }
        return hid
    }
}
