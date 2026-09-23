package com.pjarczak.bmcuflasher

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LocalFirmwareTest {
  @Test fun preservesBytesNameAndHash() {
    val original = "abc".toByteArray()
    val firmware = LocalFirmware.read("自定义.BIN", ByteArrayInputStream(original))
    original[0] = 0
    assertEquals("自定义.BIN", firmware.name)
    assertArrayEquals("abc".toByteArray(), firmware.bytes)
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", firmware.sha256)
  }

  @Test fun acceptsLargestImageThatFitsPaddedTransfers() {
    val bytes = ByteArray(LocalFirmware.MAX_BYTES) { (it % 256).toByte() }
    assertArrayEquals(bytes, LocalFirmware.read("full.bin", ByteArrayInputStream(bytes)).bytes)
  }

  @Test fun rejectsEmptyFile() {
    assertEquals("android_bin_empty", assertThrows(IllegalArgumentException::class.java) {
      LocalFirmware.read("empty.bin", ByteArrayInputStream(byteArrayOf()))
    }.message)
  }

  @Test fun rejectsWrongExtensionBeforeReading() {
    val input = object : InputStream() {
      override fun read(): Int = error("Must not read a non-BIN file")
    }
    assertEquals("android_bin_extension", assertThrows(IllegalArgumentException::class.java) {
      LocalFirmware.read("firmware.bin.zip", input)
    }.message)
  }

  @Test fun boundsUnknownSizeStream() {
    var reads = 0
    val input = object : InputStream() {
      override fun read(): Int { reads++; return 42 }
    }
    assertEquals("android_bin_too_large", assertThrows(IllegalArgumentException::class.java) {
      LocalFirmware.read("huge.bin", input)
    }.message)
    assertEquals(LocalFirmware.MAX_BYTES + 1, reads)
  }

  @Test fun handlesShortProviderReads() {
    val input = object : ByteArrayInputStream(ByteArray(257) { it.toByte() }) {
      override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 3))
    }
    assertArrayEquals(ByteArray(257) { it.toByte() }, LocalFirmware.read("short.bin", input).bytes)
  }

  @Test fun propagatesProviderFailureWithoutReturningPartialImage() {
    val input = object : InputStream() {
      override fun read(): Int = throw IOException("Provider disconnected")
    }
    assertThrows(IOException::class.java) { LocalFirmware.read("broken.bin", input) }
  }

  @Test fun invalidImagesNeverTouchUsbOrTtlPorts() {
    val port = Proxy.newProxyInstance(UsbSerialPort::class.java.classLoader,
      arrayOf(UsbSerialPort::class.java)) { _, method, _ ->
      error("Invalid firmware touched port: ${method.name}")
    } as UsbSerialPort
    for (size in listOf(0, LocalFirmware.MAX_BYTES + 1, 65536)) {
      assertThrows(IllegalArgumentException::class.java) {
        BmcuFlasher.flashUsb(port, ByteArray(size), { _, _ -> }, {})
      }
      assertThrows(IllegalArgumentException::class.java) {
        BmcuFlasher.flashTtl(port, ByteArray(size), { _, _ -> }, {})
      }
    }
  }
}
