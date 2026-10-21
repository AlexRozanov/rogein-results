import Foundation

public let blockCountMax = 128
public let minBlockCount = 5
public let blockSize = 4
public let cpFinish = 240
public let cpStart = 241
public let cpClear = 243
public let maxLogicalId = 39_999

public struct Punch: Equatable, Codable, Sendable {
    public var cp: Int
    public var hour: Int
    public var minute: Int
    public var second: Int

    public init(cp: Int, hour: Int, minute: Int, second: Int) {
        self.cp = cp
        self.hour = hour
        self.minute = minute
        self.second = second
    }

    public var time: String {
        String(format: "%02d:%02d:%02d", hour, minute, second)
    }

    public var isStart: Bool { cp == cpStart }
    public var isFinish: Bool { cp == cpFinish }
    public var daySeconds: Int { hour * 3600 + minute * 60 + second }
}

public struct ChipDump: Equatable, Sendable {
    public var uid: String
    public var logicalId: Int?
    public var clearedAt: String?
    public var punches: [Punch]
    public var blockOrder: BlockOrder
    public var pointerEnd: Int?
    public var blockCount: Int
}

public enum BlockOrder: String, Equatable, Sendable {
    case hid
    case nfcTools
}

public enum StationCommand: Equatable, Sendable {
    case control
    case start
    case finish
    case clear
    case active
    case passive
    case battery

    public var controlCp: Int? {
        switch self {
        case .start: return cpStart
        case .finish: return cpFinish
        case .clear: return cpClear
        case .control, .active, .passive, .battery: return nil
        }
    }

    public var modeBlock: Data {
        switch self {
        case .control, .start, .finish, .clear: return stationModeBlock
        case .active: return stationActiveBlock
        case .passive: return stationPassiveBlock
        case .battery: return stationBatteryBlock
        }
    }

    public static func fromPunchCp(_ cp: Int) throws -> StationCommand {
        switch cp {
        case cpStart: return .start
        case cpFinish: return .finish
        case cpClear: return .clear
        case 1...255: return .control
        default: throw SfrError.message("номер КП станции 1…255")
        }
    }
}

public struct StationRole: Equatable, Sendable {
    public var command: StationCommand
    public var cp: Int?
}

public let stationModeBlock = Data([0x00, 0x01, 0x00, 0x00])
public let stationParamBlock = Data([0x00, 0x25, 0x00, 0x00])
public let stationActiveBlock = Data([0x02, 0x02, 0x00, 0x00])
public let stationPassiveBlock = Data([0x03, 0x03, 0x03, 0x03])
public let stationBatteryBlock = Data([0x05, 0x05, 0x05, 0x05])

public enum SfrError: Error, LocalizedError, Equatable {
    case message(String)

    public var errorDescription: String? {
        switch self {
        case .message(let text): return text
        }
    }
}

public func reversed4(_ block: Data) -> Data {
    precondition(block.count == 4)
    return Data([block[3], block[2], block[1], block[0]])
}

public func formatUid(_ bytes: Data) -> String {
    bytes.map { String(format: "%02X", $0) }.joined(separator: ":")
}

/// Android NfcV UID is little-endian; Core NFC identifier is big-endian.
public func androidUid(fromIosIdentifier identifier: Data) -> String {
    formatUid(Data(identifier.reversed()))
}
