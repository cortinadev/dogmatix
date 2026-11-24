package com.cortinadev.dogmatix.util.rvz

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wii partition sectors: 0x400 bytes of hashes (AES-128-CBC, IV 0) followed by 0x7C00 bytes of
 * data (AES-128-CBC, IV = encrypted hash block bytes 0x3D0..0x3E0). Hash block layout:
 * H0 31×20 at 0x000, padding to 0x280, H1 8×20 at 0x280, padding to 0x340, H2 8×20 at 0x340,
 * padding to 0x400 (see wiibrew.org «Wii disc»).
 *
 * One instance per thread: it keeps its own `Cipher` and `MessageDigest`.
 */
class WiiCrypto(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val sha1: MessageDigest = MessageDigest.getInstance("SHA-1")
    private val ivBuf = ByteArray(16)

    /** Decrypts one 32 KiB sector: hash block to [hashOut]+[hashOff], data to [dataOut]+[dataOff]. */
    fun decryptSector(src: ByteArray, srcOff: Int, hashOut: ByteArray?, hashOff: Int, dataOut: ByteArray, dataOff: Int) {
        if (hashOut != null) {
            ivBuf.fill(0)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
            cipher.doFinal(src, srcOff, HASH_SIZE, hashOut, hashOff)
        }
        System.arraycopy(src, srcOff + 0x3D0, ivBuf, 0, 16)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
        cipher.doFinal(src, srcOff + HASH_SIZE, DATA_SIZE, dataOut, dataOff)
    }

    /** Encrypts a plain sector (hash block + data) from [src] into [out] (may be the same array). */
    fun encryptSector(src: ByteArray, srcOff: Int, out: ByteArray, outOff: Int) {
        ivBuf.fill(0)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
        val data = src.copyOfRange(srcOff + HASH_SIZE, srcOff + SECTOR)
        cipher.doFinal(src, srcOff, HASH_SIZE, out, outOff)
        System.arraycopy(out, outOff + 0x3D0, ivBuf, 0, 16)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
        cipher.doFinal(data, 0, DATA_SIZE, out, outOff + HASH_SIZE)
    }

    private fun digest(src: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int) {
        sha1.update(src, off, len)
        sha1.digest(out, outOff, 20)
    }

    /**
     * Hash blocks for a 2 MiB hash group: [data] holds `sectors` × 0x7C00 decrypted bytes (missing
     * sectors up to 64 count as zeros, as Dolphin's reader assumes). Writes 64 × 0x400 hash
     * blocks with zero padding to [out].
     */
    fun hashGroup(data: ByteArray, sectors: Int, out: ByteArray) {
        out.fill(0)
        val zero = ByteArray(0x400)
        val h0 = ByteArray(GROUP_SECTORS * H0_TABLE)
        for (s in 0 until GROUP_SECTORS) {
            for (i in 0 until 31) {
                if (s < sectors) digest(data, s * DATA_SIZE + i * 0x400, 0x400, h0, s * H0_TABLE + i * 20)
                else digest(zero, 0, 0x400, h0, s * H0_TABLE + i * 20)
            }
        }
        val h1 = ByteArray(GROUP_SECTORS * 20)
        for (s in 0 until GROUP_SECTORS) digest(h0, s * H0_TABLE, H0_TABLE, h1, s * 20)
        val h2 = ByteArray(8 * 20)
        for (g in 0 until 8) digest(h1, g * 160, 160, h2, g * 20)
        for (s in 0 until GROUP_SECTORS) {
            val o = s * HASH_SIZE
            System.arraycopy(h0, s * H0_TABLE, out, o, H0_TABLE)
            System.arraycopy(h1, (s / 8) * 160, out, o + 0x280, 160)
            System.arraycopy(h2, 0, out, o + 0x340, 160)
        }
    }

    companion object {
        const val SECTOR = 0x8000
        const val HASH_SIZE = 0x400
        const val DATA_SIZE = 0x7C00
        const val GROUP_SECTORS = 64
        const val H0_TABLE = 31 * 20

        /**
         * Byte ranges of a hash block that an exception covers: (offset, compared length). The
         * 32-byte padding areas get two overlapping 20-byte exceptions, as Dolphin writes them.
         */
        val EXCEPTION_SLOTS: List<Int> = buildList {
            for (i in 0 until 31) add(i * 20)
            add(0x26C)
            for (i in 0 until 8) add(0x280 + i * 20)
            add(0x320); add(0x32C)
            for (i in 0 until 8) add(0x340 + i * 20)
            add(0x3E0); add(0x3EC)
        }

        /** Retail common keys: index 0 (normal), 1 (Korean), 2 (vWii). Every Wii disc tool ships them. */
        private val COMMON_KEYS = arrayOf(
            "ebe42a225e8593e448d9c5457381aaf7",
            "63b82bb4f4614e2e13f2fefbba4c9b7e",
            "30bfc76e7c19afbb23163330ced7c28d",
        )

        /** Decrypts a ticket's title key ([ticket] = the first 0x2A4 bytes of the partition). */
        fun titleKey(ticket: ByteArray): ByteArray? {
            val index = ticket[0x1F1].toInt() and 0xFF
            if (index >= COMMON_KEYS.size) return null
            val common = hex(COMMON_KEYS[index])
            val iv = ByteArray(16)
            System.arraycopy(ticket, 0x1DC, iv, 0, 8)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(common, "AES"), IvParameterSpec(iv))
            return c.doFinal(ticket, 0x1BF, 16)
        }

        /** Inverse of [titleKey], used by tests to build discs. */
        fun encryptTitleKey(titleKey: ByteArray, titleId: ByteArray, commonIndex: Int = 0): ByteArray {
            val iv = ByteArray(16)
            System.arraycopy(titleId, 0, iv, 0, 8)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(COMMON_KEYS[commonIndex]), "AES"), IvParameterSpec(iv))
            return c.doFinal(titleKey)
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
