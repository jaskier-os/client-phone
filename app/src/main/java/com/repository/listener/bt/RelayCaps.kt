package com.repository.listener.bt

/**
 * Minimal Caps-compatible shim for the new direct-RFCOMM transport.
 *
 * Mirrors the Rokid Caps API surface that PhoneBtHost depends on:
 *   val c = RelayCaps(); c.write("a"); c.write("b")
 *   c.at(0).getString() == "a"
 */
class RelayCaps {
    private val args = mutableListOf<String>()

    fun write(s: String): RelayCaps {
        args.add(s)
        return this
    }

    fun at(i: Int): RelayCapsItem = RelayCapsItem(args.getOrElse(i) { "" })

    fun asArray(): Array<String> = args.toTypedArray()

    fun size(): Int = args.size
}

class RelayCapsItem(private val s: String) {
    fun getString(): String = s
}

/**
 * Transport-agnostic channel listener interface. Mirrors the former CustomCmdListener shape.
 */
interface RelayListener {
    fun onCustomCmd(cmd: String?, args: RelayCaps?)
}
