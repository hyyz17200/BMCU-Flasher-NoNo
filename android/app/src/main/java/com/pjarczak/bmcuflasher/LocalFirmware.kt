package com.pjarczak.bmcuflasher

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** An immutable-in-use snapshot: the document provider is never reopened for flashing. */
data class LocalFirmware(val name: String, val bytes: ByteArray) {
  val sha256: String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  companion object {
    // The existing ISP writer pads every transfer to 56 bytes, including the last.
    const val MAX_BYTES = (64 * 1024 / 56) * 56 // 65520; padded data must fit 64 KiB.

    fun validateSize(size: Int) {
      require(size > 0) { "android_bin_empty" }
      require(size <= MAX_BYTES) { "android_bin_too_large" }
    }

    fun read(name: String, input: InputStream): LocalFirmware {
      require(name.endsWith(".bin", ignoreCase = true)) { "android_bin_extension" }
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(4096)
      while (true) {
        // Read at most one byte beyond the limit, even for unknown/provider-reported sizes.
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
        if (count < 0) break
        if (count == 0) {
          val next = input.read()
          if (next < 0) break
          output.write(next)
        } else {
          output.write(buffer, 0, count)
        }
        validateSize(output.size())
      }
      validateSize(output.size())
      return LocalFirmware(name, output.toByteArray())
    }
  }
}
