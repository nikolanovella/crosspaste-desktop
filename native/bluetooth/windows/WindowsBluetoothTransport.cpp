#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <shellapi.h>
#include <bluetoothapis.h>
#include <atomic>
#include <cstdint>
#include <cwchar>
#include <cstring>
#include <string>
#include "../common/CrossPasteBluetoothService.h"

namespace {
bool ParseAddress(const std::wstring& input, std::uint64_t& result) {
    if (input.size() != 17 || (input[2] != L':' && input[2] != L'-')) return false;
    const wchar_t separator = input[2];
    std::uint64_t value = 0;
    for (size_t index = 0; index < input.size(); ++index) {
        if (index % 3 == 2) { if (input[index] != separator) return false; continue; }
        const wchar_t digit = input[index];
        const int number = digit >= L'0' && digit <= L'9' ? digit-L'0'
            : digit >= L'a' && digit <= L'f' ? digit-L'a'+10
            : digit >= L'A' && digit <= L'F' ? digit-L'A'+10 : -1;
        if (number < 0) return false;
        value = (value << 4) | static_cast<unsigned>(number);
    }
    if (value == 0 || value == 0xffffffffffffULL) return false;
    result = value;
    return true;
}

GUID ServiceGuid() {
    const auto* bytes = CrossPasteBluetoothServiceUuid;
    GUID result{};
    result.Data1 = (static_cast<ULONG>(bytes[0]) << 24) | (static_cast<ULONG>(bytes[1]) << 16) | (bytes[2] << 8) | bytes[3];
    result.Data2 = static_cast<USHORT>((bytes[4] << 8) | bytes[5]);
    result.Data3 = static_cast<USHORT>((bytes[6] << 8) | bytes[7]);
    for (size_t i = 0; i < 8; ++i) result.Data4[i] = bytes[i + 8];
    return result;
}

bool WriteAll(HANDLE destination, const void* bytes, DWORD size) {
    const auto* source = static_cast<const unsigned char*>(bytes);
    while (size) {
        DWORD written = 0;
        if (!WriteFile(destination, source, size, &written, nullptr) || !written) return false;
        source += written; size -= written;
    }
    return true;
}

void Status(const char* message, int code = 0) {
    std::string line = std::string(message) + (code ? ":" + std::to_string(code) : "") + "\n";
    WriteAll(GetStdHandle(STD_ERROR_HANDLE), line.data(), static_cast<DWORD>(line.size()));
}

std::string JsonString(const std::wstring& value) {
    int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string utf8(static_cast<size_t>(count), '\0');
    if (count) WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), utf8.data(), count, nullptr, nullptr);
    std::string result = "\"";
    const char* hex = "0123456789abcdef";
    for (unsigned char c : utf8) {
        if (c == '"' || c == '\\') { result += '\\'; result += c; }
        else if (c < 0x20) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += c;
    }
    return result + "\"";
}

std::wstring AddressString(std::uint64_t address) {
    wchar_t output[18]{};
    std::swprintf(output, 18, L"%02X:%02X:%02X:%02X:%02X:%02X",
        static_cast<unsigned>((address >> 40) & 255), static_cast<unsigned>((address >> 32) & 255),
        static_cast<unsigned>((address >> 24) & 255), static_cast<unsigned>((address >> 16) & 255),
        static_cast<unsigned>((address >> 8) & 255), static_cast<unsigned>(address & 255));
    return output;
}

struct Radio {
    HANDLE handle = nullptr;
    Radio() {
        BLUETOOTH_FIND_RADIO_PARAMS parameters{sizeof(parameters)};
        auto search = BluetoothFindFirstRadio(&parameters, &handle);
        if (search) BluetoothFindRadioClose(search);
    }
    ~Radio() { if (handle) CloseHandle(handle); }
};

int InspectBluetooth() {
    Radio radio;
    BLUETOOTH_RADIO_INFO info{sizeof(info)};
    bool available = radio.handle && BluetoothGetRadioInfo(radio.handle, &info) == ERROR_SUCCESS;
    std::string json = "{\"version\":1,\"radioAvailable\":" + std::string(available ? "true" : "false")
        + ",\"connectable\":" + (available && BluetoothIsConnectable(radio.handle) ? "true" : "false")
        + ",\"localAddress\":" + (available ? JsonString(AddressString(info.address.ullLong)) : "null") + ",\"devices\":[";
    if (available) {
        BLUETOOTH_DEVICE_SEARCH_PARAMS parameters{}; parameters.dwSize = sizeof(parameters);
        parameters.fReturnAuthenticated = TRUE; parameters.fReturnRemembered = TRUE; parameters.fReturnConnected = TRUE;
        // Inspect known devices only. Never turn on inquiry, change discovery,
        // power the radio, or create a bond from this metadata command.
        parameters.fIssueInquiry = FALSE; parameters.hRadio = radio.handle;
        BLUETOOTH_DEVICE_INFO device{}; device.dwSize = sizeof(device);
        auto search = BluetoothFindFirstDevice(&parameters, &device);
        if (search) {
            unsigned count = 0;
            do {
                if (count++) json += ',';
                json += "{\"address\":" + JsonString(AddressString(device.Address.ullLong))
                    + ",\"name\":" + JsonString(device.szName)
                    + ",\"paired\":" + (device.fAuthenticated ? "true" : "false")
                    + ",\"remembered\":" + (device.fRemembered ? "true" : "false") + "}";
            } while (count < 256 && BluetoothFindNextDevice(search, &device));
            BluetoothFindDeviceClose(search);
        }
    }
    json += "]}\n";
    return WriteAll(GetStdHandle(STD_OUTPUT_HANDLE), json.data(), static_cast<DWORD>(json.size())) ? 0 : 3;
}

int PairDevice(const std::wstring& input) {
    std::uint64_t address = 0;
    if (!ParseAddress(input, address)) return 2;
    Radio radio;
    BLUETOOTH_DEVICE_INFO device{}; device.dwSize = sizeof(device); device.Address.ullLong = address;
    bool paired = radio.handle && BluetoothGetDeviceInfo(radio.handle, &device) == ERROR_SUCCESS && device.fAuthenticated;
    // NULL OOB data and no authentication callback leave confirmation entirely
    // to Windows' normal pairing UI. No passkey is supplied/read/auto-accepted.
    DWORD error = paired ? ERROR_SUCCESS : BluetoothAuthenticateDeviceEx(nullptr, nullptr, &device, nullptr, MITMProtectionRequiredBonding);
    bool success = error == ERROR_SUCCESS || error == ERROR_NO_MORE_ITEMS;
    std::string json = "{\"version\":1,\"paired\":" + std::string(success ? "true" : "false")
        + ",\"alreadyPaired\":" + (paired ? "true" : "false")
        + ",\"cancelled\":" + (error == ERROR_CANCELLED ? "true" : "false") + ",\"error\":" + std::to_string(error) + "}\n";
    WriteAll(GetStdHandle(STD_OUTPUT_HANDLE), json.data(), static_cast<DWORD>(json.size()));
    return success ? 0 : 1;
}

struct Connection {
    SOCKET socket = INVALID_SOCKET;
    std::atomic_bool closed{false};
    void Close() {
        if (!closed.exchange(true)) { shutdown(socket, SD_BOTH); closesocket(socket); }
    }
};

DWORD WINAPI PumpInput(void* pointer) {
    auto& connection = *static_cast<Connection*>(pointer);
    char bytes[16 * 1024];
    DWORD read = 0;
    while (!connection.closed && ReadFile(GetStdHandle(STD_INPUT_HANDLE), bytes, sizeof(bytes), &read, nullptr) && read) {
        DWORD offset = 0;
        while (offset < read && !connection.closed) {
            int count = send(connection.socket, bytes + offset, static_cast<int>(read - offset), 0);
            if (count <= 0) { connection.Close(); return 0; }
            offset += static_cast<DWORD>(count);
        }
    }
    connection.Close();
    return 0;
}

int Run(int argc, wchar_t** argv) {
    if (argc == 2 && std::wcscmp(argv[1], L"--self-test") == 0) {
        std::uint64_t address = 0;
        const GUID service = ServiceGuid();
        bool valid = ParseAddress(L"12:34:56:78:9A:BC", address) && address == 0x123456789abcULL
            && !ParseAddress(L"12:34-56:78:9A:BC", address) && !ParseAddress(L"00:00:00:00:00:00", address)
            && !ParseAddress(L"12:34:56:78:9A", address) && !ParseAddress(L"12:34:56:78:9A:BG", address)
            && service.Data1 == 0x7c028c61 && service.Data2 == 0x7c9a && service.Data3 == 0x4b1a && service.Data4[7] == 0xed;
        valid = valid && AddressString(0x123456789abcULL) == L"12:34:56:78:9A:BC"
            && JsonString(L"quote\" slash\\ newline\n") == "\"quote\\\" slash\\\\ newline\\u000a\"";
        const char* result = valid ? "{\"platform\":\"Windows-x64\",\"addressAndServiceChecks\":true}\n" : "{\"addressAndServiceChecks\":false}\n";
        WriteAll(GetStdHandle(STD_OUTPUT_HANDLE), result, static_cast<DWORD>(std::strlen(result)));
        return valid ? 0 : 1;
    }
    if (argc == 2 && std::wcscmp(argv[1], L"--inspect") == 0) return InspectBluetooth();
    if (argc == 3 && std::wcscmp(argv[1], L"--pair") == 0) return PairDevice(argv[2]);
    std::uint64_t address = 0;
    if (argc != 3 || std::wcscmp(argv[1], L"--connect") != 0 || !ParseAddress(argv[2], address)) {
        Status("Usage: WindowsBluetoothTransport --connect AA:BB:CC:DD:EE:FF");
        return 2;
    }
    WSADATA data{};
    int error = WSAStartup(MAKEWORD(2, 2), &data);
    if (error) { Status("BT_START_FAILED", error); return 3; }
    Connection connection;
    connection.socket = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (connection.socket == INVALID_SOCKET) { Status("BT_SOCKET_FAILED", WSAGetLastError()); WSACleanup(); return 4; }
    BOOL enabled = TRUE;
    if (setsockopt(connection.socket, SOL_RFCOMM, SO_BTH_AUTHENTICATE, reinterpret_cast<const char*>(&enabled), sizeof(enabled)) == SOCKET_ERROR
        || setsockopt(connection.socket, SOL_RFCOMM, SO_BTH_ENCRYPT, reinterpret_cast<const char*>(&enabled), sizeof(enabled)) == SOCKET_ERROR) {
        Status("BT_AUTHENTICATED_LINK_UNAVAILABLE", WSAGetLastError()); connection.Close(); WSACleanup(); return 5;
    }
    SOCKADDR_BTH target{};
    target.addressFamily = AF_BTH; target.btAddr = address; target.serviceClassId = ServiceGuid(); target.port = 0;
    if (connect(connection.socket, reinterpret_cast<SOCKADDR*>(&target), sizeof(target)) == SOCKET_ERROR) {
        Status("BT_CONNECT_FAILED", WSAGetLastError()); connection.Close(); WSACleanup(); return 6;
    }
    Status("BT_CONNECTED");
    HANDLE inputThread = CreateThread(nullptr, 0, PumpInput, &connection, 0, nullptr);
    if (!inputThread) { connection.Close(); WSACleanup(); return 7; }
    char bytes[16 * 1024];
    while (!connection.closed) {
        int count = recv(connection.socket, bytes, sizeof(bytes), 0);
        if (count <= 0 || !WriteAll(GetStdHandle(STD_OUTPUT_HANDLE), bytes, static_cast<DWORD>(count))) break;
    }
    connection.Close();
    CancelSynchronousIo(inputThread);
    if (WaitForSingleObject(inputThread, 3000) != WAIT_OBJECT_0) ExitProcess(8);
    CloseHandle(inputThread);
    WSACleanup();
    Status("BT_DISCONNECTED");
    return 0;
}
}

// GUI subsystem deliberately avoids a console flash when launched by CrossPaste.
int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    int argc = 0;
    auto argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv) return 2;
    int result = Run(argc, argv);
    LocalFree(argv);
    return result;
}
