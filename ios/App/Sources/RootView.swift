import SwiftUI
import SfrKit
import UniformTypeIdentifiers

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { ChipScreen() }
                .tabItem { Label("Чип", systemImage: "radiowaves.right") }
                .tag(0)
            NavigationStack { ProtocolScreen() }
                .tabItem { Label("Протокол", systemImage: "list.bullet") }
                .tag(1)
            NavigationStack { ResultsScreen() }
                .tabItem { Label("Результаты", systemImage: "flag") }
                .tag(2)
            NavigationStack { SettingsScreen() }
                .tabItem { Label("Live", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(3)
        }
        .alert("Записать станцию?", isPresented: $model.confirmStation) {
            Button("Записать", role: .destructive, action: model.confirmStationWrite)
            Button("Отмена", role: .cancel, action: model.cancelStationWrite)
        } message: {
            Text("На чип будет записана станция «\(model.stationLabel())». Номер и отметки участника сотрутся.")
        }
    }
}

struct ChipScreen: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Режим", selection: $model.mode) {
                ForEach(ChipMode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .onChange(of: model.mode) { _ in model.prompt() }

            Text(model.status)
                .font(.callout)
                .foregroundStyle(.secondary)

            if let dump = model.lastDump {
                Text("UID \(dump.uid) · \(dump.logicalId.map { "№ \($0)" } ?? "без номера") · \(dump.punches.count) отметок")
                    .font(.footnote)
            }

            if model.mode == .station {
                StationPane()
            } else if model.mode == .issue {
                IssuePane()
            }

            Spacer()
            Button(model.busy ? "Читаю…" : "Сканировать чип") {
                model.scan()
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.busy)
            .frame(maxWidth: .infinity)
        }
        .padding()
        .navigationTitle("Чип")
    }
}

struct StationPane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Программа для полевой станции. USB с компьютером не используется.")
                .font(.footnote)
                .foregroundStyle(.red)
            Picker("Роль", selection: $model.station) {
                ForEach(StationChoice.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.menu)
            .onChange(of: model.station) { _ in model.prompt() }
            if model.station == .control {
                TextField("Номер КП", text: $model.stationControlNumber)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: model.stationControlNumber) { _ in model.prompt() }
            }
        }
    }
}

struct IssuePane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let person = model.selectedPerson {
                Text("\(person.id) · \(person.name)")
                    .font(.headline)
                Text(person.currentCourse.isEmpty ? "Укажите дистанцию в протоколе" : person.currentCourse)
                    .font(.footnote)
            } else {
                Text("Выберите участника на вкладке «Протокол».")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct ProtocolScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var newId = ""
    @State private var newName = ""
    @State private var newCourse = ""
    @State private var importKind: ImportKind?
    @State private var importer = false

    enum ImportKind { case protocolFile, courses }

    var body: some View {
        List {
            Section("Добавить") {
                TextField("Номер", text: $newId).keyboardType(.numberPad)
                TextField("ФИО", text: $newName)
                TextField("Дистанция", text: $newCourse)
                Button("Сохранить") {
                    if let id = Int(newId) {
                        model.addPerson(id: id, name: newName, course: newCourse)
                    }
                }
            }
            Section("Участники") {
                ForEach(filtered) { person in
                    Button {
                        model.selectedPersonId = person.id
                        model.mode = .issue
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text("\(person.id) · \(person.name)")
                                Text(person.currentCourse.isEmpty ? "без дистанции" : person.currentCourse)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.selectedPersonId == person.id {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Протокол")
        .searchable(text: $query)
        .toolbar {
            Menu("Импорт") {
                Button("CSV участников") { importKind = .protocolFile; importer = true }
                Button("CSV дистанций") { importKind = .courses; importer = true }
            }
        }
        .fileImporter(isPresented: $importer, allowedContentTypes: [.plainText, .commaSeparatedText, .data]) { result in
            guard let kind = importKind, let url = try? result.get() else { return }
            guard url.startAccessingSecurityScopedResource() else { return }
            defer { url.stopAccessingSecurityScopedResource() }
            if let text = try? String(contentsOf: url, encoding: .utf8) {
                switch kind {
                case .protocolFile: model.importProtocol(text)
                case .courses: model.importCourses(text)
                }
            }
        }
    }

    private var filtered: [StartPerson] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return model.people }
        return model.people.filter {
            $0.name.lowercased().contains(q) || String($0.id).contains(q)
        }
    }
}

struct ResultsScreen: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            if !model.liveNote.isEmpty {
                Text(model.liveNote).font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(model.scored, id: \.queue.id) { row in
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.queue.personName.isEmpty ? (row.queue.logicalId.map { "№ \($0)" } ?? "без номера") : row.queue.personName)
                        .font(.headline)
                    Text("\(row.queue.courseName) · \(row.calc.status) · \(row.calc.elapsedSeconds) с · КП \(row.calc.taken)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .swipeActions {
                    Button("Удалить", role: .destructive) { model.deleteFinish(row.queue.id) }
                }
            }
        }
        .navigationTitle("Результаты")
        .toolbar {
            Button("В live") {
                Task { await model.sendLive() }
            }
            .disabled(model.finishes.isEmpty)
        }
    }
}

struct SettingsScreen: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Form {
            Section("Старт") {
                TextField("Название", text: $model.settings.title)
                TextField("Slug", text: $model.settings.slug)
                    .textInputAutocapitalization(.never)
                TextField("Дата (YYYY-MM-DD)", text: $model.settings.competitionDate)
                    .textInputAutocapitalization(.never)
                Stepper("КВ \(model.settings.controlMinutes) мин", value: $model.settings.controlMinutes, in: 10...24 * 60, step: 5)
            }
            Section("Сайт") {
                TextField("https://сайт", text: $model.settings.apiBaseUrl)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                SecureField("Токен публикации", text: $model.settings.publishToken)
                Text("Обмена с компьютером нет: протокол на телефоне, результаты сразу в /api/live/{slug}.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Live")
        .onDisappear { model.persist() }
    }
}
