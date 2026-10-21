import Foundation

public struct ProtocolCsvRow: Equatable, Sendable {
    public var id: Int
    public var name: String
    public var course: String
}

public struct CourseCsvRow: Equatable, Sendable {
    public var name: String
    public var controls: [Int]
}

public enum CsvImport {
    public static func protocolFile(_ text: String) throws -> [ProtocolCsvRow] {
        let lines = logicalLines(text)
        guard !lines.isEmpty else { throw SfrError.message("Файл стартового протокола пуст") }
        let delimiter = detectDelimiter(lines[0])
        var rows: [ProtocolCsvRow] = []
        for (index, line) in lines.enumerated() {
            let cols = split(line, delimiter)
            if cols.allSatisfy({ $0.trimmingCharacters(in: .whitespaces).isEmpty }) { continue }
            let first = cols.first?.trimmingCharacters(in: .whitespaces) ?? ""
            if index == 0, first.range(of: "^(номер|id|номер чипа|chip)$", options: [.regularExpression, .caseInsensitive]) != nil {
                continue
            }
            guard let id = Int(first) else {
                throw SfrError.message("Строка \(index + 1): некорректный номер «\(first)»")
            }
            guard id > 0 else { throw SfrError.message("Строка \(index + 1): номер должен быть > 0") }
            let name = cols.dropFirst().first?.trimmingCharacters(in: .whitespaces) ?? ""
            guard !name.isEmpty else { throw SfrError.message("Строка \(index + 1): не указано ФИО") }
            let course = cols.count > 2 ? cols[2].trimmingCharacters(in: .whitespaces) : ""
            rows.append(ProtocolCsvRow(id: id, name: name, course: course))
        }
        guard !rows.isEmpty else { throw SfrError.message("В файле нет участников") }
        return rows
    }

    public static func courses(_ text: String) throws -> [CourseCsvRow] {
        let lines = logicalLines(text)
        guard !lines.isEmpty else { throw SfrError.message("Файл дистанций пуст") }
        var rows: [CourseCsvRow] = []
        var seen = Set<String>()
        for (index, line) in lines.enumerated() {
            let cols = splitCourseFields(line)
            if cols.allSatisfy({ $0.isEmpty }) { continue }
            let name = cols.first?.trimmingCharacters(in: .whitespaces) ?? ""
            if index == 0, name.range(of: "^(название|дистанция|course|name)$", options: [.regularExpression, .caseInsensitive]) != nil {
                continue
            }
            guard !name.isEmpty else { throw SfrError.message("Строка \(index + 1): не указано название дистанции") }
            let key = courseKey(name)
            guard seen.insert(key).inserted else {
                throw SfrError.message("Строка \(index + 1): дистанция «\(name)» повторяется")
            }
            let dashPath = cols.first(where: isDashCoursePath)
            let controls: [Int]
            if let dashPath {
                controls = try parseDashCoursePath(dashPath, lineNo: index + 1)
            } else {
                controls = try parseSimpleControls(Array(cols.dropFirst()), lineNo: index + 1)
            }
            guard !controls.isEmpty else {
                throw SfrError.message("Строка \(index + 1): у дистанции «\(name)» нет КП")
            }
            rows.append(CourseCsvRow(name: name, controls: controls))
        }
        guard !rows.isEmpty else { throw SfrError.message("Файл дистанций пуст") }
        return rows
    }

    private static func logicalLines(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline).map { String($0).trimmingCharacters(in: CharacterSet(charactersIn: "\r")) }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private static func detectDelimiter(_ line: String) -> Character {
        let tabs = line.filter { $0 == "\t" }.count
        if tabs > 0 { return "\t" }
        let semis = line.filter { $0 == ";" }.count
        let commas = line.filter { $0 == "," }.count
        return semis >= commas ? ";" : ","
    }

    private static func split(_ line: String, _ delimiter: Character) -> [String] {
        line.split(separator: delimiter, omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private static func splitCourseFields(_ line: String) -> [String] {
        let cols = split(line, detectDelimiter(line)).filter { !$0.isEmpty }
        if !cols.isEmpty { return cols }
        return line.split(whereSeparator: \.isWhitespace).map(String.init).filter { !$0.isEmpty }
    }

    private static func isDashCoursePath(_ value: String) -> Bool {
        let parts = value.split(separator: "-").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard parts.count >= 3 else { return false }
        return isStartOrFinishToken(parts.first!) || isStartOrFinishToken(parts.last!)
    }

    private static func isStartOrFinishToken(_ token: String) -> Bool {
        guard token.count >= 2, let first = token.first?.lowercased() else { return false }
        return (first == "s" || first == "f") && token.dropFirst().allSatisfy(\.isNumber)
    }

    private static func parseDashCoursePath(_ path: String, lineNo: Int) throws -> [Int] {
        var controls: [Int] = []
        for token in path.split(separator: "-") {
            let part = token.trimmingCharacters(in: .whitespaces)
            if part.isEmpty || isStartOrFinishToken(part) { continue }
            guard let cp = Int(part) else {
                throw SfrError.message("Строка \(lineNo): некорректный номер КП «\(part)»")
            }
            guard cp > 0 else { throw SfrError.message("Строка \(lineNo): номер КП должен быть > 0") }
            controls.append(cp)
        }
        return controls
    }

    private static func parseSimpleControls(_ cols: [String], lineNo: Int) throws -> [Int] {
        var controls: [Int] = []
        for raw in cols {
            for token in raw.split(whereSeparator: { $0 == "," || $0 == " " || $0 == "\t" }) {
                let part = token.trimmingCharacters(in: .whitespaces)
                if part.isEmpty { continue }
                guard let cp = Int(part) else {
                    throw SfrError.message("Строка \(lineNo): некорректный номер КП «\(part)»")
                }
                guard cp > 0 else { throw SfrError.message("Строка \(lineNo): номер КП должен быть > 0") }
                controls.append(cp)
            }
        }
        return controls
    }
}

public func courseKey(_ name: String) -> String {
    name.trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
}
