package dev.pam.nativeapp.protocol

sealed interface WireValue {
    data class Text(val value: String) : WireValue
    data class Integer(val value: Long) : WireValue
    data class Decimal(val value: Double) : WireValue
    data class Flag(val value: Boolean) : WireValue
}
