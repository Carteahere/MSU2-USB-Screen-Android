package com.msu2.android.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

// MSU2 设备串口通信封装
class Msu2Serial(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    companion object {
        private const val TAG = "Msu2Serial"
        private const val BAUD = 19200
        private const val BUF_SIZE = 4096
        private const val READ_TIMEOUT = 200
        private const val WRITE_TIMEOUT = 3000
        // 屏幕数据单包分块大小
        private const val SCREEN_CHUNK_SIZE = 60
        // 每块屏幕数据写入超时限制
        private const val SCREEN_WRITE_TIMEOUT = 5000
    }

    private val mutex = Mutex()
    private var port: UsbSerialPort? = null
    private val readBuffer = java.io.ByteArrayOutputStream()

    val isConnected: Boolean get() = port != null

    // 打开串口端口并完成初始化
    suspend fun open() {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IOException("未找到适用于该设备的串口驱动（需 CDC-ACM）")
        val p = driver.ports[0]
        val connection = usbManager.openDevice(device)
            ?: throw IOException("无法打开 USB 设备")
        p.open(connection)
        p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // 与桌面版通信参数保持一致
        try { p.setDTR(true) } catch (_: Exception) {}
        try { p.setRTS(false) } catch (_: Exception) {}
        port = p
        readBuffer.reset()
        Log.i(TAG, "serial opened, ${p.portNumber}")
    }

    // 关闭串口连接
    suspend fun close() {
        mutex.withLock {
            val p = port ?: return
            try { p.close() } catch (_: Exception) {}
            port = null
        }
    }

    // 与硬件设备完成握手确认
    suspend fun handshake(): Int = mutex.withLock {
        // 设备上电后需等待广播信号
        var version = -1
        var lastBytes = ""
        for (attempt in 1..3) {
            version = waitBroadcast()
            if (version >= 0) break
            lastBytes = hex(readBuffer.toByteArray())
            readBuffer.reset()
            Log.w(TAG, "握手第 $attempt 次未收到广播, 收到: $lastBytes")
            delay(300)
        }
        if (version < 0) {
            throw IOException("未收到 MSN 设备广播（共收到 $lastBytes）")
        }
        writeRaw(byteArrayOf(0, 'M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'N'.code.toByte()))
        val confirmed = waitPattern(byteArrayOf(0, 'M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'N'.code.toByte()), 3000)
        if (!confirmed) throw IOException("握手确认失败")
        drainAll()
        version
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02X ".format(it.toInt() and 0xFF) }.trim()

    private suspend fun waitBroadcast(): Int {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            for (i in 0 until buf.size - 5) {
                if (buf[i] == 0.toByte() &&
                    buf[i + 1] == 'M'.code.toByte() &&
                    buf[i + 2] == 'S'.code.toByte() &&
                    buf[i + 3] == 'N'.code.toByte() &&
                    buf[i + 4] in '0'.code.toByte()..'9'.code.toByte() &&
                    buf[i + 5] in '0'.code.toByte()..'9'.code.toByte()
                ) {
                    val ver = (buf[i + 4] - '0'.code.toByte()) * 10 + (buf[i + 5] - '0'.code.toByte())
                    readBuffer.reset()
                    return ver.toInt()
                }
            }
        }
        return -1
    }

    private suspend fun waitPattern(pattern: ByteArray, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            for (i in 0 until buf.size - pattern.size + 1) {
                var match = true
                for (j in pattern.indices) if (buf[i + j] != pattern[j]) { match = false; break }
                if (match) { readBuffer.reset(); return true }
            }
        }
        return false
    }

    // 读取单字节寄存器
    suspend fun readU8(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readU8(add))
        val resp = readResponse()
        if (resp.size >= 6) resp[5].toInt() and 0xFF else 0
    }

    // 读取双字节寄存器
    suspend fun readU16(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readU16(add))
        val resp = readResponse()
        if (resp.size >= 6) ((resp[4].toInt() and 0xFF) * 256 + (resp[5].toInt() and 0xFF)) else 0
    }

    // 读取模拟量采集通道
    suspend fun readAdc(ch: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readAdc(ch))
        val resp = readResponse()
        if (resp.size >= 6) ((resp[4].toInt() and 0xFF) * 256 + (resp[5].toInt() and 0xFF)) else 0
    }

    // 读取闪存指定地址单字节
    suspend fun readFlash(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readFlash(add))
        val resp = readResponse()
        if (resp.size >= 6) resp[5].toInt() and 0xFF else 0
    }

    // 发送指令并等待应答信号
    suspend fun ack(cmd: ByteArray, waitMs: Long = 1000, requireResponse: Boolean = false) {
        mutex.withLock {
            writeRaw(cmd)
            val got = readUntilData(waitMs)
            if (requireResponse && !got) throw IOException("设备无响应（等待 ${waitMs}ms）")
            drainAll()
        }
    }

    // 分包发送屏幕图像数据
    suspend fun sendScreen(cmd: ByteArray, abortCheck: (() -> Boolean)? = null) {
        mutex.withLock {
            var off = 0
            while (off < cmd.size) {
                val n = minOf(SCREEN_CHUNK_SIZE, cmd.size - off)
                writeRaw(cmd.copyOfRange(off, off + n), SCREEN_WRITE_TIMEOUT)
                off += n
                if (off < cmd.size) {
                    // 大包传输期间响应协程取消
                    currentCoroutineContext().ensureActive()
                    if (abortCheck?.invoke() == true) return@withLock
                }
            }
            drainAll()
        }
    }

    // 清空接收缓冲区数据
    suspend fun drain() {
        mutex.withLock { drainAll() }
    }

    private fun writeRaw(data: ByteArray, timeoutMs: Int = WRITE_TIMEOUT) {
        val p = port ?: throw IOException("串口未打开")
        p.write(data, timeoutMs)
    }

    // 等待并提取固定响应报文
    private suspend fun readResponse(): ByteArray {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            if (buf.size >= 6) {
                val resp = buf.copyOfRange(buf.size - 6, buf.size)
                readBuffer.reset()
                return resp
            }
        }
        readBuffer.reset()
        return ByteArray(0)
    }

    // 持续监听直到读取到有效字节
    private suspend fun readUntilData(timeoutMs: Long = 1000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = readChunk(READ_TIMEOUT)
            if (n > 0) return true
        }
        return false
    }

    // 持续排空缓冲区直至通信静默
    private suspend fun drainAll() {
        val deadline = System.currentTimeMillis() + 300
        var silent = 0
        while (System.currentTimeMillis() < deadline) {
            val n = readChunk(30)
            if (n > 0) silent = 0
            else if (++silent >= 3) break
        }
        readBuffer.reset()
    }

    private suspend fun readChunk(timeoutMs: Int): Int {
        val p = port ?: return 0
        val buf = ByteArray(BUF_SIZE)
        val n = try { p.read(buf, timeoutMs) } catch (_: Exception) { 0 }
        if (n > 0) readBuffer.write(buf, 0, n)
        return n
    }
}