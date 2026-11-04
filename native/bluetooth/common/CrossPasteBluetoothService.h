#pragma once

// One service identifier for both native adapters. This identifies a transport,
// not a trusted peer; CrossPaste's normal pairing remains mandatory above it.
inline constexpr unsigned char CrossPasteBluetoothServiceUuid[16] = {
    0x7c, 0x02, 0x8c, 0x61, 0x7c, 0x9a, 0x4b, 0x1a,
    0xa8, 0xcb, 0x25, 0x8b, 0x58, 0xb3, 0xe0, 0xed
};
