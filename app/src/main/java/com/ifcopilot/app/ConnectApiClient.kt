package com.ifcopilot.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for Infinite Flight's "Connect" API v2 (local TCP socket, no API key).
 *
 * IMPORTANT CAVEAT: Infinite Flight's official docs describe the message shape
 * (ID, boolean, value; strings length-prefixed) but do not publish the exact
 * endianness used on the wire. This implementation assumes little-endian,
 * matching how the reference .NET sample (BitConverter, little-endian on all
 * common desktop/mobile CPUs) would naturally serialize values. If values come
 * back garbled, this is the first thing to flip (see ENDIAN below).
 */
object Proto {
    val ENDIAN: ByteOrder = ByteOrder.LITTLE_ENDIAN
}

enum class IfType(val id: Int) {
    BOOL(0), INT(1), FLOAT(2), DOUBLE(3), STRING(4), LONG(5);
    companion object {
        fun fromId(id: Int): IfType? = entries.firstOrNull { it.id == id }
    }
}

data class ManifestEntry(val id: Int, val type: IfType, val path: String)

sealed class IfValue {
    data class B(val v: Boolean) : IfValue()
    data class I(val v: Int) : IfValue()
    data class F(val v: Float) : IfValue()
    data class D(val v: Double) : IfValue()
    data class S(val v: String) : IfValue()
    data class L(val v: Long) : IfValue()

    fun asDouble(): Double = when (this) {
        is B -> if (v) 1.0 else 0.0
        is I -> v.toDouble()
        is F -> v.toDouble()
        is D -> v
        is S -> v.toDoubleOrNull() ?: 0.0
        is L -> v.toDouble()
    }

    fun asBoolean(): Boolean = when (this) {
        is B -> v
        is I -> v != 0
        is L -> v != 0L
        else -> asDouble() != 0.0
    }

    fun asString(): String = when (this) {
        is S -> v
        else -> toString()
    }
}

class ConnectApiClient {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val ioMutex = Mutex()

    var manifestByPath: Map<String, ManifestEntry> = emptyMap()
        private set
    var manifestById: Map<Int, ManifestEntry> = emptyMap()
        private set
    var lastSkippedUnknownTypeCount: Int = 0
        private set

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Listens for the UDP broadcast Infinite Flight sends on port 15000
     * advertising its host/port/aircraft. Returns null if nothing heard
     * within [timeoutMs].
     */
    suspend fun discover(timeoutMs: Int = 8000): DiscoveredHost? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket(15000).use { udp ->
                udp.broadcast = true
                udp.soTimeout = timeoutMs
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                udp.receive(packet)
                val text = String(packet.data, 0, packet.length)
                parseDiscovery(text)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDiscovery(text: String): DiscoveredHost? {
        // Try the structured field names first (best case).
        try {
            val json = JSONObject(text)
            val port = json.optInt("Port", json.optInt("port", 10112))
            val addr = extractFirstAddress(json)
            if (!addr.isNullOrBlank()) {
                return DiscoveredHost(
                    addr, port,
                    json.optString("Aircraft", json.optString("aircraft", "")),
                    json.optString("DeviceName", json.optString("deviceName", ""))
                )
            }
        } catch (e: Exception) {
            // fall through to the regex fallback below
        }

        // Fallback: the exact broadcast schema isn't confirmed against real
        // IF traffic, so if the structured parse above didn't find an
        // address, scan the raw text directly for an IPv4-looking string
        // and a port number. Less precise, but doesn't depend on guessing
        // exact JSON key names correctly.
        val ipMatch = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""").find(text)?.value
        val portMatch = Regex(""""?[Pp]ort"?\s*[:=]\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 10112
        return if (ipMatch != null) DiscoveredHost(ipMatch, portMatch, "", "") else null
    }

    private fun extractFirstAddress(json: JSONObject): String? {
        for (key in listOf("Addresses", "addresses")) {
            val arr = json.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr.optString(0)
        }
        for (key in listOf("Address", "address", "Ip", "ip", "IpAddress", "ipAddress", "Host", "host")) {
            val v = json.optString(key, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        socket = s
        input = DataInputStream(s.getInputStream())
        output = DataOutputStream(s.getOutputStream())
        fetchManifest()
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    /** Requests the full state manifest and builds path/id lookup maps. */
    private suspend fun fetchManifest() = ioMutex.withLock {
        val out = output ?: return@withLock
        val inp = input ?: return@withLock

        writeInt32(out, -1)
        writeBool(out, false)
        out.flush()

        val respId = readInt32(inp)
        val length = readInt32(inp)
        val raw = ByteArray(length)
        inp.readFully(raw)
        val text = String(raw, Charsets.UTF_8)

        val entries = mutableMapOf<String, ManifestEntry>()
        val entriesById = mutableMapOf<Int, ManifestEntry>()
        var skippedUnknownTypes = 0
        text.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(",", limit = 3)
            if (parts.size == 3) {
                val id = parts[0].toIntOrNull() ?: return@forEach
                val typeId = parts[1].toIntOrNull() ?: return@forEach
                val path = parts[2]
                val type = IfType.fromId(typeId)
                if (type == null) {
                    // Unrecognized type code for this manifest line - skip it
                    // rather than crash the whole connection. Common cause:
                    // IF added a type this client doesn't model yet (e.g. an
                    // array/vector type). Anything using this exact path
                    // simply won't be readable until the mapping is extended.
                    skippedUnknownTypes++
                    return@forEach
                }
                val entry = ManifestEntry(id, type, path)
                entries[path] = entry
                entriesById[id] = entry
            }
        }
        manifestByPath = entries
        manifestById = entriesById
        lastSkippedUnknownTypeCount = skippedUnknownTypes
    }

    /** Look up a manifest entry by its stable path string, e.g. "aircraft/0/altitude_agl" */
    fun resolve(path: String): ManifestEntry? = manifestByPath[path]

    /**
     * Fallback lookup for when the exact path name isn't confirmed - scans
     * all manifest paths for one containing every given keyword
     * (case-insensitive). Useful for things like the throttle state, where
     * the exact path string used by this client is a best guess, not
     * something verified against Infinite Flight's real manifest.
     */
    fun resolveFuzzy(vararg keywords: String): ManifestEntry? {
        val lowerKeywords = keywords.map { it.lowercase() }
        return manifestByPath.entries.firstOrNull { (path, _) ->
            val lowerPath = path.lowercase()
            lowerKeywords.all { lowerPath.contains(it) }
        }?.value
    }

    suspend fun getState(entry: ManifestEntry): IfValue? = ioMutex.withLock {
        val out = output ?: return@withLock null
        val inp = input ?: return@withLock null
        writeInt32(out, entry.id)
        writeBool(out, false)
        out.flush()
        readValue(inp, entry.type)
    }

    suspend fun setStateBool(entry: ManifestEntry, value: Boolean) = ioMutex.withLock {
        val out = output ?: return@withLock
        writeInt32(out, entry.id)
        writeBool(out, true)
        writeBool(out, value)
        out.flush()
    }

    suspend fun setStateFloat(entry: ManifestEntry, value: Float) = ioMutex.withLock {
        val out = output ?: return@withLock
        writeInt32(out, entry.id)
        writeBool(out, true)
        writeFloat(out, value)
        out.flush()
    }

    suspend fun setStateInt(entry: ManifestEntry, value: Int) = ioMutex.withLock {
        val out = output ?: return@withLock
        writeInt32(out, entry.id)
        writeBool(out, true)
        writeInt32(out, value)
        out.flush()
    }

    /** Runs a command entry (e.g. Commands.FlapsDown) - fire and forget. */
    suspend fun runCommand(entry: ManifestEntry) = ioMutex.withLock {
        val out = output ?: return@withLock
        writeInt32(out, entry.id)
        writeBool(out, false)
        out.flush()
    }

    // ---- low level read/write helpers ----

    private fun writeInt32(out: DataOutputStream, v: Int) {
        val bb = ByteBuffer.allocate(4).order(Proto.ENDIAN).putInt(v)
        out.write(bb.array())
    }

    private fun writeFloat(out: DataOutputStream, v: Float) {
        val bb = ByteBuffer.allocate(4).order(Proto.ENDIAN).putFloat(v)
        out.write(bb.array())
    }

    private fun writeBool(out: DataOutputStream, v: Boolean) {
        out.write(byteArrayOf(if (v) 1 else 0))
    }

    private fun readInt32(inp: DataInputStream): Int {
        val b = ByteArray(4)
        inp.readFully(b)
        return ByteBuffer.wrap(b).order(Proto.ENDIAN).int
    }

    private fun readValue(inp: DataInputStream, expectedType: IfType): IfValue {
        // Every response begins with id (int32) + length (int32) of the payload.
        readInt32(inp) // id (echoed back), unused here
        val length = readInt32(inp)
        val payload = ByteArray(length)
        inp.readFully(payload)
        val bb = ByteBuffer.wrap(payload).order(Proto.ENDIAN)
        return when (expectedType) {
            IfType.BOOL -> IfValue.B(payload.isNotEmpty() && payload[0].toInt() != 0)
            IfType.INT -> IfValue.I(bb.int)
            IfType.FLOAT -> IfValue.F(bb.float)
            IfType.DOUBLE -> IfValue.D(bb.double)
            IfType.LONG -> IfValue.L(bb.long)
            IfType.STRING -> {
                // NOTE: earlier versions assumed an extra 4-byte length
                // prefix inside the payload itself, on top of the outer
                // length IF already sends. That was wrong and caused
                // BufferUnderflowException (message == null) on any string
                // read, e.g. aircraft/0/name - the outer length already
                // *is* the string's byte length, so just decode it directly.
                IfValue.S(String(payload, Charsets.UTF_8))
            }
        }
    }
}

data class DiscoveredHost(val address: String, val port: Int, val aircraft: String, val deviceName: String)
