package com.v2ray.ang.smartpool.crash

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AgeEncryption {
    const val DEFAULT_PUBLIC_KEY = "age15vxh8da66xpkufplm9xp63pfuewzesyald6ar68h7spmyeqfqaqqw8m3fk"
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    fun encryptToArmor(data: ByteArray, recipientKey: String = DEFAULT_PUBLIC_KEY): String {
        val recipientRaw = decodeBech32(recipientKey)
        val ephemeralPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        clampScalar(ephemeralPriv)
        val ephemeralPub = curve25519ScalarMult(ephemeralPriv, basePoint())
        val sharedSecret = curve25519ScalarMult(ephemeralPriv, recipientRaw)

        val salt = ByteArray(64)
        System.arraycopy(ephemeralPub, 0, salt, 0, 32)
        System.arraycopy(recipientRaw, 0, salt, 32, 32)
        val wrapKey = hkdf(sharedSecret, salt, "age-encryption.org/v1/X25519".toByteArray(Charsets.UTF_8), 32)

        val fileKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wrappedKey = chacha20Poly1305Encrypt(wrapKey, ByteArray(12), fileKey)

        val b64NoPad = Base64.getEncoder().withoutPadding()
        val epPubB64 = b64NoPad.encodeToString(ephemeralPub)
        val wrappedKeyB64 = b64NoPad.encodeToString(wrappedKey)

        // В RFC age спецификации: данные для HMAC заканчиваются строго на '---' без пробела
        val headerForMac = "age-encryption.org/v1\n-> X25519 $epPubB64\n$wrappedKeyB64\n---"
        val macKey = hkdf(fileKey, ByteArray(32), "header".toByteArray(Charsets.UTF_8), 32)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(macKey, "HmacSHA256"))
        }.doFinal(headerForMac.toByteArray(Charsets.UTF_8))
        val headerMacB64 = b64NoPad.encodeToString(mac)

        // В файл пишется с пробелом перед MAC
        val fullHeader = "$headerForMac $headerMacB64\n"

        val payloadNonce16 = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payloadKey = hkdf(fileKey, payloadNonce16, "payload".toByteArray(Charsets.UTF_8), 32)

        val chunkNonce = ByteArray(12).apply { this[11] = 1.toByte() }
        val chunkCiphertext = chacha20Poly1305Encrypt(payloadKey, chunkNonce, data)

        val fullBinaryStream = fullHeader.toByteArray(Charsets.UTF_8) + payloadNonce16 + chunkCiphertext
        val armorB64 = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.UTF_8)).encodeToString(fullBinaryStream)

        Arrays.fill(ephemeralPriv, 0.toByte())
        Arrays.fill(sharedSecret, 0.toByte())
        Arrays.fill(wrapKey, 0.toByte())
        Arrays.fill(fileKey, 0.toByte())
        Arrays.fill(macKey, 0.toByte())
        Arrays.fill(payloadKey, 0.toByte())

        return buildString {
            append("-----BEGIN AGE ENCRYPTED FILE-----\n")
            append(armorB64.trimEnd()).append("\n")
            append("-----END AGE ENCRYPTED FILE-----\n")
        }
    }

    private fun chacha20Poly1305Encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20-Poly1305"), IvParameterSpec(iv))
            cipher.doFinal(plaintext)
        } catch (_: Exception) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
            cipher.doFinal(plaintext)
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(len)
    }

    private fun clampScalar(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
    }

    private fun basePoint(): ByteArray = ByteArray(32).apply { this[0] = 9 }

    private fun curve25519ScalarMult(scalar: ByteArray, uCoords: ByteArray): ByteArray {
        var x1 = decodeBigInt(uCoords)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val byteIdx = t / 8
            val bitIdx = t % 8
            val kt = (scalar[byteIdx].toInt() shr bitIdx) and 1
            swap = swap xor kt
            if (swap == 1) {
                var dummy = x2; x2 = x3; x3 = dummy
                dummy = z2; z2 = z3; z3 = dummy
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            val dummy = x2; x2 = x3; x3 = dummy
            val dummyZ = z2; z2 = z3; z3 = dummyZ
        }
        val result = x2.multiply(z2.modInverse(P)).mod(P)
        return encodeBigInt(result)
    }

    private fun decodeBigInt(b: ByteArray): BigInteger {
        val rev = b.reversedArray()
        return BigInteger(1, rev)
    }

    private fun encodeBigInt(n: BigInteger): ByteArray {
        val out = ByteArray(32)
        val b = n.toByteArray()
        val rev = b.reversedArray()
        val copyLen = minOf(32, rev.size)
        System.arraycopy(rev, 0, out, 0, copyLen)
        return out
    }

    private fun decodeBech32(bech: String): ByteArray {
        val sep = bech.lastIndexOf('1')
        if (sep == -1) error("Invalid bech32")
        val data = bech.substring(sep + 1)
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val values = ByteArray(data.length - 6)
        for (i in 0 until (data.length - 6)) {
            val idx = charset.indexOf(data[i])
            if (idx == -1) error("Bad charset")
            values[i] = idx.toByte()
        }
        return convertBits(values, 5, 8, false)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val v = b.toInt() and 0xff
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc shr bits) and maxv).toByte())
            }
        }
        return out.toByteArray()
    }
}
