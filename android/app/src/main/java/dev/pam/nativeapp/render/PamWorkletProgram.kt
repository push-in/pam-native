package dev.pam.nativeapp.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

internal enum class PamWorkletTarget(val value: Int) {
    OPACITY(1),
    TRANSLATION_X(2),
    TRANSLATION_Y(3),
    SCALE(4),
    ROTATION_DEGREES(5);

    companion object {
        fun from(value: Int): PamWorkletTarget? = entries.firstOrNull { it.value == value }
    }
}

internal class PamWorkletProgram private constructor(
    private val instructions: List<Instruction>,
) {
    fun evaluate(input: Double): Double? {
        if (!input.isFinite()) return null
        var value = 0.0
        for (instruction in instructions) {
            val operands = instruction.operands
            value = when (instruction.opcode) {
                1 -> input
                2 -> operands[0]
                3 -> value + operands[0]
                4 -> value - operands[0]
                5 -> value * operands[0]
                6 -> value / operands[0]
                7 -> min(max(value, operands[0]), operands[1])
                8 -> operands[2] +
                    ((value - operands[0]) / (operands[1] - operands[0])) *
                    (operands[3] - operands[2])
                else -> return null
            }
            if (!value.isFinite()) return null
        }
        return value
    }

    companion object {
        private val operandCounts = intArrayOf(0, 0, 1, 1, 1, 1, 1, 2, 4)

        fun decode(bytes: ByteArray): PamWorkletProgram? {
            if (bytes.size !in 8..(6 + 256 * 34) || !bytes.copyOfRange(0, 4).contentEquals("PNW1".toByteArray())) {
                return null
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(4)
            val count = buffer.short.toInt() and 0xffff
            if (count !in 1..256) return null
            val instructions = ArrayList<Instruction>(count)
            repeat(count) {
                if (buffer.remaining() < 2) return null
                val opcode = buffer.get().toInt() and 0xff
                val operands = buffer.get().toInt() and 0xff
                if (opcode !in 1..8 || operands != operandCounts[opcode] || buffer.remaining() < operands * 8) {
                    return null
                }
                val values = DoubleArray(operands) { buffer.double }
                if (values.any { !it.isFinite() }) return null
                if (opcode == 6 && values[0] == 0.0) return null
                if (opcode == 7 && values[0] > values[1]) return null
                if (opcode == 8 && values[0] == values[1]) return null
                instructions += Instruction(opcode, values)
            }
            if (buffer.hasRemaining()) return null
            return PamWorkletProgram(instructions)
        }
    }

    private data class Instruction(val opcode: Int, val operands: DoubleArray)
}
