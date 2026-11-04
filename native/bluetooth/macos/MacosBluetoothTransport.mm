#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include "../common/CrossPasteBluetoothService.h"

static bool WriteAll(int descriptor, const void* bytes, size_t length) {
    const auto* data = static_cast<const unsigned char*>(bytes);
    while (length) {
        ssize_t written = write(descriptor, data, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        data += written; length -= static_cast<size_t>(written);
    }
    return true;
}

static void Status(const char* text) {
    WriteAll(STDERR_FILENO, text, std::strlen(text));
    WriteAll(STDERR_FILENO, "\n", 1);
}

@interface CrossPasteBluetoothServer : NSObject <IOBluetoothRFCOMMChannelDelegate>
@property(nonatomic, strong) IOBluetoothSDPServiceRecord* record;
@property(nonatomic, strong) IOBluetoothUserNotification* notification;
@property(nonatomic, strong) IOBluetoothRFCOMMChannel* channel;
@property(nonatomic) BOOL stopping;
@property(nonatomic) BOOL connected;
@property(nonatomic) int result;
- (BOOL)start;
- (void)opened:(IOBluetoothUserNotification*)notification channel:(IOBluetoothRFCOMMChannel*)channel;
- (void)becameConnected;
- (void)finish:(int)result;
@end

@implementation CrossPasteBluetoothServer
- (BOOL)start {
    IOBluetoothSDPUUID* uuid = [IOBluetoothSDPUUID uuidWithBytes:CrossPasteBluetoothServiceUuid length:sizeof(CrossPasteBluetoothServiceUuid)];
    NSDictionary* channelNumber = @{ @"DataElementType": @1, @"DataElementSize": @1, @"DataElementValue": @0 };
    NSDictionary* attributes = @{
        @"0001 - ServiceClassIDList": @[uuid],
        @"0004 - ProtocolDescriptorList": @[
            @[[IOBluetoothSDPUUID uuid16:0x0100]],
            @[[IOBluetoothSDPUUID uuid16:0x0003], channelNumber]
        ],
        @"0005 - BrowseGroupList": @[[IOBluetoothSDPUUID uuid16:0x1002]],
        @"0100 - ServiceName": @"CrossPaste Bluetooth",
        @"LocalAttributes": @{ @"Persistent": @NO }
    };
    self.record = [IOBluetoothSDPServiceRecord publishedServiceRecordWithDictionary:attributes];
    BluetoothRFCOMMChannelID channel = 0;
    if (!self.record || [self.record getRFCOMMChannelID:&channel] != kIOReturnSuccess || !channel) {
        Status("BT_SERVICE_FAILED"); return NO;
    }
    self.notification = [IOBluetoothRFCOMMChannel registerForChannelOpenNotifications:self
        selector:@selector(opened:channel:) withChannelID:channel direction:kIOBluetoothUserNotificationChannelDirectionIncoming];
    if (!self.notification) { Status("BT_LISTENER_FAILED"); return NO; }
    Status("BT_LISTENING");
    return YES;
}

- (void)opened:(IOBluetoothUserNotification*)notification channel:(IOBluetoothRFCOMMChannel*)channel {
    (void)notification;
    // OS pairing is separate from CrossPaste pairing. Neither is auto-approved.
    if (self.stopping || self.channel || ![[channel getDevice] isPaired]) {
        [channel closeChannel]; return;
    }
    self.channel = channel;
    [channel setDelegate:self];
    if ([channel isOpen]) [self becameConnected];
}

- (void)becameConnected {
    if (self.connected || self.stopping) return;
    self.connected = YES;
    Status("BT_CONNECTED");
    // Raw payload goes only through inherited stdio pipes, never through logs.
    [NSThread detachNewThreadWithBlock:^{
        @autoreleasepool {
            unsigned char bytes[16 * 1024];
            while (true) {
                ssize_t length = read(STDIN_FILENO, bytes, sizeof(bytes));
                if (length < 0 && errno == EINTR) continue;
                if (length <= 0) break;
                auto* chunk = bytes;
                __block BOOL failed = NO;
                dispatch_sync(dispatch_get_main_queue(), ^{
                    if (self.stopping || !self.channel) { failed = YES; return; }
                    const auto mtu = [self.channel getMTU];
                    if (!mtu) { failed = YES; return; }
                    size_t offset = 0;
                    while (offset < static_cast<size_t>(length)) {
                        UInt16 count = static_cast<UInt16>(MIN(static_cast<size_t>(mtu), static_cast<size_t>(length) - offset));
                        if ([self.channel writeSync:chunk + offset length:count] != kIOReturnSuccess) { failed = YES; break; }
                        offset += count;
                    }
                });
                if (failed) break;
            }
            dispatch_async(dispatch_get_main_queue(), ^{ [self finish:0]; });
        }
    }];
}

- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel*)channel status:(IOReturn)status {
    if (channel != self.channel) return;
    if (status == kIOReturnSuccess) [self becameConnected];
    else { Status("BT_CHANNEL_FAILED"); [self finish:4]; }
}

- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel*)channel data:(void*)data length:(size_t)length {
    if (channel == self.channel && !self.stopping && !WriteAll(STDOUT_FILENO, data, length)) [self finish:5];
}

- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel*)channel {
    if (channel == self.channel) [self finish:0];
}

- (void)finish:(int)result {
    if (self.stopping) return;
    self.stopping = YES; self.result = result;
    [self.notification unregister]; self.notification = nil;
    [self.channel setDelegate:nil]; [self.channel closeChannel]; self.channel = nil;
    [self.record removeServiceRecord]; self.record = nil;
    Status("BT_DISCONNECTED");
    CFRunLoopStop(CFRunLoopGetMain());
}
@end

int main(int argc, const char* argv[]) {
    @autoreleasepool {
        signal(SIGPIPE, SIG_IGN);
        if (argc == 2 && std::strcmp(argv[1], "--self-test") == 0) {
            // Pure constant/argument check; never accesses the radio or starts SDP.
            bool valid = sizeof(CrossPasteBluetoothServiceUuid) == 16 && CrossPasteBluetoothServiceUuid[0] == 0x7c
                && CrossPasteBluetoothServiceUuid[7] == 0x1a && CrossPasteBluetoothServiceUuid[15] == 0xed;
            const char* text = valid ? "{\"platform\":\"macOS\",\"serviceChecks\":true}\n" : "{\"serviceChecks\":false}\n";
            WriteAll(STDOUT_FILENO, text, std::strlen(text)); return valid ? 0 : 1;
        }
        if (argc != 2 || std::strcmp(argv[1], "--listen") != 0) {
            Status("Usage: MacosBluetoothTransport --listen"); return 2;
        }
        CrossPasteBluetoothServer* server = [CrossPasteBluetoothServer new];
        if (![server start]) { [server finish:3]; return 3; }
        CFRunLoopRun();
        if (!server.stopping) { Status("BT_LISTENER_STOPPED"); [server finish:6]; }
        return server.result;
    }
}
