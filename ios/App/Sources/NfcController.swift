import Foundation
import SfrKit

#if canImport(CoreNFC)
import CoreNFC
#endif

protocol NfcTagSession: AnyObject {
    var identifier: Data { get }
    func readForMode(_ mode: ChipMode) async throws -> ChipMemoryRead
    func write(plan: [BlockWrite], order: BlockOrder) async throws
}

struct ChipMemoryRead {
    var blocks: [Data]
    var declaredBlockCount: Int
}

final class NfcController: NSObject {
    private var continuation: CheckedContinuation<NfcTagSession, Error>?
    private var kept: NfcTagSession?

#if canImport(CoreNFC)
    private var session: NFCTagReaderSession?
#endif

    func scan(completion: @escaping (Result<NfcTagSession, Error>) -> Void) {
#if canImport(CoreNFC)
        guard NFCTagReaderSession.readingAvailable else {
            completion(.failure(SfrError.message("NFC недоступен на этом устройстве")))
            return
        }
        Task {
            do {
                let tag = try await scan()
                completion(.success(tag))
            } catch {
                completion(.failure(error))
            }
        }
#else
        completion(.failure(SfrError.message("Core NFC есть только на iPhone. Соберите приложение в Xcode.")))
#endif
    }

#if canImport(CoreNFC)
    @MainActor
    private func scan() async throws -> NfcTagSession {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let session = NFCTagReaderSession(pollingOption: .iso15693, delegate: self)
            session.alertMessage = "Держите чип у верхней грани iPhone"
            self.session = session
            session.begin()
        }
    }
#endif

    func keepSession(_ tag: NfcTagSession) {
        kept = tag
    }

    func writeKept(plan: [BlockWrite], order: BlockOrder) async throws {
        guard let kept else { throw SfrError.message("Чип убран. Приложите его снова и подтвердите.") }
        try await kept.write(plan: plan, order: order)
        invalidate()
    }

    func invalidate() {
        kept = nil
#if canImport(CoreNFC)
        session?.invalidate()
        session = nil
#endif
        if let continuation {
            self.continuation = nil
            continuation.resume(throwing: SfrError.message("сессия NFC закрыта"))
        }
    }
}

#if canImport(CoreNFC)
extension NfcController: NFCTagReaderSessionDelegate {
    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        self.session = nil
        if let continuation {
            self.continuation = nil
            continuation.resume(throwing: error)
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let first = tags.first else { return }
        session.connect(to: first) { error in
            if let error {
                session.invalidate(errorMessage: error.localizedDescription)
                return
            }
            guard case .iso15693(let tag) = first else {
                session.invalidate(errorMessage: "Нужен чип ISO 15693 (SFR)")
                return
            }
            let wrapped = Iso15693Session(tag: tag, session: session)
            if let continuation = self.continuation {
                self.continuation = nil
                continuation.resume(returning: wrapped)
            }
        }
    }
}

final class Iso15693Session: NfcTagSession {
    let tag: NFCISO15693Tag
    let session: NFCTagReaderSession
    var identifier: Data { tag.identifier }

    init(tag: NFCISO15693Tag, session: NFCTagReaderSession) {
        self.tag = tag
        self.session = session
    }

    func readForMode(_ mode: ChipMode) async throws -> ChipMemoryRead {
        let declared = try await systemBlockCount()
        switch mode {
        case .finish:
            return try await readFinish(declared: declared)
        case .issue:
            return try await readAll(declared: declared)
        case .station:
            return try await readHeader(declared: declared)
        }
    }

    func write(plan: [BlockWrite], order: BlockOrder) async throws {
        for (index, item) in plan.enumerated() {
            let wire = SfrChipMemory.toWire(item.data, order: order)
            try await writeBlock(UInt8(item.index), wire)
            let check = try await readBlock(UInt8(item.index))
            guard check == wire else { throw SfrError.message("Блок \(item.index) записался неверно") }
            session.alertMessage = "Запись \(index + 1)/\(plan.count)"
        }
        session.alertMessage = "Готово"
        session.invalidate()
    }

    private func readFinish(declared: Int) async throws -> ChipMemoryRead {
        let b3 = try await readBlock(3)
        let b4 = try await readBlock(4)
        let end = min(max(SfrChipParser.maxPointerCandidate(block4: b4, blockCount: declared), 6), max(declared - 1, 4))
        let size = max(end + 1, minBlockCount)
        var blocks = Array(repeating: Data(count: blockSize), count: size)
        blocks[3] = b3
        blocks[4] = b4
        var lastOk = 4
        if end >= 5 {
            for i in 5...end {
                do {
                    blocks[i] = try await readBlock(UInt8(i))
                    lastOk = i
                } catch {
                    if lastOk < minBlockCount - 1 { throw error }
                    break
                }
            }
        }
        let ready = lastOk + 1 < blocks.count ? Array(blocks.prefix(lastOk + 1)) : blocks
        return ChipMemoryRead(blocks: ready, declaredBlockCount: declared)
    }

    private func readAll(declared: Int) async throws -> ChipMemoryRead {
        var blocks: [Data] = []
        for i in 0..<declared {
            do {
                blocks.append(try await readBlock(UInt8(i)))
            } catch {
                if blocks.count >= minBlockCount { break }
                throw error
            }
        }
        guard blocks.count >= minBlockCount else {
            throw SfrError.message("чип вернул слишком мало блоков: \(blocks.count)")
        }
        return ChipMemoryRead(blocks: blocks, declaredBlockCount: declared)
    }

    private func readHeader(declared: Int) async throws -> ChipMemoryRead {
        var blocks = Array(repeating: Data(count: blockSize), count: minBlockCount)
        blocks[3] = try await readBlock(3)
        blocks[4] = try await readBlock(4)
        return ChipMemoryRead(blocks: blocks, declaredBlockCount: declared)
    }

    private func systemBlockCount() async throws -> Int {
        let flags: [NFCISO15693RequestFlag] = [[.address, .highDataRate], [.address], [.highDataRate], []]
        for flag in flags {
            if let count = try? await tag.systemBlockCount(requestFlags: flag), count >= minBlockCount {
                return min(max(count, minBlockCount), blockCountMax)
            }
        }
        return blockCountMax
    }

    private func readBlock(_ index: UInt8) async throws -> Data {
        let flags: [NFCISO15693RequestFlag] = [[.address, .highDataRate], [.address], [.highDataRate], []]
        var last: Error = SfrError.message("блок \(index) не прочитан")
        for flag in flags {
            do {
                let data = try await tag.readSingleBlock(requestFlags: flag, blockNumber: index)
                if data.count >= blockSize { return Data(data.prefix(blockSize)) }
            } catch {
                last = error
            }
        }
        throw last
    }

    private func writeBlock(_ index: UInt8, _ data: Data) async throws {
        let flags: [NFCISO15693RequestFlag] = [
            [.address, .highDataRate, .option],
            [.address, .highDataRate],
            [.address, .option],
            [.address],
            [.highDataRate, .option],
            [.highDataRate],
            [.option],
            [],
        ]
        var last: Error = SfrError.message("блок \(index) не записан")
        for flag in flags {
            do {
                try await tag.writeSingleBlock(requestFlags: flag, blockNumber: index, dataBlock: data)
                return
            } catch {
                last = error
            }
        }
        throw last
    }
}

private extension NFCISO15693Tag {
    func systemBlockCount(requestFlags: NFCISO15693RequestFlag) async throws -> Int {
        try await withCheckedThrowingContinuation { continuation in
            self.getSystemInfo(requestFlags: requestFlags) { (result: Result<NFCISO15693SystemInfo, Error>) in
                switch result {
                case .success(let info):
                    continuation.resume(returning: info.totalBlocks)
                case .failure(let error):
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func readSingleBlock(requestFlags: NFCISO15693RequestFlag, blockNumber: UInt8) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            self.readSingleBlock(requestFlags: requestFlags, blockNumber: blockNumber) { data, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: data)
                }
            }
        }
    }

    func writeSingleBlock(requestFlags: NFCISO15693RequestFlag, blockNumber: UInt8, dataBlock: Data) async throws {
        try await withCheckedThrowingContinuation { continuation in
            self.writeSingleBlock(requestFlags: requestFlags, blockNumber: blockNumber, dataBlock: dataBlock) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}
#endif
