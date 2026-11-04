import SwiftUI

@main
struct CrossPasteIOSApp: App {
    var body: some Scene {
        WindowGroup {
            DevicesView()
        }
    }
}

struct DevicesView: View {
    @State private var showingManualConnection = false
    @State private var nearbyDevice: CrossPasteEndpoint?
    @State private var message: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label("This iPhone", systemImage: "iphone")
                    if let nearbyDevice {
                        Label(nearbyDevice.name, systemImage: "desktopcomputer")
                        Text("Found CrossPaste v\(nearbyDevice.version) on \(nearbyDevice.address):\(nearbyDevice.port)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text("Device found. Pairing and clipboard sync are not connected in this first build yet.")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("No paired devices")
                                .font(.headline)
                            Text("Connect to CrossPaste on your Mac or PC to start syncing.")
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 8)
                    }
                } header: {
                    Text(nearbyDevice == nil ? "Nearby devices" : "Connection check")
                }

                Section {
                    Button {
                        showingManualConnection = true
                    } label: {
                        Label("Add device manually", systemImage: "plus.circle")
                    }
                } footer: {
                    Text("Your devices need to be on the same local network.")
                }
            }
            .navigationTitle("Devices")
            .alert("CrossPaste", isPresented: Binding(
                get: { message != nil },
                set: { if !$0 { message = nil } }
            )) {
                Button("OK", role: .cancel) { message = nil }
            } message: {
                Text(message ?? "")
            }
            .sheet(isPresented: $showingManualConnection) {
                ManualConnectionView { endpoint in
                    Task {
                        do {
                            nearbyDevice = try await CrossPasteEndpoint.discover(
                                address: endpoint.address,
                                port: endpoint.port
                            )
                        } catch {
                            message = error.localizedDescription
                        }
                    }
                }
                    .presentationDetents([.medium])
            }
        }
    }
}

struct ManualConnectionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var port = ""
    var onAdd: (CrossPasteEndpoint) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Device address") {
                    TextField("IP address", text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.decimalPad)
                    TextField("Port", text: $port)
                        .keyboardType(.numberPad)
                }
                Section {
                    Text("After connecting, enter the pairing code shown by the other device.")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Manual connection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") {
                        guard let numericPort = Int(port), (1...65535).contains(numericPort) else { return }
                        onAdd(CrossPasteEndpoint(name: address, address: address, port: numericPort))
                        dismiss()
                    }
                    .disabled(address.isEmpty || port.isEmpty)
                }
            }
        }
    }
}

struct CrossPasteEndpoint {
    let name: String
    let address: String
    let port: Int
    let version: Int

    init(name: String, address: String, port: Int, version: Int = 0) {
        self.name = name
        self.address = address
        self.port = port
        self.version = version
    }

    static func discover(address: String, port: Int) async throws -> CrossPasteEndpoint {
        guard let url = URL(string: "http://\(address):\(port)/sync/syncInfo") else {
            throw ConnectionError.invalidAddress
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 5
        let (versionData, versionResponse) = try await URLSession.shared.data(for: request)
        guard let versionResponse = versionResponse as? HTTPURLResponse,
              (200..<300).contains(versionResponse.statusCode),
              let version = Int(String(decoding: versionData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw ConnectionError.notCrossPaste
        }
        guard version == 3 else { throw ConnectionError.incompatibleVersion(version) }

        request.url = URL(string: "http://\(address):\(port)/sync/syncInfo")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
            throw ConnectionError.notCrossPaste
        }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let appInfo = root?["appInfo"] as? [String: Any]
        guard let deviceName = appInfo?["userName"] as? String,
              let instanceId = appInfo?["appInstanceId"] as? String,
              !instanceId.isEmpty else {
            throw ConnectionError.notCrossPaste
        }
        return CrossPasteEndpoint(name: deviceName, address: address, port: port, version: version)
    }
}

enum ConnectionError: LocalizedError {
    case invalidAddress
    case notCrossPaste
    case incompatibleVersion(Int)

    var errorDescription: String? {
        switch self {
        case .invalidAddress: "Enter a valid IP address and port."
        case .notCrossPaste: "Could not find a CrossPaste device at that address. Check the IP, port, and firewall."
        case .incompatibleVersion(let version): "This device uses protocol version \(version). This iPhone build supports version 3."
        }
    }
}
