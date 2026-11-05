package com.crosspaste.transport.bluetooth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable
internal data class KnownBluetoothDevice(
    val address: String,
    val name: String,
    val paired: Boolean,
)

@Serializable
private data class BluetoothInspection(
    val version: Int,
    val radioAvailable: Boolean,
    val devices: List<KnownBluetoothDevice>,
)

internal suspend fun inspectPairedWindowsBluetoothDevices(): List<KnownBluetoothDevice> =
    withContext(Dispatchers.IO) {
        val resources = System.getProperty("compose.application.resources.dir")
        require(!resources.isNullOrBlank())
        val adapter = Path.of(resources, "bluetooth", "WindowsBluetoothTransport.exe")
        require(Files.isRegularFile(adapter))
        val output = Files.createTempFile("crosspaste-bluetooth-", ".json")
        try {
            val child =
                ProcessBuilder(adapter.toString(), "--inspect")
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                check(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0)
                val bytes = Files.newInputStream(output).use { it.readNBytes(65_537) }
                check(bytes.size <= 65_536)
                val result =
                    Json { ignoreUnknownKeys = true }.decodeFromString<BluetoothInspection>(
                        bytes.decodeToString(),
                    )
                check(result.version == 1 && result.radioAvailable && result.devices.size <= 256)
                val address = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                result.devices.filter { it.paired && address.matches(it.address) }.distinctBy { it.address.uppercase() }
            } finally {
                if (child.isAlive) child.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
