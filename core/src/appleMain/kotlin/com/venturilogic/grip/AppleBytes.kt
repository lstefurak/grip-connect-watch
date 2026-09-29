package com.venturilogic.grip

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Swift bridging for CoreBluetooth `Data` <-> Kotlin `ByteArray`.
 * From Swift (NSData is imported as `Data`): `AppleBytes.shared.toByteArray(data: value)`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
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
        // convert(): NSUInteger is UInt on arm64_32 (watchosArm64) and ULong elsewhere.
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    }
}
