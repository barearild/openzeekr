package com.openzeekr.app.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.openzeekr.app.util.Logx

/**
 * Helper to write an NDEF message (deep link + Android Application Record) to a standard
 * NFC tag (e.g. Samsung TecTile, NTAG213/215/216) so tapping the tag launches [com.openzeekr.app.NfcActionActivity].
 */
object NfcTagWriter {

    const val URI_TOGGLE = "openzeekr://toggle"
    const val URI_UNLOCK = "openzeekr://unlock"
    const val URI_LOCK = "openzeekr://lock"

    fun startListening(
        activity: Activity,
        uriString: String = URI_TOGGLE,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: run {
            onError("NFC is not supported on this device")
            return
        }
        if (!adapter.isEnabled) {
            onError("NFC is disabled. Please enable NFC in Android Settings.")
            return
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_F

        adapter.enableReaderMode(activity, { tag ->
            val err = writeTag(tag, uriString)
            activity.runOnUiThread {
                stopListening(activity)
                if (err == null) onSuccess()
                else onError(err)
            }
        }, flags, null)
    }

    fun stopListening(activity: Activity) {
        runCatching {
            Logx.d("nfc", "disabling NFC reader mode on activity")
            NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        }
    }

    private fun writeTag(tag: Tag, uriString: String): String? {
        val uriRecord = NdefRecord.createUri(uriString)
        val aarRecord = NdefRecord.createApplicationRecord("com.openzeekr.app")
        val message = NdefMessage(arrayOf(uriRecord, aarRecord))
        val size = message.toByteArray().size

        // Case 1: Tag is already NDEF formatted
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) {
                    "Tag is read-only / locked."
                } else if (ndef.maxSize < size) {
                    "Tag memory too small (${ndef.maxSize}B < ${size}B)."
                } else {
                    ndef.writeNdefMessage(message)
                    Logx.d("nfc", "NDEF message written successfully ($size bytes)")
                    null
                }
            } catch (e: Exception) {
                Logx.e("nfc", "Failed to write NDEF: ${e.message}")
                "Write failed: ${e.message}"
            } finally {
                runCatching { ndef.close() }
            }
        }

        // Case 2: Tag is unformatted but NDEF formatable
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                formatable.format(message)
                Logx.d("nfc", "Tag formatted and NDEF message written ($size bytes)")
                null
            } catch (e: Exception) {
                Logx.e("nfc", "Failed to format tag: ${e.message}")
                "Format failed: ${e.message}"
            } finally {
                runCatching { formatable.close() }
            }
        }

        return "Tag does not support NDEF."
    }
}
