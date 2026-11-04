package com.crosspaste.transport.bluetooth

import com.crosspaste.net.Server
import com.crosspaste.net.clientapi.SyncClientApi
import com.crosspaste.secure.SecureStore
import com.crosspaste.sync.SyncTestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@com.crosspaste.test.IntegrationTest
class BluetoothTransportControllerTest {
    @Test
    fun `untrusted peer cannot open the adapter or query its metadata`(): Unit =
        runBlocking {
            val store = mockk<SecureStore>()
            val api = mockk<SyncClientApi>(relaxed = true)
            coEvery { store.existCryptPublicKey("not-paired") } returns false
            val server = lazy<Server> { error("An unpaired device must fail before starting transport") }
            val controller =
                BluetoothTransportController(
                    SyncTestFixtures.TEST_PLATFORM.copy(name = "Windows"),
                    SyncTestFixtures.createAppInfo(appInstanceId = "local"),
                    store,
                    api,
                    mockk(relaxed = true),
                    server,
                    mockk(relaxed = true),
                )
            try {
                assertFalse(controller.state.value.active)
                controller.connect("not-paired", "AA:BB:CC:DD:EE:FF")
                val state =
                    withTimeout(3.seconds) {
                        controller.state.first { !it.active && it.message.contains("Pair this device") }
                    }
                assertTrue(state.message.contains("Pair this device"))
                coVerify(exactly = 0) { api.syncInfo(any(), any()) }
            } finally {
                controller.close()
            }
        }

    @Test
    fun `local identity is rejected before reading keys or starting transport`(): Unit =
        runBlocking {
            val store = mockk<SecureStore>(relaxed = true)
            val controller =
                BluetoothTransportController(
                    SyncTestFixtures.TEST_PLATFORM.copy(name = "Windows"),
                    SyncTestFixtures.createAppInfo(appInstanceId = "local"),
                    store,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    lazy<Server> { error("Must not start a self connection") },
                    mockk(relaxed = true),
                )
            try {
                controller.connect("local", "AA:BB:CC:DD:EE:FF")
                withTimeout(3.seconds) {
                    controller.state.first { !it.active && it.message == "Choose another device" }
                }
                coVerify(exactly = 0) { store.existCryptPublicKey(any()) }
            } finally {
                controller.close()
            }
        }
}
