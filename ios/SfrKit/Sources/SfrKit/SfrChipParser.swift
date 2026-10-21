import Foundation

public enum SfrChipParser {
    public static func parse(uid: String, rawBlocks: [Data], declaredBlockCount: Int? = nil) throws -> ChipDump {
        guard rawBlocks.count >= minBlockCount, rawBlocks.count <= blockCountMax else {
            throw SfrError.message("expected \(minBlockCount)…\(blockCountMax) blocks, got \(rawBlocks.count)")
        }
        for (i, block) in rawBlocks.enumerated() {
            guard block.count == blockSize else {
                throw SfrError.message("block \(i) size \(block.count)")
            }
        }
        let count = min(max(declaredBlockCount ?? rawBlocks.count, minBlockCount), blockCountMax)
        let hid = interpret(uid: uid, blocks: rawBlocks, order: .hid, declaredBlockCount: count)
        let nfc = interpret(uid: uid, blocks: rawBlocks.map(reversed4), order: .nfcTools, declaredBlockCount: count)
        return [hid, nfc].max(by: { score($0) < score($1) })!
    }

    public static func maxPointerCandidate(block4: Data, blockCount: Int) -> Int {
        precondition(block4.count == blockSize)
        let last = max(blockCount - 1, 4)
        let hid = Int(block4[0])
        let nfc = Int(block4[3])
        var end = 4
        if (5...last).contains(hid) { end = max(end, hid) }
        if (5...last).contains(nfc) { end = max(end, nfc) }
        return end
    }

    public static func wireOrder(block3: Data, block4: Data, blockCount: Int) -> BlockOrder {
        precondition(block3.count == blockSize && block4.count == blockSize)
        let last = max(blockCount - 1, 4)
        let hidEnd = (5...last).contains(Int(block4[0])) ? Int(block4[0]) : nil
        let nfcEnd = (5...last).contains(Int(block4[3])) ? Int(block4[3]) : nil
        let hidId = readLogicalId(block3)
        let nfcId = readLogicalId(reversed4(block3))
        if hidEnd != nil && nfcEnd == nil { return .hid }
        if nfcEnd != nil && hidEnd == nil { return .nfcTools }
        if hidId != nil && nfcId == nil { return .hid }
        if nfcId != nil && hidId == nil { return .nfcTools }
        if block4[1] == 0 && block4[2] == 0 && block4[3] == 0 { return .hid }
        if block4[0] == 0 && block4[1] == 0 && block4[2] == 0 { return .nfcTools }
        return .hid
    }

    public static func interpret(
        uid: String,
        blocks: [Data],
        order: BlockOrder,
        declaredBlockCount: Int? = nil
    ) -> ChipDump {
        let clearedAt = blocks.indices.contains(1) ? readClearTime(blocks[1]) : nil
        let logicalId = blocks.indices.contains(3) ? readLogicalId(blocks[3]) : nil
        let pointerEnd = blocks.indices.contains(4) ? Int(blocks[4][0]) : nil
        var punches: [Punch] = []
        let last = blocks.count - 1
        if let pointerEnd, pointerEnd >= 5 {
            let end = min(pointerEnd, last)
            if end >= 5 {
                for i in 5...end {
                    if let punch = readPunch(blocks[i]) {
                        punches.append(punch)
                    }
                }
            }
        }
        let count = min(max(declaredBlockCount ?? blocks.count, minBlockCount), blockCountMax)
        return ChipDump(
            uid: uid,
            logicalId: logicalId,
            clearedAt: clearedAt,
            punches: punches,
            blockOrder: order,
            pointerEnd: pointerEnd,
            blockCount: count
        )
    }

    public static func stationRole(_ blocks: [Data]) -> StationRole? {
        let b4 = blocks.indices.contains(4) ? blocks[4] : nil
        guard let b4 else { return nil }
        let b5 = blocks.indices.contains(5) ? blocks[5] : nil
        let b6 = blocks.indices.contains(6) ? blocks[6] : nil
        return matchStation(block4: b4, block5: b5, block6: b6)
            ?? matchStation(
                block4: reversed4(b4),
                block5: b5.map(reversed4),
                block6: b6.map(reversed4)
            )
    }

    public static func stationCommand(_ blocks: [Data]) -> StationCommand? {
        stationRole(blocks)?.command
    }

    public static func stationProgramCp(_ blocks: [Data]) -> Int? {
        stationRole(blocks)?.cp
    }

    public static func stationCommandLabel(_ command: StationCommand, cp: Int? = nil) -> String {
        let shown = cp ?? command.controlCp
        switch command {
        case .control: return "программатор станции КП \(shown.map(String.init) ?? "—")"
        case .start: return "программатор станции «старт» (КП \(cpStart))"
        case .finish: return "программатор станции «финиш» (КП \(cpFinish))"
        case .clear: return "программатор станции «очистка» (КП \(cpClear))"
        case .active: return "карта включения активного режима станции"
        case .passive: return "карта пассивного режима станции"
        case .battery: return "карта сброса и проверки батареи станции"
        }
    }

    public static func stationProgramTitle(_ cp: Int) -> String? {
        switch cp {
        case cpStart: return "старт"
        case cpFinish: return "финиш"
        case cpClear: return "очистка"
        default: return nil
        }
    }

    public static func finishChipError(_ blocks: [Data]) -> String {
        if let role = stationRole(blocks) {
            return "Этот чип нельзя принять на финише. Это \(stationCommandLabel(role.command, cp: role.cp))."
        }
        return "Этот чип нельзя принять на финише. На чипе нет логического номера участника. Сначала выдайте его на вкладке «Выдача»."
    }

    private static func matchStation(block4: Data, block5: Data?, block6: Data?) -> StationRole? {
        if block4 == stationActiveBlock { return StationRole(command: .active, cp: nil) }
        if block4 == stationPassiveBlock { return StationRole(command: .passive, cp: nil) }
        if block4 == stationBatteryBlock { return StationRole(command: .battery, cp: nil) }
        guard let block5 else { return nil }
        guard block4 == stationModeBlock else { return nil }
        if let block6, block6 != stationParamBlock { return nil }
        let cp = Int(block5[0])
        if cp == 0 { return nil }
        guard let command = try? StationCommand.fromPunchCp(cp) else { return nil }
        return StationRole(command: command, cp: cp)
    }

    private static func score(_ dump: ChipDump) -> Int {
        var s = 0
        if let end = dump.pointerEnd, end >= 5, end < dump.blockCount { s += 10 }
        s += dump.punches.count
        if dump.logicalId != nil { s += 5 }
        if dump.clearedAt != nil { s += 2 }
        if dump.punches.last?.isFinish == true { s += 4 }
        if timesAreMonotonic(dump.punches) { s += 8 }
        return s
    }

    private static func timesAreMonotonic(_ punches: [Punch]) -> Bool {
        guard punches.count >= 2 else { return true }
        var prev = -1
        for punch in punches {
            let sec = punch.daySeconds
            if sec < prev { return false }
            prev = sec
        }
        return true
    }

    private static func readClearTime(_ block: Data) -> String? {
        guard let h = hexDecimal(block[1]), let m = hexDecimal(block[2]), let s = hexDecimal(block[3]) else {
            return nil
        }
        guard h <= 23, m <= 59, s <= 59 else { return nil }
        return String(format: "%02d:%02d:%02d", h, m, s)
    }

    private static func readLogicalId(_ block: Data) -> Int? {
        let low = Int(block[0])
        let mid = Int(block[1])
        let id = low + mid * 200
        guard id > 0, id <= maxLogicalId else { return nil }
        return id
    }

    private static func readPunch(_ block: Data) -> Punch? {
        let cp = Int(block[0])
        if cp == 0 { return nil }
        guard let h = hexDecimal(block[1]), let m = hexDecimal(block[2]), let s = hexDecimal(block[3]) else {
            return nil
        }
        guard h <= 23, m <= 59, s <= 59 else { return nil }
        return Punch(cp: cp, hour: h, minute: m, second: s)
    }

    static func hexDecimal(_ b: UInt8) -> Int? {
        let v = Int(b)
        let hi = v >> 4
        let lo = v & 0x0F
        guard hi <= 9, lo <= 9 else { return nil }
        return hi * 10 + lo
    }
}
