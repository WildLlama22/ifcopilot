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
        return try {
            val json = JSONObject(text)
            val port = json.optInt("Port", 10112)
            val addresses = json.optJSONArray("Addresses")
            val addr = if (addresses != null && addresses.length() > 0) addresses.getString(0) else null
            DiscoveredHost(addr ?: "", port, json.optString("Aircraft", ""), json.optString("DeviceName", ""))
        } catch (e: Exception) {
            null
        }
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

    private suspend fun fetchManifest() = ioMutex.withLock {
        val out = output ?: return@withLock
        val inp = input ?: return@withLock

        writeInt32(out, -1)
        writeBool(out, false)
        out.flush()

        readInt32(inp)
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

    fun resolve(path: String): ManifestEntry? = manifestByPath[path]

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

    suspend fun runCommand(entry: ManifestEntry) = ioMutex.withLock {
        val out = output ?: return@withLock
        writeInt32(out, entry.id)
        writeBool(out, false)
        out.flush()
    }

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
        readInt32(inp)
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
                val strLen = bb.int
                val strBytes = ByteArray(strLen)
                bb.get(strBytes)
                IfValue.S(String(strBytes, Charsets.UTF_8))
            }
        }
    }
}

data class DiscoveredHost(val address: String, val port: Int, val aircraft: String, val deviceName: String)
