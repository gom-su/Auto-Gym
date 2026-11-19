package com.example.workoutpoc

import android.util.Base64
import android.util.Log
import com.example.workoutpoc.wearprotocol.PanelProtocol
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking

/** Opt-in loopback fixture. Only the transport changes. Replies come from the installed phone app's handler. */
internal class PhonePanelTestRelay(app: WorkoutApplication, port: Int) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", port))
    }

    private val worker = Thread({
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 4_000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine() ?: return@use
                    val payload = Base64.decode(reader.readLine() ?: return@use, Base64.NO_WRAP)
                    val reply = runBlocking {
                        app.catalog.restore()
                        app.wearBridge.handler.reply(path, payload)
                    }
                    Log.i("AutoGymTestRelay", "${path.substringAfterLast('/')}: ${reply.result}, " +
                        "${reply.snapshot?.mode}, ${reply.snapshot?.timer?.millis}")
                    val response = PanelProtocol.encode(reply)
                    socket.getOutputStream().bufferedWriter().use {
                        it.write(Base64.encodeToString(response, Base64.NO_WRAP))
                        it.newLine()
                    }
                }
            } catch (e: IOException) {
                if (!server.isClosed) Log.w("AutoGymTestRelay", "Loopback request failed", e)
            }
        }
    }, "phone-panel-test-relay").apply { isDaemon = true; start() }

    override fun close() {
        server.close()
        worker.join(5_000)
    }
}
