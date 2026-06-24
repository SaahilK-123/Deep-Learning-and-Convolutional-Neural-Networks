//FrameSender.kt
package com.spatialsync.bridge

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class FrameSender(private val host: String, private val port: Int = 9999) {

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(5)
    private var thread: Thread? = null

    fun start() {
        running.set(true)
        thread = Thread {
            while (running.get()) {
                try {
                    Socket(host, port).use { socket ->
                        val out = DataOutputStream(socket.getOutputStream())
                        Log.d("FrameSender", "Connected to $host:$port")
                        while (running.get()) {
                            val bytes = queue.take()
                            out.writeInt(bytes.size)
                            out.write(bytes)
                            out.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FrameSender", "Connection lost, retrying: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }.also { it.start() }
    }

    fun sendFrame(bitmap: Bitmap) {
        if (!running.get()) return
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val bytes = stream.toByteArray()
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }
}