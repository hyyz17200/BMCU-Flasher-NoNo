package com.pjarczak.bmcuflasher

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.lang.Thread.sleep

object BmcuFlasher {
  private val MAGIC_REQ = byteArrayOf(0x57.toByte(), 0xAB.toByte())
  private val MAGIC_RSP = byteArrayOf(0x55.toByte(), 0xAA.toByte())

  private const val CMD_IDENTIFY = 0xA1
  private const val CMD_ISP_END = 0xA2
  private const val CMD_ISP_KEY = 0xA3
  private const val CMD_ERASE = 0xA4
  private const val CMD_PROGRAM = 0xA5
  private const val CMD_VERIFY = 0xA6
  private const val CMD_READ_CFG = 0xA7
  private const val CMD_WRITE_CFG = 0xA8
  private const val CMD_SET_BAUD = 0xC5

  private const val BMCU_DEVICE_ID = 0x31
  private const val BMCU_DEVICE_TYPE = 0x19
  private const val BMCU_CFG_MASK = 0x1F

  private const val CFG_MASK_RDPR_USER_DATA_WPR = 0x07
  private const val CH32V203C8_FLASH_KB = 64

  private const val BMCU_CHUNK = 56

  private fun u16le(x: Int) = byteArrayOf((x and 0xFF).toByte(), ((x ushr 8) and 0xFF).toByte())
  private fun u32le(x: Int) = byteArrayOf(
    (x and 0xFF).toByte(),
    ((x ushr 8) and 0xFF).toByte(),
    ((x ushr 16) and 0xFF).toByte(),
    ((x ushr 24) and 0xFF).toByte()
  )

  private fun checksum(payload: ByteArray): Int {
    var s = 0
    for (b in payload) s = (s + (b.toInt() and 0xFF)) and 0xFF
    return s
  }

  private fun sumU8(payload: ByteArray): Int {
    var s = 0
    for (b in payload) s = (s + (b.toInt() and 0xFF)) and 0xFF
    return s
  }

  private fun packReq(payload: ByteArray): ByteArray {
    val out = ByteArray(MAGIC_REQ.size + payload.size + 1)
    System.arraycopy(MAGIC_REQ, 0, out, 0, MAGIC_REQ.size)
    System.arraycopy(payload, 0, out, MAGIC_REQ.size, payload.size)
    out[out.size - 1] = checksum(payload).toByte()
    return out
  }

  private fun buildIdentify(deviceId: Int, deviceType: Int): ByteArray {
    val tail = "MCU ISP & WCH.CN".toByteArray(Charsets.US_ASCII)
    val data = byteArrayOf((deviceId and 0xFF).toByte(), (deviceType and 0xFF).toByte()) + tail
    val payload = byteArrayOf(CMD_IDENTIFY.toByte()) + u16le(data.size) + data
    return packReq(payload)
  }

  private fun buildReadCfg(mask: Int): ByteArray {
    val data = byteArrayOf((mask and 0xFF).toByte(), 0x00)
    val payload = byteArrayOf(CMD_READ_CFG.toByte()) + u16le(data.size) + data
    return packReq(payload)
  }

  private fun buildWriteCfg(mask: Int, data: ByteArray): ByteArray {
    val payload = byteArrayOf(CMD_WRITE_CFG.toByte()) + u16le(2 + data.size) + byteArrayOf((mask and 0xFF).toByte(), 0x00) + data
    return packReq(payload)
  }

  private fun buildIspKey(seed: ByteArray): ByteArray {
    val payload = byteArrayOf(CMD_ISP_KEY.toByte()) + u16le(seed.size) + seed
    return packReq(payload)
  }

  private fun buildErase(sectors: Int): ByteArray {
    val payload = byteArrayOf(CMD_ERASE.toByte()) + u16le(4) + u32le(sectors)
    return packReq(payload)
  }

  private fun buildSetBaud(baud: Int): ByteArray {
    val payload = byteArrayOf(CMD_SET_BAUD.toByte()) + u16le(4) + u32le(baud)
    return packReq(payload)
  }

  private fun buildProgram(addr: Int, padding: Int, data: ByteArray): ByteArray {
    val ln = 4 + 1 + data.size
    val payload = byteArrayOf(CMD_PROGRAM.toByte()) + u16le(ln) + u32le(addr) + byteArrayOf((padding and 0xFF).toByte()) + data
    return packReq(payload)
  }

  private fun buildVerify(addr: Int, padding: Int, data: ByteArray): ByteArray {
    val ln = 4 + 1 + data.size
    val payload = byteArrayOf(CMD_VERIFY.toByte()) + u16le(ln) + u32le(addr) + byteArrayOf((padding and 0xFF).toByte()) + data
    return packReq(payload)
  }

  private fun buildIspEnd(reason: Int): ByteArray {
    val payload = byteArrayOf(CMD_ISP_END.toByte()) + u16le(1) + byteArrayOf((reason and 0xFF).toByte())
    return packReq(payload)
  }

  private fun xorCrypt(data: ByteArray, key8: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    for (i in data.indices) out[i] = (data[i].toInt() xor (key8[i and 7].toInt())).toByte()
    return out
  }

  private fun calcXorKeySeed(seed: ByteArray, uidChk: Int, chipId: Int): ByteArray {
    if (seed.size < 8) throw IllegalArgumentException("seed too short")
    val a = seed.size / 5
    val b = seed.size / 7
    val k0 = (seed[b * 4].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k1 = (seed[a].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k2 = (seed[b].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k3 = (seed[b * 6].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k4 = (seed[b * 3].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k5 = (seed[a * 3].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k6 = (seed[b * 5].toInt() and 0xFF) xor (uidChk and 0xFF)
    val k7 = (k0 + (chipId and 0xFF)) and 0xFF
    return byteArrayOf(k0.toByte(), k1.toByte(), k2.toByte(), k3.toByte(), k4.toByte(), k5.toByte(), k6.toByte(), k7.toByte())
  }

  private fun calcXorKeyUid(uid8: ByteArray, chipId: Int): ByteArray {
    var s = 0
    for (b in uid8) s = (s + (b.toInt() and 0xFF)) and 0xFF
    val k = ByteArray(8) { s.toByte() }
    k[7] = (((k[7].toInt() and 0xFF) + (chipId and 0xFF)) and 0xFF).toByte()
    return k
  }

  private data class AutoDi(val bootIsDtr: Boolean, val bootAssert: Boolean, val resetAssert: Boolean)

  private class WchIsp(private val port: UsbSerialPort, private var baud: Int, private val trace: Boolean) {
    private val rx = ArrayList<Byte>(8192)

    fun setBaud(newBaud: Int) {
      baud = newBaud
      port.setParameters(newBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    fun flush() {
      try { port.purgeHwBuffers(true, true) } catch (_: Throwable) {}
      rx.clear()
    }

    private fun readAvail() {
      val buf = ByteArray(4096)
      val n = try { port.read(buf, 20) } catch (_: Throwable) { 0 }
      if (n > 0) {
        for (i in 0 until n) rx.add(buf[i])
      }
    }

    fun txrx(pkt: ByteArray, expectCmd: Int, timeoutMs: Long): Pair<Int, ByteArray> {
      port.write(pkt, 1000)
      return recv(expectCmd, timeoutMs)
    }

    fun recv(expectCmd: Int, timeoutMs: Long): Pair<Int, ByteArray> {
      val end = System.nanoTime() + timeoutMs * 1_000_000L
      while (true) {
        if (System.nanoTime() >= end) throw RuntimeException("timeout waiting for cmd=0x%02x".format(expectCmd))
        readAvail()

        val i = findMagic()
        if (i >= 0) {
          if (i > 0) rx.subList(0, i).clear()
          if (rx.size >= 2 + 4 + 1) {
            val cmd = rx[2].toInt() and 0xFF
            val ln = (rx[4].toInt() and 0xFF) or ((rx[5].toInt() and 0xFF) shl 8)
            val total = 2 + 4 + ln + 1
            if (rx.size >= total) {
              val frame = ByteArray(total)
              for (k in 0 until total) frame[k] = rx[k]
              rx.subList(0, total).clear()

              val pay = frame.copyOfRange(2, frame.size - 1)
              if ((checksum(pay) and 0xFF) != (frame[frame.size - 1].toInt() and 0xFF)) continue
              if (cmd != expectCmd) continue

              val code = frame[3].toInt() and 0xFF
              val data = frame.copyOfRange(6, frame.size - 1)
              return code to data
            }
          }
        }

        sleep(2)
      }
    }

    private fun findMagic(): Int {
      val n = rx.size
      if (n < 2) return -1
      for (i in 0 until (n - 1)) {
        if (rx[i] == MAGIC_RSP[0] && rx[i + 1] == MAGIC_RSP[1]) return i
      }
      return -1
    }
  }

  private fun setLines(port: UsbSerialPort, bootIsDtr: Boolean, bootVal: Boolean, resetVal: Boolean) {
    if (bootIsDtr) {
      port.setDTR(bootVal)
      port.setRTS(resetVal)
    } else {
      port.setRTS(bootVal)
      port.setDTR(resetVal)
    }
  }

  private fun pulseReset(port: UsbSerialPort, bootIsDtr: Boolean, resetAssert: Boolean) {
    if (bootIsDtr) {
      port.setRTS(!resetAssert)
      sleep(20)
      port.setRTS(resetAssert)
    } else {
      port.setDTR(!resetAssert)
      sleep(20)
      port.setDTR(resetAssert)
    }
  }

  private fun autodiTry(isp: WchIsp, port: UsbSerialPort, identifyPkt: ByteArray): AutoDi? {
    val combos = ArrayList<AutoDi>(8)
    for (bootIsDtr in listOf(true, false)) {
      for (bootAssert in listOf(true, false)) {
        for (resetAssert in listOf(true, false)) {
          combos.add(AutoDi(bootIsDtr, bootAssert, resetAssert))
        }
      }
    }

    for (c in combos) {
      try {
        isp.flush()
        setLines(port, c.bootIsDtr, c.bootAssert, c.resetAssert)
        sleep(20)
        pulseReset(port, c.bootIsDtr, c.resetAssert)
        sleep(60)
        isp.flush()
        val (code, data) = isp.txrx(identifyPkt, CMD_IDENTIFY, 600)
        if (code == 0x00 && data.size >= 2) return c
      } catch (_: Throwable) {}
    }
    return null
  }

  fun flashUsb(
    port: UsbSerialPort,
    firmware: ByteArray,
    log: (String, String) -> Unit,
    progress: (Int) -> Unit,
    baud: Int = 115200,
    fastBaud: Int = 1_000_000,
    noFast: Boolean = false,
    verify: Boolean = true
  ) {
    LocalFirmware.validateSize(firmware.size)
    fun l(level: String, msg: String) = log(level, msg)

    val blocks = (firmware.size + BMCU_CHUNK - 1) / BMCU_CHUNK
    val fwPad = ByteArray(blocks * BMCU_CHUNK)
    System.arraycopy(firmware, 0, fwPad, 0, firmware.size)
    for (i in firmware.size until fwPad.size) fwPad[i] = 0xFF.toByte()

    val isp = WchIsp(port, baud, trace = false)
    isp.setBaud(baud)

    val identifyPkt = buildIdentify(BMCU_DEVICE_ID, BMCU_DEVICE_TYPE)

    l("INFO", "autodi...")
    val autodi = autodiTry(isp, port, identifyPkt) ?: throw RuntimeException("autodi failed (usb mode)")
    l("INFO", "autodi ok boot_is_dtr=${if (autodi.bootIsDtr) 1 else 0} boot_assert=${if (autodi.bootAssert) 1 else 0} reset_assert=${if (autodi.resetAssert) 1 else 0}")

    l("INFO", "stage identify @ host_baud=$baud")
    val (idCode, idData) = isp.txrx(identifyPkt, CMD_IDENTIFY, 1000)
    if (idCode != 0x00 || idData.size < 2) throw RuntimeException("identify failed")
    val chipId = idData[0].toInt() and 0xFF
    val chipType = idData[1].toInt() and 0xFF
    if (chipId != BMCU_DEVICE_ID || chipType != BMCU_DEVICE_TYPE) throw RuntimeException("unexpected chip_id/type: 0x%02x/0x%02x".format(chipId, chipType))
    l("INFO", "identify ok chip_id=0x%02x chip_type=0x%02x".format(chipId, chipType))

    val flashKb = CH32V203C8_FLASH_KB
    val flashBytes = flashKb * 1024

    l("INFO", "stage read_cfg (A7)")
    val (cfgCode, cfgRaw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfgCode != 0x00 || cfgRaw.size < 14) throw RuntimeException("read_cfg failed")

    var cfg12 = cfgRaw.copyOfRange(2, 14)
    var wpr = cfg12.copyOfRange(8, 12)
    var uid = if (cfgRaw.size >= 8) cfgRaw.copyOfRange(cfgRaw.size - 8, cfgRaw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage write_cfg step1 (A8)")
    val cfg12a = cfg12.clone()
    cfg12a[0] = 0xA5.toByte(); cfg12a[1] = 0x5A.toByte(); cfg12a[2] = 0x3F.toByte(); cfg12a[3] = 0xC0.toByte()
    cfg12a[4] = 0x00.toByte(); cfg12a[5] = 0xFF.toByte(); cfg12a[6] = 0x00.toByte(); cfg12a[7] = 0xFF.toByte()
    cfg12a[8] = 0xFF.toByte(); cfg12a[9] = 0xFF.toByte(); cfg12a[10] = 0xFF.toByte(); cfg12a[11] = 0xFF.toByte()

    val (w1Code, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfg12a), CMD_WRITE_CFG, 2000)
    if (w1Code != 0x00) throw RuntimeException("write_cfg (wchtool step1) failed")

    val (cfg1Code, cfg1Raw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfg1Code != 0x00 || cfg1Raw.size < 14) throw RuntimeException("read_cfg after write_cfg (wchtool step1) failed")

    cfg12 = cfg1Raw.copyOfRange(2, 14)
    wpr = cfg12.copyOfRange(8, 12)
    uid = if (cfg1Raw.size >= 8) cfg1Raw.copyOfRange(cfg1Raw.size - 8, cfg1Raw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_step1 rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage isp_end reason=0x01 (apply option bytes)")
    try { isp.txrx(buildIspEnd(1), CMD_ISP_END, 1200) } catch (_: Throwable) {}

    l("INFO", "usb: re-enter bootloader (autodi)")
    val endRe = System.nanoTime() + 2500L * 1_000_000L
    while (true) {
      try {
        isp.flush()
        setLines(port, autodi.bootIsDtr, autodi.bootAssert, autodi.resetAssert)
        sleep(20)
        pulseReset(port, autodi.bootIsDtr, autodi.resetAssert)
        sleep(80)
        isp.flush()
        val (c2, d2) = isp.txrx(identifyPkt, CMD_IDENTIFY, 800)
        if (c2 == 0x00 && d2.size >= 2) break
      } catch (_: Throwable) {}
      if (System.nanoTime() >= endRe) throw RuntimeException("identify failed (usb) after isp_end(01). last=timeout")
      sleep(100)
    }

    l("INFO", "bootloader detected again (after isp_end(01))")

    repeat(2) {
      val (c2, d2) = isp.txrx(identifyPkt, CMD_IDENTIFY, 1000)
      if (c2 != 0x00 || d2.size < 2) throw RuntimeException("identify failed after re-enter (wchtool)")
      val (c3, cfgX) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
      if (c3 != 0x00 || cfgX.size < 14) throw RuntimeException("read_cfg failed after re-enter (wchtool)")
      cfg12 = cfgX.copyOfRange(2, 14)
      wpr = cfg12.copyOfRange(8, 12)
      uid = if (cfgX.size >= 8) cfgX.copyOfRange(cfgX.size - 8, cfgX.size) else ByteArray(0)
    }

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_reenter rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage write_cfg step2 (A8)")
    val cfg12b = cfg12.clone()
    cfg12b[0] = 0xFF.toByte(); cfg12b[1] = 0xFF.toByte(); cfg12b[2] = 0x3F.toByte(); cfg12b[3] = 0xC0.toByte()
    cfg12b[4] = 0x00.toByte(); cfg12b[5] = 0x00.toByte(); cfg12b[6] = 0x00.toByte(); cfg12b[7] = 0x00.toByte()
    cfg12b[8] = 0xFF.toByte(); cfg12b[9] = 0xFF.toByte(); cfg12b[10] = 0xFF.toByte(); cfg12b[11] = 0xFF.toByte()

    val (w2Code, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfg12b), CMD_WRITE_CFG, 2000)
    if (w2Code != 0x00) throw RuntimeException("write_cfg (wchtool step2) failed")

    val (cfg2Code, cfg2Raw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfg2Code != 0x00 || cfg2Raw.size < 14) throw RuntimeException("read_cfg after write_cfg (wchtool step2) failed")

    cfg12 = cfg2Raw.copyOfRange(2, 14)
    wpr = cfg12.copyOfRange(8, 12)
    uid = if (cfg2Raw.size >= 8) cfg2Raw.copyOfRange(cfg2Raw.size - 8, cfg2Raw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_step2 rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "stage isp_key (A3)")
    val seed = ByteArray(0x1E) { 0x00 }
    val (kCode, kResp) = isp.txrx(buildIspKey(seed), CMD_ISP_KEY, 1200)
    if (kCode != 0x00 || kResp.isEmpty()) throw RuntimeException("isp_key failed")
    val bootSum = kResp[0].toInt() and 0xFF

    val uidChk = cfg2Raw[2].toInt() and 0xFF
    val candidates = ArrayList<Pair<String, ByteArray>>(2)
    if (uid.size == 8) candidates.add("uid" to calcXorKeyUid(uid, chipId))
    candidates.add("seed" to calcXorKeySeed(seed, uidChk, chipId))

    val picked = candidates.filter { (_, key) -> (sumU8(key) == bootSum) }
    if (picked.isEmpty()) {
      var msg = "isp_key checksum mismatch: boot=0x%02x ".format(bootSum)
      for ((name, key) in candidates) {
        msg += "$name=0x%02x ".format(sumU8(key))
      }
      throw RuntimeException(msg.trim())
    }

    val best = picked.sortedBy { if (it.first == "uid") 0 else 1 }.first()
    val xorKey = best.second
    l("INFO", "isp_key ok (src=${best.first} key_sum=0x%02x)".format(bootSum))
    l("INFO", "unlock ok")

    val wprIsFf = (wpr.size == 4 && wpr[0] == 0xFF.toByte() && wpr[1] == 0xFF.toByte() && wpr[2] == 0xFF.toByte() && wpr[3] == 0xFF.toByte())
    if (!wprIsFf) {
      l("WARN", "code flash protected (WPR=${wpr.toHex()}) -> clearing WPR + RDPR")
      val cfgUp = cfg12.clone()
      cfgUp[0] = 0xA5.toByte()
      cfgUp[1] = 0x5A.toByte()
      cfgUp[8] = 0xFF.toByte(); cfgUp[9] = 0xFF.toByte(); cfgUp[10] = 0xFF.toByte(); cfgUp[11] = 0xFF.toByte()

      val (ucCode, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfgUp), CMD_WRITE_CFG, 2000)
      if (ucCode != 0x00) throw RuntimeException("write_cfg (unprotect) failed")

      sleep(80)
      val (rc, cfgU) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
      if (rc != 0x00 || cfgU.size < 14) throw RuntimeException("read_cfg after unprotect failed")

      val wpr2 = cfgU.copyOfRange(2 + 8, 2 + 12)
      l("INFO", "cfg_wpr(after)=${wpr2.toHex()}")
      val wpr2IsFf = (wpr2.size == 4 && wpr2[0] == 0xFF.toByte() && wpr2[1] == 0xFF.toByte() && wpr2[2] == 0xFF.toByte() && wpr2[3] == 0xFF.toByte())
      if (!wpr2IsFf) {
        throw RuntimeException("WPR still not cleared (needs reset/power-cycle to apply option bytes). Re-enter bootloader and retry.")
      }
      l("INFO", "unprotect ok")
    }

    l("INFO", "stage erase sectors=$flashKb (full erase)")
    val (ec, _) = isp.txrx(buildErase(flashKb), CMD_ERASE, 12000)
    if (ec != 0x00) throw RuntimeException("erase failed")
    l("INFO", "erase ok")

    val tailAddr = ((flashBytes - BMCU_CHUNK) / BMCU_CHUNK) * BMCU_CHUNK
    val ffEnc = xorCrypt(ByteArray(BMCU_CHUNK) { 0xFF.toByte() }, xorKey)
    val (vcTail, _) = isp.txrx(buildVerify(tailAddr, 0x00, ffEnc), CMD_VERIFY, 1800)
    if (vcTail != 0x00) throw RuntimeException("erase incomplete (tail not erased) addr=0x%08x".format(tailAddr))

    if (!noFast) {
      l("INFO", "stage set_baud mcu=$fastBaud")
      val (bc, _) = isp.txrx(buildSetBaud(fastBaud), CMD_SET_BAUD, 1200)
      if (bc != 0x00) throw RuntimeException("set_baud failed")
      sleep(30)
      isp.setBaud(fastBaud)
      isp.flush()
      l("INFO", "set_baud ok host_baud=$fastBaud")
    }

    progress(0)
    l("INFO", "stage program addr=0x00000000..0x%08x blocks=$blocks chunk=$BMCU_CHUNK verify=${if (verify) "on" else "off"} every=1 +last".format(blocks * BMCU_CHUNK))

    var lastUi = -1
    var lastLog = -1

    for (i in 0 until blocks) {
      val addr = i * BMCU_CHUNK
      val plain = fwPad.copyOfRange(addr, addr + BMCU_CHUNK)
      val enc = xorCrypt(plain, xorKey)
      val (pc, _) = isp.txrx(buildProgram(addr, 0x00, enc), CMD_PROGRAM, 1800)
      if (pc != 0x00) throw RuntimeException("program failed at 0x%08x".format(addr))

      val stagePct = ((i + 1) * 100) / blocks
      val uiPct = ((i + 1) * 50) / blocks

      if (uiPct != lastUi) {
        lastUi = uiPct
        progress(uiPct)
      }
      if ((stagePct % 10) == 0 && stagePct != lastLog) {
        lastLog = stagePct
        l("INFO", "program $stagePct%% addr=0x%08x".format((i + 1) * BMCU_CHUNK))
      }
    }

    val flushAddr = blocks * BMCU_CHUNK
    l("INFO", "stage program_flush addr=0x%08x (A5 empty)".format(flushAddr))
    val (pfc, _) = isp.txrx(buildProgram(flushAddr, 0x00, ByteArray(0)), CMD_PROGRAM, 2200)
    if (pfc != 0x00) throw RuntimeException("program_flush failed")

    progress(50)
    l("INFO", "program done")

    if (verify) {
      l("INFO", "stage isp_key (A3) before verify")
      val (kc2, k2) = isp.txrx(buildIspKey(seed), CMD_ISP_KEY, 1200)
      if (kc2 != 0x00 || k2.isEmpty() || ((k2[0].toInt() and 0xFF) != bootSum)) throw RuntimeException("isp_key before verify failed")

      l("INFO", "stage verify blocks=$blocks/$blocks")
      lastUi = -1
      lastLog = -1

      for (j in 0 until blocks) {
        val addr = j * BMCU_CHUNK
        val plain = fwPad.copyOfRange(addr, addr + BMCU_CHUNK)
        val enc = xorCrypt(plain, xorKey)
        val (vcc, _) = isp.txrx(buildVerify(addr, 0x00, enc), CMD_VERIFY, 1800)
        if (vcc != 0x00) throw RuntimeException("verify failed at 0x%08x".format(addr))

        val stagePct = ((j + 1) * 100) / blocks
        val uiPct = 50 + ((j + 1) * 50) / blocks

        if (uiPct != lastUi) {
          lastUi = uiPct
          progress(uiPct)
        }
        if ((stagePct % 10) == 0 && stagePct != lastLog) {
          lastLog = stagePct
          l("INFO", "verify $stagePct%% addr=0x%08x".format(addr))
        }
      }

      l("INFO", "verify ok")
    }

    l("INFO", "stage isp_end")
    try { isp.txrx(buildIspEnd(0), CMD_ISP_END, 1200) } catch (_: Throwable) {}

    setLines(port, autodi.bootIsDtr, !autodi.bootAssert, autodi.resetAssert)
    sleep(20)
    pulseReset(port, autodi.bootIsDtr, autodi.resetAssert)

    progress(100)
    l("INFO", "OK")
  }

  private fun waitForTtlIdentify(isp: WchIsp, identifyPkt: ByteArray, log: (String, String) -> Unit, startMsg: String, failPrefix: String) {
    log("ACTION", startMsg)
    val end = System.nanoTime() + 12_000L * 1_000_000L
    var lastErr = "timeout"

    while (true) {
      try {
        isp.flush()
        val (code, data) = isp.txrx(identifyPkt, CMD_IDENTIFY, 800)
        if (code == 0x00 && data.size >= 2) {
          log("INFO", "bootloader detected (ISP active)")
          return
        }
        lastErr = "identify bad response"
      } catch (e: Throwable) {
        lastErr = e.message ?: e.toString()
      }

      if (System.nanoTime() >= end) throw RuntimeException("$failPrefix. last=$lastErr")
      sleep(150)
    }
  }

  fun flashTtl(
    port: UsbSerialPort,
    firmware: ByteArray,
    log: (String, String) -> Unit,
    progress: (Int) -> Unit,
    baud: Int = 115200,
    fastBaud: Int = 1_000_000,
    noFast: Boolean = false,
    verify: Boolean = true
  ) {
    LocalFirmware.validateSize(firmware.size)
    fun l(level: String, msg: String) = log(level, msg)

    val blocks = (firmware.size + BMCU_CHUNK - 1) / BMCU_CHUNK
    val fwPad = ByteArray(blocks * BMCU_CHUNK)
    System.arraycopy(firmware, 0, fwPad, 0, firmware.size)
    for (i in firmware.size until fwPad.size) fwPad[i] = 0xFF.toByte()

    val isp = WchIsp(port, baud, trace = false)
    isp.setBaud(baud)

    val identifyPkt = buildIdentify(BMCU_DEVICE_ID, BMCU_DEVICE_TYPE)

    l("INFO", "stage identify @ host_baud=$baud")
    waitForTtlIdentify(
      isp = isp,
      identifyPkt = identifyPkt,
      log = log,
      startMsg = "ttl mode: enter bootloader now (hold BOOT, tap RESET). waiting for ISP...",
      failPrefix = "identify failed (ttl). enter bootloader first"
    )

    val (idCode, idData) = isp.txrx(identifyPkt, CMD_IDENTIFY, 1000)
    if (idCode != 0x00 || idData.size < 2) throw RuntimeException("identify failed")
    val chipId = idData[0].toInt() and 0xFF
    val chipType = idData[1].toInt() and 0xFF
    if (chipId != BMCU_DEVICE_ID || chipType != BMCU_DEVICE_TYPE) throw RuntimeException("unexpected chip_id/type: 0x%02x/0x%02x".format(chipId, chipType))
    l("INFO", "identify ok chip_id=0x%02x chip_type=0x%02x".format(chipId, chipType))

    val flashKb = CH32V203C8_FLASH_KB
    val flashBytes = flashKb * 1024

    l("INFO", "stage read_cfg (A7)")
    val (cfgCode, cfgRaw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfgCode != 0x00 || cfgRaw.size < 14) throw RuntimeException("read_cfg failed")

    var cfg12 = cfgRaw.copyOfRange(2, 14)
    var wpr = cfg12.copyOfRange(8, 12)
    var uid = if (cfgRaw.size >= 8) cfgRaw.copyOfRange(cfgRaw.size - 8, cfgRaw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage write_cfg step1 (A8)")
    val cfg12a = cfg12.clone()
    cfg12a[0] = 0xA5.toByte(); cfg12a[1] = 0x5A.toByte(); cfg12a[2] = 0x3F.toByte(); cfg12a[3] = 0xC0.toByte()
    cfg12a[4] = 0x00.toByte(); cfg12a[5] = 0xFF.toByte(); cfg12a[6] = 0x00.toByte(); cfg12a[7] = 0xFF.toByte()
    cfg12a[8] = 0xFF.toByte(); cfg12a[9] = 0xFF.toByte(); cfg12a[10] = 0xFF.toByte(); cfg12a[11] = 0xFF.toByte()

    val (w1Code, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfg12a), CMD_WRITE_CFG, 2000)
    if (w1Code != 0x00) throw RuntimeException("write_cfg (wchtool step1) failed")

    val (cfg1Code, cfg1Raw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfg1Code != 0x00 || cfg1Raw.size < 14) throw RuntimeException("read_cfg after write_cfg (wchtool step1) failed")

    cfg12 = cfg1Raw.copyOfRange(2, 14)
    wpr = cfg12.copyOfRange(8, 12)
    uid = if (cfg1Raw.size >= 8) cfg1Raw.copyOfRange(cfg1Raw.size - 8, cfg1Raw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_step1 rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage isp_end reason=0x01 (apply option bytes)")
    try { isp.txrx(buildIspEnd(1), CMD_ISP_END, 1200) } catch (_: Throwable) {}

    waitForTtlIdentify(
      isp = isp,
      identifyPkt = identifyPkt,
      log = log,
      startMsg = "ttl: re-enter bootloader now (hold BOOT, tap RESET). waiting for ISP...",
      failPrefix = "identify failed (ttl) after isp_end(01)"
    )

    l("INFO", "bootloader detected again (after isp_end(01))")

    repeat(2) {
      val (c2, d2) = isp.txrx(identifyPkt, CMD_IDENTIFY, 1000)
      if (c2 != 0x00 || d2.size < 2) throw RuntimeException("identify failed after re-enter (wchtool)")
      val (c3, cfgX) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
      if (c3 != 0x00 || cfgX.size < 14) throw RuntimeException("read_cfg failed after re-enter (wchtool)")
      cfg12 = cfgX.copyOfRange(2, 14)
      wpr = cfg12.copyOfRange(8, 12)
      uid = if (cfgX.size >= 8) cfgX.copyOfRange(cfgX.size - 8, cfgX.size) else ByteArray(0)
    }

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_reenter rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "wchtool: stage write_cfg step2 (A8)")
    val cfg12b = cfg12.clone()
    cfg12b[0] = 0xFF.toByte(); cfg12b[1] = 0xFF.toByte(); cfg12b[2] = 0x3F.toByte(); cfg12b[3] = 0xC0.toByte()
    cfg12b[4] = 0x00.toByte(); cfg12b[5] = 0x00.toByte(); cfg12b[6] = 0x00.toByte(); cfg12b[7] = 0x00.toByte()
    cfg12b[8] = 0xFF.toByte(); cfg12b[9] = 0xFF.toByte(); cfg12b[10] = 0xFF.toByte(); cfg12b[11] = 0xFF.toByte()

    val (w2Code, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfg12b), CMD_WRITE_CFG, 2000)
    if (w2Code != 0x00) throw RuntimeException("write_cfg (wchtool step2) failed")

    val (cfg2Code, cfg2Raw) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
    if (cfg2Code != 0x00 || cfg2Raw.size < 14) throw RuntimeException("read_cfg after write_cfg (wchtool step2) failed")

    cfg12 = cfg2Raw.copyOfRange(2, 14)
    wpr = cfg12.copyOfRange(8, 12)
    uid = if (cfg2Raw.size >= 8) cfg2Raw.copyOfRange(cfg2Raw.size - 8, cfg2Raw.size) else ByteArray(0)

    if (uid.size == 8) l("INFO", "uid=${uid.joinToString("-") { "%02x".format(it.toInt() and 0xFF) }}")
    l("INFO", "cfg_after_step2 rdpr_user=${cfg12.copyOfRange(0, 4).toHex()} cfg_data=${cfg12.copyOfRange(4, 8).toHex()} cfg_wpr=${wpr.toHex()}")

    l("INFO", "stage isp_key (A3)")
    val seed = ByteArray(0x1E) { 0x00 }
    val (kCode, kResp) = isp.txrx(buildIspKey(seed), CMD_ISP_KEY, 1200)
    if (kCode != 0x00 || kResp.isEmpty()) throw RuntimeException("isp_key failed")
    val bootSum = kResp[0].toInt() and 0xFF

    val uidChk = cfg2Raw[2].toInt() and 0xFF
    val candidates = ArrayList<Pair<String, ByteArray>>(2)
    if (uid.size == 8) candidates.add("uid" to calcXorKeyUid(uid, chipId))
    candidates.add("seed" to calcXorKeySeed(seed, uidChk, chipId))

    val picked = candidates.filter { (_, key) -> sumU8(key) == bootSum }
    if (picked.isEmpty()) {
      var msg = "isp_key checksum mismatch: boot=0x%02x ".format(bootSum)
      for ((name, key) in candidates) msg += "$name=0x%02x ".format(sumU8(key))
      throw RuntimeException(msg.trim())
    }

    val best = picked.sortedBy { if (it.first == "uid") 0 else 1 }.first()
    val xorKey = best.second
    l("INFO", "isp_key ok (src=${best.first} key_sum=0x%02x)".format(bootSum))
    l("INFO", "unlock ok")

    val wprIsFf = wpr.size == 4 && wpr[0] == 0xFF.toByte() && wpr[1] == 0xFF.toByte() && wpr[2] == 0xFF.toByte() && wpr[3] == 0xFF.toByte()
    if (!wprIsFf) {
      l("WARN", "code flash protected (WPR=${wpr.toHex()}) -> clearing WPR + RDPR")
      val cfgUp = cfg12.clone()
      cfgUp[0] = 0xA5.toByte()
      cfgUp[1] = 0x5A.toByte()
      cfgUp[8] = 0xFF.toByte(); cfgUp[9] = 0xFF.toByte(); cfgUp[10] = 0xFF.toByte(); cfgUp[11] = 0xFF.toByte()

      val (ucCode, _) = isp.txrx(buildWriteCfg(CFG_MASK_RDPR_USER_DATA_WPR, cfgUp), CMD_WRITE_CFG, 2000)
      if (ucCode != 0x00) throw RuntimeException("write_cfg (unprotect) failed")

      sleep(80)
      val (rc, cfgU) = isp.txrx(buildReadCfg(BMCU_CFG_MASK), CMD_READ_CFG, 1200)
      if (rc != 0x00 || cfgU.size < 14) throw RuntimeException("read_cfg after unprotect failed")

      val wpr2 = cfgU.copyOfRange(2 + 8, 2 + 12)
      l("INFO", "cfg_wpr(after)=${wpr2.toHex()}")
      val wpr2IsFf = wpr2.size == 4 && wpr2[0] == 0xFF.toByte() && wpr2[1] == 0xFF.toByte() && wpr2[2] == 0xFF.toByte() && wpr2[3] == 0xFF.toByte()
      if (!wpr2IsFf) throw RuntimeException("WPR still not cleared (needs reset/power-cycle to apply option bytes). Re-enter bootloader and retry.")
      l("INFO", "unprotect ok")
    }

    l("INFO", "stage erase sectors=$flashKb (full erase)")
    val (ec, _) = isp.txrx(buildErase(flashKb), CMD_ERASE, 12000)
    if (ec != 0x00) throw RuntimeException("erase failed")
    l("INFO", "erase ok")

    val tailAddr = ((flashBytes - BMCU_CHUNK) / BMCU_CHUNK) * BMCU_CHUNK
    val ffEnc = xorCrypt(ByteArray(BMCU_CHUNK) { 0xFF.toByte() }, xorKey)
    val (vcTail, _) = isp.txrx(buildVerify(tailAddr, 0x00, ffEnc), CMD_VERIFY, 1800)
    if (vcTail != 0x00) throw RuntimeException("erase incomplete (tail not erased) addr=0x%08x".format(tailAddr))

    if (!noFast) {
      l("INFO", "stage set_baud mcu=$fastBaud")
      val (bc, _) = isp.txrx(buildSetBaud(fastBaud), CMD_SET_BAUD, 1200)
      if (bc != 0x00) throw RuntimeException("set_baud failed")
      sleep(30)
      isp.setBaud(fastBaud)
      isp.flush()
      l("INFO", "set_baud ok host_baud=$fastBaud")
    }

    progress(0)
    l("INFO", "stage program addr=0x00000000..0x%08x blocks=$blocks chunk=$BMCU_CHUNK verify=${if (verify) "on" else "off"} every=1 +last".format(blocks * BMCU_CHUNK))

    var lastUi = -1
    var lastLog = -1

    for (i in 0 until blocks) {
      val addr = i * BMCU_CHUNK
      val plain = fwPad.copyOfRange(addr, addr + BMCU_CHUNK)
      val enc = xorCrypt(plain, xorKey)
      val (pc, _) = isp.txrx(buildProgram(addr, 0x00, enc), CMD_PROGRAM, 1800)
      if (pc != 0x00) throw RuntimeException("program failed at 0x%08x".format(addr))

      val stagePct = ((i + 1) * 100) / blocks
      val uiPct = ((i + 1) * 50) / blocks

      if (uiPct != lastUi) {
        lastUi = uiPct
        progress(uiPct)
      }
      if ((stagePct % 10) == 0 && stagePct != lastLog) {
        lastLog = stagePct
        l("INFO", "program $stagePct%% addr=0x%08x".format((i + 1) * BMCU_CHUNK))
      }
    }

    val flushAddr = blocks * BMCU_CHUNK
    l("INFO", "stage program_flush addr=0x%08x (A5 empty)".format(flushAddr))
    val (pfc, _) = isp.txrx(buildProgram(flushAddr, 0x00, ByteArray(0)), CMD_PROGRAM, 2200)
    if (pfc != 0x00) throw RuntimeException("program_flush failed")

    progress(50)
    l("INFO", "program done")

    if (verify) {
      l("INFO", "stage isp_key (A3) before verify")
      val (kc2, k2) = isp.txrx(buildIspKey(seed), CMD_ISP_KEY, 1200)
      if (kc2 != 0x00 || k2.isEmpty() || ((k2[0].toInt() and 0xFF) != bootSum)) throw RuntimeException("isp_key before verify failed")

      l("INFO", "stage verify blocks=$blocks/$blocks")
      lastUi = -1
      lastLog = -1

      for (j in 0 until blocks) {
        val addr = j * BMCU_CHUNK
        val plain = fwPad.copyOfRange(addr, addr + BMCU_CHUNK)
        val enc = xorCrypt(plain, xorKey)
        val (vcc, _) = isp.txrx(buildVerify(addr, 0x00, enc), CMD_VERIFY, 1800)
        if (vcc != 0x00) throw RuntimeException("verify failed at 0x%08x".format(addr))

        val stagePct = ((j + 1) * 100) / blocks
        val uiPct = 50 + ((j + 1) * 50) / blocks

        if (uiPct != lastUi) {
          lastUi = uiPct
          progress(uiPct)
        }
        if ((stagePct % 10) == 0 && stagePct != lastLog) {
          lastLog = stagePct
          l("INFO", "verify $stagePct%% addr=0x%08x".format(addr))
        }
      }

      l("INFO", "verify ok")
    }

    l("INFO", "stage isp_end")
    try { isp.txrx(buildIspEnd(0), CMD_ISP_END, 1200) } catch (_: Throwable) {}

    progress(100)
    l("INFO", "OK")
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
