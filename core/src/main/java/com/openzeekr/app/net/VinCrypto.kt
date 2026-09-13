package com.openzeekr.app.net

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X-VIN header value = Base64(AES-128-CBC(VIN, vin_key, vin_iv)).
 *
 * The key and IV are the 16-character ASCII strings themselves (UTF-8 bytes),
 * not hex-decoded — matching the app's SecretKeySpec usage (see dk-secrets-model).
 * Returns the plain VIN unchanged if key/iv aren't configured.
 */
object VinCrypto {
    fun encryptVin(vin: String, key: String, iv: String): String {
        if (vin.isBlank() || key.isBlank() || iv.isBlank()) return vin
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return Base64.encodeToString(cipher.doFinal(vin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
