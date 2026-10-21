import Foundation

public struct BlockWrite: Equatable, Sendable {
    public var index: Int
    public var data: Data
}

public enum SfrChipMemory {
    public static func logicalIdBlock(_ id: Int) throws -> Data {
        guard (1...maxLogicalId).contains(id) else {
            throw SfrError.message("логический номер от 1 до \(maxLogicalId)")
        }
        return Data([UInt8(id % 200), UInt8(id / 200), 0, 0])
    }

    public static func clearTimeBlock(hour: Int, minute: Int, second: Int) throws -> Data {
        guard (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else {
            throw SfrError.message("некорректное время")
        }
        return Data([0, bcd(hour), bcd(minute), bcd(second)])
    }

    public static func punchBlock(cp: Int, hour: Int, minute: Int, second: Int) throws -> Data {
        guard (1...255).contains(cp) else { throw SfrError.message("номер КП от 1 до 255") }
        guard (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else {
            throw SfrError.message("некорректное время")
        }
        return Data([UInt8(cp), bcd(hour), bcd(minute), bcd(second)])
    }

    public static func requireStationControlNumber(_ cp: Int) throws {
        guard (1...255).contains(cp), cp != cpStart, cp != cpFinish, cp != cpClear else {
            throw SfrError.message(
                "номер КП станции 1…255, кроме старта \(cpStart), финиша \(cpFinish) и очистки \(cpClear)"
            )
        }
    }

    public static func stationProgramPlan(
        command: StationCommand,
        hour: Int,
        minute: Int,
        second: Int,
        oldPointerEnd: Int?,
        blockCount: Int = blockCountMax,
        controlNumber: Int? = nil
    ) throws -> [BlockWrite] {
        guard (minBlockCount...blockCountMax).contains(blockCount) else {
            throw SfrError.message("blockCount \(blockCount)")
        }
        let zeros = Data(count: blockSize)
        let lastWritable = blockCount - 1
        let lastPunch: Int
        if let oldPointerEnd, (5...lastWritable).contains(oldPointerEnd) {
            lastPunch = oldPointerEnd
        } else {
            lastPunch = lastWritable
        }
        var plan: [BlockWrite] = []
        plan.append(BlockWrite(index: 3, data: zeros))
        plan.append(BlockWrite(index: 0, data: zeros))
        plan.append(BlockWrite(index: 1, data: zeros))
        plan.append(BlockWrite(index: 2, data: zeros))
        if lastPunch >= 7 {
            for block in 7...lastPunch {
                plan.append(BlockWrite(index: block, data: zeros))
            }
        }
        var cp = command.controlCp
        if cp == nil, let controlNumber {
            try requireStationControlNumber(controlNumber)
            cp = controlNumber
        }
        if let cp {
            plan.append(BlockWrite(index: 4, data: stationModeBlock))
            plan.append(BlockWrite(index: 5, data: try punchBlock(cp: cp, hour: hour, minute: minute, second: second)))
            plan.append(BlockWrite(index: 6, data: stationParamBlock))
        } else {
            guard command != .control else {
                throw SfrError.message("для КП нужен номер 1…255, кроме \(cpStart), \(cpFinish), \(cpClear)")
            }
            plan.append(BlockWrite(index: 4, data: command.modeBlock))
            plan.append(BlockWrite(index: 5, data: zeros))
            plan.append(BlockWrite(index: 6, data: zeros))
        }
        return plan
    }

    public static func clearPlan(
        pointerEnd: Int?,
        hour: Int,
        minute: Int,
        second: Int,
        blockCount: Int = blockCountMax
    ) throws -> [BlockWrite] {
        guard (minBlockCount...blockCountMax).contains(blockCount) else {
            throw SfrError.message("blockCount \(blockCount)")
        }
        let zeros = Data(count: blockSize)
        let lastWritable = blockCount - 1
        let lastPunch = min(max(pointerEnd ?? lastWritable, 5), lastWritable)
        var plan: [BlockWrite] = []
        plan.append(BlockWrite(index: 4, data: Data([0x05, 0, 0, 0])))
        if lastPunch >= 5 {
            for block in 5...lastPunch {
                plan.append(BlockWrite(index: block, data: zeros))
            }
        }
        plan.append(BlockWrite(index: 1, data: try clearTimeBlock(hour: hour, minute: minute, second: second)))
        plan.append(BlockWrite(index: 0, data: zeros))
        plan.append(BlockWrite(index: 2, data: zeros))
        return plan
    }

    public static func issuePlan(
        id: Int,
        pointerEnd: Int?,
        hour: Int,
        minute: Int,
        second: Int,
        blockCount: Int = blockCountMax
    ) throws -> [BlockWrite] {
        var plan = try clearPlan(
            pointerEnd: pointerEnd,
            hour: hour,
            minute: minute,
            second: second,
            blockCount: blockCount
        )
        plan.append(BlockWrite(index: 3, data: try logicalIdBlock(id)))
        return plan
    }

    public static func toWire(_ logical: Data, order: BlockOrder) -> Data {
        precondition(logical.count == blockSize)
        return order == .nfcTools ? reversed4(logical) : logical
    }

    private static func bcd(_ value: Int) -> UInt8 {
        UInt8(((value / 10) << 4) | (value % 10))
    }
}
