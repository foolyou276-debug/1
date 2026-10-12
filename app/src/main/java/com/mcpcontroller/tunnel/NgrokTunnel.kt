package com.mcpcontroller.tunnel

import android.content.Context
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

object NgrokTunnel {
    private const val TAG = "NgrokTunnel"
    var publicUrl: String = ""
    private var sshSession: com.jcraft.jsch.Session? = null
    private var tunnelJob: Job? = null

    fun start(
        context: Context,
        authToken: String,
        port: Int = 8080,
        onStatus: (String) -> Unit,
        onUrl: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        tunnelJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatus("Generating SSH key...")
                val jsch = JSch()
                val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)
                val privOut = ByteArrayOutputStream()
                keyPair.writePrivateKey(privOut)
                jsch.addIdentity("mcpkey", privOut.toByteArray(), null, null)

                onStatus("Connecting to serveo.net...")
                val session = jsch.getSession("serveo.net", "serveo.net", 22)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "publickey")
                session.setServerAliveInterval(20000)
                session.setServerAliveCountMax(10)
                session.connect(30000)
                sshSession = session

                onStatus("Setting up port forward...")
                session.setPortForwardingR(80, "localhost", port)

                delay(3000)
                val url = "https://serveo.net"
                publicUrl = url
                onUrl(url)
                onStatus("✅ Tunnel active: $url/mcp")

                while (isActive && session.isConnected) {
                    delay(10000)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Tunnel error", e)
                onError("Error: ${e.message}")
            }
        }
    }

    fun stop() {
        tunnelJob?.cancel()
        sshSession?.disconnect()
        sshSession = null
        publicUrl = ""
    }
}
