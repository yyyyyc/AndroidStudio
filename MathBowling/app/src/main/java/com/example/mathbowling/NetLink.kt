package com.example.mathbowling

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Messages between the two phones, one per line: NAME|field|field... */
sealed class NetMsg {
    /** Host -> guest: start a game with these settings; [seed] makes both phones get the same questions. */
    data class Start(
        val seed: Long, val mode: GameMode, val level: Level, val endless: Boolean,
        /** CUSTOM mode: the host's questions file, so both phones play the same questions. */
        val customText: String? = null
    ) : NetMsg()
    /** After each answer. */
    data class Result(val question: Int, val correct: Boolean, val score: Int) : NetMsg()
    /** The player finished the game. */
    data class Done(val score: Int, val total: Int, val timeMs: Long) : NetMsg()
    object Bye : NetMsg()

    fun encode(): String = when (this) {
        is Start -> "START|$seed|${mode.name}|${level.name}|${if (endless) 1 else 0}" +
            (customText?.let { "|" + java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: "")
        is Result -> "RESULT|$question|${if (correct) 1 else 0}|$score"
        is Done -> "DONE|$score|$total|$timeMs"
        Bye -> "BYE"
    }

    companion object {
        /** Returns null for anything unknown or malformed (e.g. a newer app version). */
        fun decode(line: String): NetMsg? = try {
            val f = line.trim().split("|")
            when (f[0]) {
                "START" -> Start(
                    f[1].toLong(), GameMode.valueOf(f[2]), Level.valueOf(f[3]), f[4] == "1",
                    f.getOrNull(5)?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
                )
                "RESULT" -> Result(f[1].toInt(), f[2] == "1", f[3].toInt())
                "DONE" -> Done(f[1].toInt(), f[2].toInt(), f[3].toLong())
                "BYE" -> Bye
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * A TCP connection between two phones on the same network. One side [host]s, the other [connect]s.
 * Listener callbacks arrive on background threads.
 */
class NetLink(private val listener: Listener) {

    interface Listener {
        fun onConnected(isHost: Boolean)
        fun onMessage(msg: NetMsg)
        /** Connection failed or ended (not called after [close]). */
        fun onClosed()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var closed = false
    private val sender = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = writer != null && !closed

    companion object {
        /** Used when free, so joining by typed address needs only the IP. */
        const val DEFAULT_PORT = 47123
    }

    /** Starts waiting for the other phone; returns the port to advertise. */
    fun host(preferredPort: Int = DEFAULT_PORT): Int {
        val ss = try { ServerSocket(preferredPort) } catch (e: IOException) { ServerSocket(0) }
        server = ss
        thread(name = "net-accept", isDaemon = true) {
            try {
                val s = ss.accept()
                ss.close()
                attach(s, true)
            } catch (e: IOException) {
                if (!closed) { closed = true; listener.onClosed() }
            }
        }
        return ss.localPort
    }

    fun connect(host: String, port: Int) {
        thread(name = "net-connect", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                attach(s, false)
            } catch (e: IOException) {
                if (!closed) { closed = true; listener.onClosed() }
            }
        }
    }

    private fun attach(s: Socket, isHost: Boolean) {
        s.tcpNoDelay = true
        socket = s
        writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
        listener.onConnected(isHost)
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val msg = NetMsg.decode(line) ?: continue
                listener.onMessage(msg)
                if (msg == NetMsg.Bye) break
            }
        } catch (e: IOException) {
            // connection dropped
        }
        if (!closed) { closed = true; listener.onClosed() }
        try { s.close() } catch (e: IOException) {}
    }

    fun send(msg: NetMsg) {
        val w = writer ?: return
        if (closed) return
        sender.execute { w.println(msg.encode()) }
    }

    /** Says goodbye to the other phone and closes everything. */
    fun close() {
        if (closed) return
        val w = writer
        closed = true
        sender.execute {
            try { w?.println(NetMsg.Bye.encode()) } catch (e: Exception) {}
            try { socket?.close() } catch (e: IOException) {}
            try { server?.close() } catch (e: IOException) {}
        }
        sender.shutdown()
    }
}
