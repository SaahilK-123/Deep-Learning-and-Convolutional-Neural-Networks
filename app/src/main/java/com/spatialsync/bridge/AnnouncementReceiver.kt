// AnnouncementReceiver
package com.spatialsync.bridge

import android.util.Log
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AnnouncementReceiver(
    private val host: String,
    private val port: Int = 9998,
    private val onAnnouncement: (className: String, quadrant: String, distance: String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        running.set(true)
        thread = Thread {
            while (running.get()) {
                try {
                    Socket(host, port).use { socket ->
                        Log.d("AnnouncementReceiver", "Connected to $host:$port")
                        val input = DataInputStream(socket.getInputStream())
                        while (running.get()) {
                            val len = input.readInt()
                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            val parts = String(bytes, Charsets.UTF_8).split("|")
                            if (parts.size == 3) {
                                onAnnouncement(parts[0], parts[1], parts[2])
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AnnouncementReceiver", "Connection lost, retrying: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }
}