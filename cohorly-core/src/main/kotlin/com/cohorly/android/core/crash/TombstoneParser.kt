package com.cohorly.android.core.crash

/**
 * Minimal protobuf reader for AOSP `debuggerd/proto/tombstone.proto`, the
 * trace of a `REASON_CRASH_NATIVE` exit record. No dependency; reads only the
 * fields the event needs.
 */
object TombstoneParser {
    class Frame(val relPc: Long, val functionName: String?, val fileName: String, val buildId: String?)

    class Thread(val id: Long, val name: String?, val frames: List<Frame>)

    class Tombstone(
        val tid: Long,
        val signalName: String?,
        val codeName: String?,
        val faultAddress: Long?,
        val abortMessage: String?,
        val threads: List<Thread>,
    )

    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?)

    fun parse(bytes: ByteArray): Tombstone {
        val top = fields(bytes)
        val signal = top.firstOrNull { it.number == 10 }?.bytes?.let(::fields)
        val threads = top.filter { it.number == 16 }.mapNotNull { entry ->
            // map<uint32, Thread> threads = 16: entry key = 1, value = 2.
            val pair = fields(entry.bytes ?: return@mapNotNull null)
            val thread = fields(pair.firstOrNull { it.number == 2 }?.bytes ?: return@mapNotNull null)
            val frames = thread.filter { it.number == 4 && it.bytes != null }.map { raw ->
                val f = fields(raw.bytes!!)
                Frame(
                    relPc = f.num(1) ?: 0,
                    functionName = f.str(4),
                    fileName = f.str(6) ?: "",
                    buildId = f.str(8),
                )
            }
            Thread(id = thread.num(1) ?: pair.num(1) ?: 0, name = thread.str(2), frames = frames)
        }.sortedBy { it.id }
        return Tombstone(
            tid = top.num(6) ?: 0,
            signalName = signal?.str(2),
            codeName = signal?.str(4),
            faultAddress = if (signal?.num(8) == 1L) signal.num(9) ?: 0L else null,
            abortMessage = top.str(14),
            threads = threads,
        )
    }

    private fun fields(buf: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long at $i")
            }
        }

        fun fixed(size: Int): Long {
            var v = 0L
            for (k in 0 until size) v = v or ((buf[i + k].toLong() and 0xff) shl (8 * k))
            i += size
            return v
        }

        while (i < buf.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when (val wire = (key and 7).toInt()) {
                0 -> out.add(Field(number, varint(), null))
                1 -> out.add(Field(number, fixed(8), null))
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > buf.size) throw IllegalStateException("bad length $len at $i")
                    out.add(Field(number, 0, buf.copyOfRange(i, i + len)))
                    i += len
                }
                5 -> out.add(Field(number, fixed(4), null))
                else -> throw IllegalStateException("wire type $wire at $i")
            }
        }
        return out
    }

    private fun List<Field>.str(n: Int): String? =
        firstOrNull { it.number == n && it.bytes != null }?.bytes?.toString(Charsets.UTF_8)

    private fun List<Field>.num(n: Int): Long? = firstOrNull { it.number == n && it.bytes == null }?.varint
}
