package com.msu2.android.usb

// MSU2 屏幕通信协议封装
object Msu2Protocol {

    const val RED = 0xF800
    const val GREEN = 0x07E0
    const val BLUE = 0x001F
    const val WHITE = 0xFFFF
    const val BLACK = 0x0000
    const val YELLOW = 0xFFE0

    const val SCREEN_W = 160
    const val SCREEN_H = 80

    // 投屏模式竖屏基准分辨率
    const val MIRROR_W = 80
    const val MIRROR_H = 160

    // 固件预置字库与背景存储页码
    const val PAGE_ASC64 = 3651
    const val PAGE_CLK_BG = 3826
    const val PAGE_PH1 = 3926
    const val PAGE_N24X33 = 4026
    const val PAGE_MP1 = 4038

    // 动态图片帧数配置
    const val GIF_FRAME_PAGES = 100
    const val GIF_FRAME_COUNT = 36

    // 读取单字节寄存器
    fun readU8(add: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x00, (add ushr 8).toByte(), (add and 0xFF).toByte(), 0x00)

    // 读取双字节寄存器
    fun readU16(add: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x20, (add and 0xFF).toByte(), 0x00, 0x00)

    // 写入单字节寄存器
    fun writeU8(add: Int, data: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x80.toByte(), (add ushr 8).toByte(), (add and 0xFF).toByte(), (data and 0xFF).toByte())

    // 写入双字节寄存器
    fun writeU16(add: Int, data: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0xA0.toByte(), (add and 0xFF).toByte(), (data ushr 8).toByte(), (data and 0xFF).toByte())

    // 读取采集通道电平
    fun readAdc(ch: Int): ByteArray =
        byteArrayOf(0x08, (ch and 0xFF).toByte(), 0x00, 0x00, 0x00, 0x00)

    // 读取闪存单字节数据
    fun readFlash(add: Int): ByteArray =
        byteArrayOf(
            0x03, 0x00,
            ((add ushr 16) and 0xFF).toByte(),
            ((add ushr 8) and 0xFF).toByte(),
            (add and 0xFF).toByte(),
            0x00
        )

    // 擦除指定闪存扇区
    fun eraseFlashPage(add: Int, size: Int): ByteArray =
        byteArrayOf(
            0x03, 0x02,
            ((add % 65536) / 256).toByte(),
            ((add % 65536) % 256).toByte(),
            ((size % 65536) / 256).toByte(),
            ((size % 65536) % 256).toByte()
        )

    // 写入数据到闪存页
    fun writeFlashPage(pageAdd: Int, pageNum: Int, erase: Boolean): ByteArray =
        byteArrayOf(
            0x03, if (erase) 0x01 else 0x03,
            (pageAdd / (256 * 256) and 0xFF).toByte(),
            ((pageAdd % 65536) / 256).toByte(),
            ((pageAdd % 65536) % 256).toByte(),
            (pageNum and 0xFF).toByte()
        )

    // 写入临时缓存数据
    fun flashDataWrite(idx: Int, d0: Int, d1: Int, d2: Int, d3: Int): ByteArray =
        byteArrayOf(
            0x04, (idx and 0xFF).toByte(),
            (d0 and 0xFF).toByte(), (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte(), (d3 and 0xFF).toByte()
        )

    // 设置屏幕绘制起始坐标
    fun lcdSetXY(x: Int, y: Int): ByteArray =
        byteArrayOf(0x02, 0x00, (x ushr 8).toByte(), (x and 0xFF).toByte(), (y ushr 8).toByte(), (y and 0xFF).toByte())

    // 设置屏幕绘制宽高尺寸
    fun lcdSetSize(w: Int, h: Int): ByteArray =
        byteArrayOf(0x02, 0x01, (w ushr 8).toByte(), (w and 0xFF).toByte(), (h ushr 8).toByte(), (h and 0xFF).toByte())

    // 设置前景色与背景色
    fun lcdSetColor(fc: Int, bc: Int): ByteArray =
        byteArrayOf(0x02, 0x02, (fc ushr 8).toByte(), (fc and 0xFF).toByte(), (bc ushr 8).toByte(), (bc and 0xFF).toByte())

    // 发送屏幕底层绘制指令
    fun lcdDisplay(op: Int, d0: Int, d1: Int, d2: Int): ByteArray =
        byteArrayOf(
            0x02, 0x03, (op and 0xFF).toByte(),
            (d0 and 0xFF).toByte(), (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte()
        )

    // 设置显存压缩主色值
    fun lcdSetColorRam(color: Int): ByteArray =
        byteArrayOf(
            0x02, 0x04,
            ((color ushr 24) and 0xFF).toByte(),
            ((color ushr 16) and 0xFF).toByte(),
            ((color ushr 8) and 0xFF).toByte(),
            (color and 0xFF).toByte()
        )

    // 载入显存目标写入地址
    fun lcdLoadAddr(x: Int, y: Int, w: Int, h: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(7, 0, 0, 0)

    // 从闪存读取并显示彩色位图
    fun lcdPhoto(x: Int, y: Int, w: Int, h: Int, pageAdd: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(0, pageAdd / 256, pageAdd % 256, 0)

    // 从闪存读取并显示双色位图
    fun lcdPhotoWb(x: Int, y: Int, w: Int, h: Int, pageAdd: Int, fc: Int, bc: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdSetColor(fc, bc) + lcdDisplay(1, pageAdd / 256, pageAdd % 256, 0)

    // 显示点阵字符图案
    fun lcdAscii32x64Mix(x: Int, y: Int, ch: Char, fc: Int, bgPage: Int, numPage: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetColor(fc, bgPage) +
            byteArrayOf(0x02, 0x03, 0x05, (ch.code and 0xFF).toByte(), (numPage / 256).toByte(), (numPage % 256).toByte())

    // 切换副屏显示旋转角度
    fun lcdState(state: Int): ByteArray =
        byteArrayOf(0x02, 0x03, 0x0A, (state and 0xFF).toByte(), 0x00, 0x00)

    // 指定颜色填充区域
    fun lcdColorFill(x: Int, y: Int, w: Int, h: Int, color: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) +
            byteArrayOf(0x02, 0x03, 0x0B, ((color ushr 8) and 0xFF).toByte(), (color and 0xFF).toByte(), 0x00)

    // 像素阵列转标准色彩字节数组
    fun rgb565Bytes(pixels: IntArray, width: Int, height: Int, stride: Int, out: ByteArray) {
        var o = 0
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val p = pixels[row + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[o++] = (((r shr 3) shl 3) or (g shr 5)).toByte()
                out[o++] = ((((g and 0x1F) shr 2) shl 5) or (b shr 3)).toByte()
            }
        }
    }

    // 编码屏幕像素为指令序列
    fun encodeScreenData(rgb565: ByteArray, xSize: Int, ySize: Int): ByteArray {
        val total = xSize * ySize * 2
        val out = java.io.ByteArrayOutputStream(total * 7 / 6)
        var pos = 0
        while (pos < total - total % 256) {
            encodePage(rgb565, pos, out, fullPage = true, remain = 0)
            pos += 256
        }
        if (total % 256 != 0) {
            encodePage(rgb565, pos, out, fullPage = false, remain = total % 256)
        }
        return out.toByteArray()
    }

    private fun encodePage(rgb565: ByteArray, pos: Int, out: java.io.ByteArrayOutputStream, fullPage: Boolean, remain: Int) {
        // 数据不足时补齐默认字节
        fun byteAt(idx: Int): Int = if (idx < rgb565.size) rgb565[idx].toInt() and 0xFF else 0xFF
        fun groupValue(i: Int): Long {
            val b = pos + i * 4
            return (byteAt(b).toLong() shl 24) or (byteAt(b + 1).toLong() shl 16) or
                    (byteAt(b + 2).toLong() shl 8) or byteAt(b + 3).toLong()
        }
        if (fullPage) {
            // 完整页压缩高频颜色与差异像素
            var best = 0L
            var bestCount = -1
            val counts = HashMap<Long, Int>()
            for (i in 0 until 64) {
                val v = groupValue(i)
                val c = (counts[v] ?: 0) + 1
                counts[v] = c
                if (c > bestCount) { bestCount = c; best = v }
            }
            val colorRam = best
            out.write(lcdSetColorRam(colorRam.toInt()))
            for (i in 0 until 64) {
                if (groupValue(i) != colorRam) {
                    val b = pos + i * 4
                    out.write(flashDataWrite(i, byteAt(b), byteAt(b + 1), byteAt(b + 2), byteAt(b + 3)))
                }
            }
            out.write(lcdDisplay(8, 1, 0, 0))
        } else {
            // 非完整页直接全量发送
            for (i in 0 until 64) {
                val b = pos + i * 4
                out.write(flashDataWrite(i, byteAt(b), byteAt(b + 1), byteAt(b + 2), byteAt(b + 3)))
            }
            out.write(lcdDisplay(8, 0, remain and 0xFF, 0))
        }
    }
}