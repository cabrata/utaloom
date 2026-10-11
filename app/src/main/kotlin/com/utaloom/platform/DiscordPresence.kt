package com.utaloom.platform

import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

internal enum class DiscordOpcode(val code: Int) {
    HANDSHAKE(0), FRAME(1), CLOSE(2), PING(3), PONG(4)
}

internal class DiscordRpcFrame(val opcode: DiscordOpcode, val payload: ByteArray)

internal fun parseDiscordFrame(header: ByteBuffer, body: ByteArray): DiscordRpcFrame {
    val code = header.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    val op = DiscordOpcode.entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown Discord opcode $code")
    return DiscordRpcFrame(op, body)
}

internal fun encodeDiscordFrame(opcode: DiscordOpcode, jsonText: String): ByteBuffer {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 65_536) { "Discord payload too large" }
    val buffer = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(opcode.code)
    buffer.putInt(bytes.size)
    buffer.put(bytes)
    buffer.flip()
    return buffer
}

internal interface DiscordTransport : Closeable {
    fun writeFrame(opcode: DiscordOpcode, payload: String)
    fun readFrame(maxBytes: Int = 65_536): DiscordRpcFrame
}

internal class UnixSocketTransport(private val channel: SocketChannel) : DiscordTransport {
    override fun writeFrame(opcode: DiscordOpcode, payload: String) {
        val buf = encodeDiscordFrame(opcode, payload)
        while (buf.hasRemaining()) channel.write(buf)
    }

    override fun readFrame(maxBytes: Int): DiscordRpcFrame {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (header.hasRemaining()) {
            if (channel.read(header) < 0) throw IllegalStateException("Discord socket closed")
        }
        val length = header.getInt(4)
        if (length !in 0..maxBytes) throw IllegalArgumentException("Frame size out of bounds: $length")
        val body = ByteBuffer.allocate(length)
        while (body.hasRemaining()) {
            if (channel.read(body) < 0) throw IllegalStateException("Discord socket closed while reading body")
        }
        return parseDiscordFrame(header, body.array())
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

internal class WindowsPipeTransport(private val pipe: RandomAccessFile) : DiscordTransport {
    override fun writeFrame(opcode: DiscordOpcode, payload: String) {
        val buf = encodeDiscordFrame(opcode, payload)
        pipe.write(buf.array(), 0, buf.remaining())
    }

    override fun readFrame(maxBytes: Int): DiscordRpcFrame {
        val header = ByteArray(8)
        pipe.readFully(header)
        val hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = hBuf.getInt(4)
        if (length !in 0..maxBytes) throw IllegalArgumentException("Frame size out of bounds: $length")
        val body = ByteArray(length)
        pipe.readFully(body)
        return parseDiscordFrame(hBuf, body)
    }

    override fun close() {
        runCatching { pipe.close() }
    }
}

internal fun candidateDiscordIpcPipes(): List<String> {
    val os = System.getProperty("os.name").lowercase()
    val isWin = os.contains("win")
    val list = mutableListOf<String>()
    if (isWin) {
        for (i in 0..9) list += "\\\\?\\pipe\\discord-ipc-$i"
    } else {
        val baseDirs = listOfNotNull(
            System.getenv("XDG_RUNTIME_DIR"),
            System.getenv("TMPDIR"),
            System.getenv("TMP"),
            System.getenv("TEMP"),
            "/tmp"
        ).distinct()
        for (dir in baseDirs) {
            for (i in 0..9) list += File(dir, "discord-ipc-$i").absolutePath
        }
    }
    return list
}

internal fun connectDiscordTransport(): DiscordTransport? {
    val os = System.getProperty("os.name").lowercase()
    val isWin = os.contains("win")
    for (candidate in candidateDiscordIpcPipes()) {
        if (isWin) {
            val pipe = runCatching { RandomAccessFile(candidate, "rw") }.getOrNull()
            if (pipe != null) return WindowsPipeTransport(pipe)
        } else {
            val file = File(candidate)
            if (!file.exists()) continue
            val channel = runCatching {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(UnixDomainSocketAddress.of(file.toPath()))
                ch
            }.getOrNull()
            if (channel != null) return UnixSocketTransport(channel)
        }
    }
    return null
}

internal fun discordActivityPayload(appId: String, song: Song?, state: PlayerState, epochNow: Long): String {
    val isPlaying = state.isPlaying && !state.isBuffering && state.error == null && song != null
    val root = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        putJsonObject("args") {
            put("pid", ProcessHandle.current().pid())
            if (!isPlaying) {
                put("activity", JsonNull)
            } else {
                putJsonObject("activity") {
                    put("type", 2) // LISTENING
                    put("details", song.title.take(128))
                    put("state", song.artistText.take(128))
                    val durationMs = state.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
                    val start = (epochNow - (state.positionMs / 1000L)).coerceAtLeast(0)
                    putJsonObject("timestamps") {
                        put("start", start)
                        if (durationMs > 0) {
                            put("end", start + (durationMs / 1000L))
                        }
                    }
                    putJsonObject("assets") {
                        song.thumbnail?.takeIf { it.startsWith("http") }?.let { put("large_image", it.take(256)) }
                        song.album?.takeIf { it.isNotBlank() }?.let { put("large_text", it.take(128)) }
                    }
                }
            }
        }
        put("nonce", java.util.UUID.randomUUID().toString())
    }
    return root.toString()
}

/** Rich presence via native length-bounded Discord IPC without third-party frameworks. */
object DiscordPresence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow("Disabled")
    val status: StateFlow<String> = _status.asStateFlow()
    private var job: Job? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        _status.value = "Stopped"
    }

    private suspend fun loop() {
        var transport: DiscordTransport? = null
        var currentAppId: String? = null
        try {
            while (currentCoroutineContext().isActive) {
                val s = Stores.settings.value
                val enabled = s.discordEnabled
                val appId = s.discordApplicationId.trim()
                if (!enabled || appId.isBlank()) {
                    if (transport != null) {
                        runCatching { clearActivity(transport) }
                        transport.close()
                        transport = null
                        currentAppId = null
                    }
                    _status.value = if (!enabled) "Disabled" else "Application ID required"
                    delay(500)
                    continue
                }

                if (transport == null || currentAppId != appId) {
                    transport?.close()
                    transport = null
                    _status.value = "Connecting to Discord..."
                    val opened = connectDiscordTransport()
                    if (opened == null) {
                        _status.value = "Discord app not found"
                        delay(2500)
                        continue
                    }
                    val handshakeOk = runCatching {
                        val hs = buildJsonObject {
                            put("v", 1)
                            put("client_id", appId)
                        }.toString()
                        opened.writeFrame(DiscordOpcode.HANDSHAKE, hs)
                        val reply = opened.readFrame()
                        reply.opcode == DiscordOpcode.FRAME
                    }.getOrDefault(false)

                    if (!handshakeOk) {
                        opened.close()
                        _status.value = "Handshake rejected (verify Application ID)"
                        delay(2500)
                        continue
                    }
                    transport = opened
                    currentAppId = appId
                    _status.value = "Connected"
                }

                // Update activity based on current player state
                val liveState = Player.state.value
                val now = System.currentTimeMillis() / 1000L
                val payload = discordActivityPayload(appId, liveState.current, liveState, now)
                val updateOk = runCatching {
                    transport.writeFrame(DiscordOpcode.FRAME, payload)
                    true
                }.getOrDefault(false)

                if (!updateOk) {
                    transport.close()
                    transport = null
                    currentAppId = null
                    _status.value = "Disconnected, reconnecting..."
                    delay(2000)
                    continue
                }

                // Drain any incoming ping/response frame non-blocking if possible or short sleep
                delay(1000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _status.value = "IPC connection error"
        } finally {
            transport?.let {
                runCatching { clearActivity(it) }
                it.close()
            }
        }
    }

    private fun clearActivity(transport: DiscordTransport) {
        val payload = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                put("activity", JsonNull)
            }
            put("nonce", java.util.UUID.randomUUID().toString())
        }.toString()
        transport.writeFrame(DiscordOpcode.FRAME, payload)
    }
}
