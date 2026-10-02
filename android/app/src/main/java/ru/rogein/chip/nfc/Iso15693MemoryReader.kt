package ru.rogein.chip.nfc

import android.nfc.Tag
import android.nfc.tech.NfcV
import ru.rogein.chip.parse.BLOCK_COUNT
import ru.rogein.chip.parse.BLOCK_SIZE
import ru.rogein.chip.parse.BlockOrder
import ru.rogein.chip.parse.SfrChipMemory

class Iso15693MemoryReader {
    fun probe(tag: Tag): Boolean {
        val nfc = NfcV.get(tag) ?: return false
        return try {
            nfc.connect()
            readBlock(nfc, tag.id, 0).size == BLOCK_SIZE
        } catch (_: Exception) {
            false
        } finally {
            closeQuietly(nfc)
        }
    }

    fun writeBlocks(
        tag: Tag,
        order: BlockOrder,
        blocks: List<Pair<Int, ByteArray>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val nfc = NfcV.get(tag) ?: error("Метка не ISO 15693 (NfcV)")
        nfc.connect()
        try {
            blocks.forEachIndexed { index, (block, logical) ->
                val wire = SfrChipMemory.toWire(logical, order)
                writeBlock(nfc, tag.id, block, wire)
                val raw = readBlock(nfc, tag.id, block)
                if (!raw.contentEquals(wire)) {
                    error("Блок $block записался неверно")
                }
                onProgress(index + 1, blocks.size)
            }
        } finally {
            closeQuietly(nfc)
        }
    }

    fun readAllBlocks(tag: Tag): List<ByteArray> {
        val nfc = NfcV.get(tag) ?: error("Метка не ISO 15693 (NfcV)")
        nfc.connect()
        try {
            val uid = tag.id
            val blocks = ArrayList<ByteArray>(BLOCK_COUNT)
            for (block in 0 until BLOCK_COUNT) {
                blocks += readBlock(nfc, uid, block)
            }
            return blocks
        } finally {
            closeQuietly(nfc)
        }
    }

    private fun writeBlock(nfc: NfcV, uid: ByteArray, block: Int, data: ByteArray) {
        val commands = listOf(
            addressedWrite(0x22, uid, block, data),
            addressedWrite(0x20, uid, block, data),
            addressedWrite(0x62, uid, block, data),
            addressedWrite(0x60, uid, block, data),
            unaddressedWrite(0x02, block, data),
            unaddressedWrite(0x00, block, data),
            unaddressedWrite(0x42, block, data),
            unaddressedWrite(0x40, block, data),
        )
        var lastError: Exception? = null
        for (cmd in commands) {
            try {
                val resp = nfc.transceive(cmd)
                if (resp.isNotEmpty() && (resp[0].toInt() and 0x01) == 0) return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("блок $block не записан")
    }

    private fun readBlock(nfc: NfcV, uid: ByteArray, block: Int): ByteArray {
        val commands = listOf(
            addressed(0x22, uid, block),
            addressed(0x20, uid, block),
            unaddressed(0x02, block),
            unaddressed(0x00, block),
        )
        var lastError: Exception? = null
        for (cmd in commands) {
            try {
                val resp = nfc.transceive(cmd)
                extractBlock(resp)?.let { return it }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("блок $block не прочитан")
    }

    private fun addressed(flags: Int, uid: ByteArray, block: Int): ByteArray {
        val cmd = ByteArray(3 + uid.size)
        cmd[0] = flags.toByte()
        cmd[1] = 0x20
        System.arraycopy(uid, 0, cmd, 2, uid.size)
        cmd[cmd.lastIndex] = block.toByte()
        return cmd
    }

    private fun unaddressed(flags: Int, block: Int): ByteArray =
        byteArrayOf(flags.toByte(), 0x20, block.toByte())

    private fun addressedWrite(flags: Int, uid: ByteArray, block: Int, data: ByteArray): ByteArray {
        val cmd = ByteArray(3 + uid.size + data.size)
        cmd[0] = flags.toByte()
        cmd[1] = 0x21
        System.arraycopy(uid, 0, cmd, 2, uid.size)
        cmd[2 + uid.size] = block.toByte()
        System.arraycopy(data, 0, cmd, 3 + uid.size, data.size)
        return cmd
    }

    private fun unaddressedWrite(flags: Int, block: Int, data: ByteArray): ByteArray {
        val cmd = ByteArray(3 + data.size)
        cmd[0] = flags.toByte()
        cmd[1] = 0x21
        cmd[2] = block.toByte()
        System.arraycopy(data, 0, cmd, 3, data.size)
        return cmd
    }

    private fun closeQuietly(nfc: NfcV) {
        try {
            nfc.close()
        } catch (_: Exception) {
        }
    }

    private fun extractBlock(resp: ByteArray): ByteArray? {
        if (resp.isEmpty()) return null
        if ((resp[0].toInt() and 0x01) != 0) return null
        return when {
            resp.size >= 5 -> resp.copyOfRange(1, 1 + BLOCK_SIZE)
            resp.size == BLOCK_SIZE -> resp
            else -> null
        }
    }
}

fun formatUid(id: ByteArray): String =
    id.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
