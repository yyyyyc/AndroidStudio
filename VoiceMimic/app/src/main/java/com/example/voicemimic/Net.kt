package com.example.voicemimic

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Messages of the two-phone bird race, one text line each: NAME|field|field... */
sealed class RaceMsg {
    data class Hello(val version: Int, val name: String) : RaceMsg()
    /** Host -> guest: the voice to imitate (WAV bytes) and the time limit. */
    data class Voice(val name: String, val limitSec: Int, val wav: ByteArray) : RaceMsg()
    /** Guest -> host: voice analysed, ready to race. */
    object Ready : RaceMsg()
    /** Host -> guest: start a round now. */
    object Go : RaceMsg()
    /** Progress 0..1 and current score, a few times per second while racing. */
    data class Pos(val progress: Float, val score: Int) : RaceMsg()
    /** Reached the finish after [ms] of racing. */
    data class Finish(val ms: Long) : RaceMsg()
    object Bye : RaceMsg()

    fun encode(): String = when (this) {
        is Hello -> "HELLO|$version|${name.replace("|", "/")}"
        is Voice -> "VOICE|${name.replace("|", "/")}|$limitSec|" + java.util.Base64.getEncoder().encodeToString(wav)
        Ready -> "READY"
        Go -> "GO"
        is Pos -> "POS|${"%.4f".format(java.util.Locale.US, progress)}|$score"
        is Finish -> "FINISH|$ms"
        Bye -> "BYE"
    }

    companion object {
        const val VERSION = 1

        /** Null for anything unknown or malformed (e.g. from a newer app version). */
        fun decode(line: String): RaceMsg? = try {
            val f = line.trim().split("|")
            when (f[0]) {
                "HELLO" -> Hello(f[1].toInt(), f.getOrElse(2) { "?" })
                "VOICE" -> Voice(f[1], f[2].toInt(), java.util.Base64.getDecoder().decode(f[3]))
                "READY" -> Ready
                "GO" -> Go
                "POS" -> Pos(f[1].toFloat(), f[2].toInt())
                "FINISH" -> Finish(f[1].toLong())
                "BYE" -> Bye
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * A TCP connection between two phones on the same Wi-Fi. One side [host]s, the other [connect]s.
 * Listener callbacks arrive on background threads.
 */
class NetLink(private val listener: Listener) {

    interface Listener {
        fun onConnected(isHost: Boolean)
        fun onMessage(msg: RaceMsg)
        /** Connection failed or ended (not called after [close]). */
        fun onClosed()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var closed = false
    private val sender = Executors.newSingleThreadExecutor()

    companion object {
        /** Used when free, so joining by typed address needs only the IP. */
        const val DEFAULT_PORT = 47124
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
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 64 * 1024)
            while (true) {
                val line = reader.readLine() ?: break
                val msg = RaceMsg.decode(line) ?: continue
                listener.onMessage(msg)
                if (msg == RaceMsg.Bye) break
            }
        } catch (e: IOException) {
            // connection dropped
        }
        if (!closed) { closed = true; listener.onClosed() }
        try { s.close() } catch (e: IOException) {}
    }

    fun send(msg: RaceMsg) {
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
            try { w?.println(RaceMsg.Bye.encode()) } catch (e: Exception) {}
            try { socket?.close() } catch (e: IOException) {}
            try { server?.close() } catch (e: IOException) {}
        }
        sender.shutdown()
    }
}

/**
 * Finds races on the same Wi-Fi with Android's network service discovery (mDNS), so nobody has to type an address.
 * Callbacks are delivered on the main thread.
 */
class NetDiscovery(context: Context) {

    data class Game(val name: String, val host: String, val port: Int)

    companion object {
        const val SERVICE_TYPE = "_voicemimic._tcp."
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    var onFound: (Game) -> Unit = {}
    var onLost: (String) -> Unit = {}

    fun advertise(name: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = SERVICE_TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { registration = null }
    }

    fun stopAdvertising() {
        registration?.let { try { nsd.unregisterService(it) } catch (e: Exception) {} }
        registration = null
    }

    fun search() {
        stopSearching()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolveQueue.addLast(info); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { onLost(info.serviceName) } }
        }
        discovery = l
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { discovery = null }
    }

    fun stopSearching() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) {} }
        discovery = null
        resolveQueue.clear()
    }

    fun stop() { stopAdvertising(); stopSearching() }

    /** NsdManager resolves one service at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || discovery == null) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null && discovery != null) onFound(Game(info.serviceName, host, info.port))
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    /** This phone's IPv4 address on the current network, to show for manual joining. */
    fun localAddress(): String? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(net) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    fun deviceName(): String = Build.MODEL ?: "Android"
}
