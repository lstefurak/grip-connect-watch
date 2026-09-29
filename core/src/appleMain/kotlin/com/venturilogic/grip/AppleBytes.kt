package com.venturilogic.grip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Swift bridging for CoreBluetooth `Data` <-> Kotlin `ByteArray`.
 * From Swift: `AppleBytes.shared.toByteArray(data: value as NSData)`.
 */
@OptIn(ExperimentalForeignApi::class)
object AppleBytes {
    fun toByteArray(data: NSData): ByteArray {
        val size = data.length.toInt()
        val out = ByteArray(size)
        if (size > 0) {
            out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
        return out
    }

    fun toNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
}
