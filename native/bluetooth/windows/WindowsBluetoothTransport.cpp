#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <shellapi.h>
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
        const char* result = valid ? "{\"platform\":\"Windows-x64\",\"addressAndServiceChecks\":true}\n" : "{\"addressAndServiceChecks\":false}\n";
        WriteAll(GetStdHandle(STD_OUTPUT_HANDLE), result, static_cast<DWORD>(std::strlen(result)));
        return valid ? 0 : 1;
    }
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
