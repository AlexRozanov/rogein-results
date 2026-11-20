package ru.rogein.chip.parse

data class Punch(
    val cp: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    val time: String
        get() = "%02d:%02d:%02d".format(hour, minute, second)

    val isStart: Boolean get() = cp == CP_START
    val isFinish: Boolean get() = cp == CP_FINISH
}

data class ChipDump(
    val uid: String,
    val logicalId: Int?,
    val clearedAt: String?,
    val punches: List<Punch>,
    val blockOrder: BlockOrder,
    val pointerEnd: Int?,
    val blockCount: Int,
) {
    fun hasPunch(cp: Int): Boolean = punches.any { it.cp == cp }

    fun withPunch(punch: Punch): ChipDump = copy(punches = punches + punch)
}

enum class BlockOrder {
    /** Как в USB HID SFR basic: [КП][ЧЧ][ММ][СС]. */
    Hid,
    /** Как в экспорте NFC Tools: байты блока наоборот. */
    NfcTools,
}

const val BLOCK_COUNT = 128
const val MIN_BLOCK_COUNT = 5
const val BLOCK_SIZE = 4
const val CP_FINISH = 240
const val CP_START = 241
const val CP_CLEAR = 243
const val MAX_LOGICAL_ID = 39_999

fun nextFreeLogicalId(used: Collection<Int>, vararg extraUsed: Int): Int? {
    val blocked = HashSet<Int>(used.size + extraUsed.size)
    blocked.addAll(used)
    for (id in extraUsed) {
        if (id > 0) blocked.add(id)
    }
    for (id in 1..MAX_LOGICAL_ID) {
        if (id !in blocked) return id
    }
    return null
}

enum class StationCommand {
    Control,
    Start,
    Finish,
    Clear,
    Active,
    Passive,
    Battery,
    ;

    val controlCp: Int?
        get() = when (this) {
            Start -> CP_START
            Finish -> CP_FINISH
            Clear -> CP_CLEAR
            Control, Active, Passive, Battery -> null
        }

    val isService: Boolean
        get() = this == Active || this == Passive || this == Battery

    val modeBlock: ByteArray
        get() = when (this) {
            Control, Start, Finish, Clear -> STATION_MODE_BLOCK
            Active -> stationActiveBlock()
            Passive -> STATION_PASSIVE_BLOCK
            Battery -> STATION_BATTERY_BLOCK
        }

    companion object {
        fun fromPunchCp(cp: Int): StationCommand = when (cp) {
            CP_START -> Start
            CP_FINISH -> Finish
            CP_CLEAR -> Clear
            in 1..255 -> Control
            else -> error("номер КП станции 1…255")
        }
    }
}

data class StationRole(
    val command: StationCommand,
    val cp: Int?,
    val activeSpeed: StationActiveSpeed? = null,
)

val STATION_MODE_BLOCK = byteArrayOf(0x00, 0x01, 0x00, 0x00)
val STATION_PARAM_BLOCK = byteArrayOf(0x00, 0x25, 0x00, 0x00)

/**
 * Скорость опроса чипов в активном режиме станции.
 * Первый байт блока 4: 01=100%, 02=50%, 03=30%, 05=20%, 09=10%.
 */
enum class StationActiveSpeed(val percent: Int, val code: Int) {
    Full(100, 0x01),
    Half(50, 0x02),
    Thirty(30, 0x03),
    Twenty(20, 0x05),
    Ten(10, 0x09),
    ;

    val label: String get() = "$percent%"

    companion object {
        val DEFAULT = Half

        fun fromCode(code: Int): StationActiveSpeed? = entries.find { it.code == code }

        fun fromBlock(block: ByteArray): StationActiveSpeed? {
            if (block.size < BLOCK_SIZE) return null
            if ((block[1].toInt() and 0xFF) != 0x02) return null
            if (block[2].toInt() != 0 || block[3].toInt() != 0) return null
            return fromCode(block[0].toInt() and 0xFF)
        }
    }
}

fun stationActiveBlock(speed: StationActiveSpeed = StationActiveSpeed.DEFAULT): ByteArray =
    byteArrayOf(speed.code.toByte(), 0x02, 0x00, 0x00)

val STATION_ACTIVE_BLOCK = stationActiveBlock(StationActiveSpeed.DEFAULT)
val STATION_PASSIVE_BLOCK = byteArrayOf(0x03, 0x03, 0x03, 0x03)
val STATION_BATTERY_BLOCK = byteArrayOf(0x05, 0x05, 0x05, 0x05)
