package com.msu2.android.ui

import com.msu2.android.usb.Msu2Protocol
import com.msu2.android.usb.Msu2Serial
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

// 闪存数据写入管理器
class FlashWriter {

    companion object {
        const val FLASH_TOTAL_PAGES = 4096
    }

    // 写入数据到设备闪存
    suspend fun flash(
        serial: Msu2Serial,
        data: ByteArray,
        page: Int,
        zk: Boolean,
        onLog: (String) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        var fsize = data.size
        if (zk) fsize -= 6 // 字库文件末尾非点阵数据
        onLog("素材大小 ${data.size} B，有效数据 ${fsize} B")

        val totalPages = if (fsize % 256 != 0) fsize / 256 + 1 else fsize / 256
        if (page < 0 || page + totalPages > FLASH_TOTAL_PAGES) {
            throw IllegalStateException(
                "起始页越界：$page + $totalPages 页超过 Flash 容量（共 $FLASH_TOTAL_PAGES 页）"
            )
        }
        var written = 0

        if (!zk) {
            // 普通图像数据先擦除再写入
            onLog("擦除 $totalPages 页 (起始页 $page)")
            val eraseTimeoutMs = maxOf(10_000L, totalPages * 100L)
            serial.ack(Msu2Protocol.eraseFlashPage(page, totalPages), waitMs = eraseTimeoutMs, requireResponse = true)
            // 擦除后等待硬件状态稳定
            delay(200)
        }

        var off = 0
        var p = page
        while (off + 256 <= fsize) {
            val cmd = buildPageCommands(data, off, 256) +
                Msu2Protocol.writeFlashPage(p, 1, zk)
            serial.ack(cmd, waitMs = 3000)
            off += 256
            p += 1
            written += 1
            onProgress(written, totalPages)
        }
        if (off < fsize) {
            val remain = fsize - off
            val padded = ByteArray(256) { 0xFF.toByte() }
            data.copyInto(padded, 0, off, off + remain)
            val cmd = buildPageCommands(padded, 0, 256) +
                Msu2Protocol.writeFlashPage(p, 1, zk)
            serial.ack(cmd, waitMs = 3000)
            written += 1
            onProgress(written, totalPages)
        }
        onLog("烧写完成（共 ${p - page} 页）")
    }

    // 构造单页闪存数据写入指令序列
    private fun buildPageCommands(data: ByteArray, off: Int, size: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 6)
        for (i in 0 until 64) {
            val b = off + i * 4
            out.write(
                Msu2Protocol.flashDataWrite(
                    i,
                    data[b].toInt() and 0xFF,
                    data[b + 1].toInt() and 0xFF,
                    data[b + 2].toInt() and 0xFF,
                    data[b + 3].toInt() and 0xFF
                )
            )
        }
        return out.toByteArray()
    }
}