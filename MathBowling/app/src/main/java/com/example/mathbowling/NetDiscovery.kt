package com.example.mathbowling

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Finds games on the same Wi-Fi with Android's network service discovery (mDNS), so nobody has to type an address.
 * Callbacks are delivered on the main thread.
 */
class NetDiscovery(context: Context) {

    data class Game(val name: String, val host: String, val port: Int)

    companion object {
        const val SERVICE_TYPE = "_mathbowling._tcp."
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

    /** Advertises a hosted game. */
    fun advertise(name: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
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

    /** Looks for hosted games until [stopSearching]. */
    fun search() {
        stopSearching()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolveQueue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { onLost(info.serviceName) }
            }
        }
        discovery = l
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { discovery = null }
    }

    fun stopSearching() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) {} }
        discovery = null
        resolveQueue.clear()
    }

    fun stop() {
        stopAdvertising()
        stopSearching()
    }

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

    /** A friendly name for this phone in other players' lists. */
    fun deviceName(): String {
        val model = Build.MODEL ?: "Android"
        return "MathBowling · $model"
    }
}
