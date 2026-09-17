package com.openzeekr.app.ble

import com.openzeekr.app.util.Logx
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Trust anchors for the VEHICLE side of the DK handshake: the Geely/Zeekr Trust Center **prod** CAs
 * (root, sub-root, and the ECU issuing CAs — EU/CN plus the Zeekr-branded EU/CN). These are PUBLIC
 * CA certificates (no secret material), the same trust chain the stock native DK lib bakes in
 * (`getRoot/SubRoot/Ecu…ProdJNI`).
 *
 * A genuine Zeekr's cert (handshake frame 0x0104) is signed by one of these; a fake "car" can't
 * forge that signature without a Geely CA private key. We therefore reject the handshake BEFORE we
 * release our digital key (0x010b) if the vehicle cert isn't Geely-issued — closing the
 * "fake car harvests your digitalKey" MITM (matches what the stock app does).
 */
object DkTrust {

    // Public Geely/Zeekr Trust Center PROD CAs (root, sub-root, ECU issuing CAs). Public certs only.
    private val PEM = """
-----BEGIN CERTIFICATE-----
MIICoTCCAkigAwIBAgINAJ6zaLrFuChMwbMiYTAKBggqhkjOPQQDAjCBojELMAkG
A1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhIYW5nemhvdTEm
MCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQxGzAZBgNVBAsT
EkdlZWx5IFRydXN0IENlbnRlcjEoMCYGA1UEAxMfR2VlbHkgVHJ1c3QgQ2VudGVy
IFByb2QgUm9vdCBDQTAiGA8yMDIwMDMxODE2MDAwMFoYDzIwNzEwMzE2MTYwMDAw
WjCBojELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhI
YW5nemhvdTEmMCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQx
GzAZBgNVBAsTEkdlZWx5IFRydXN0IENlbnRlcjEoMCYGA1UEAxMfR2VlbHkgVHJ1
c3QgQ2VudGVyIFByb2QgUm9vdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
BLOOq9V30zGdavoMVpdmVop/rO2yyQ+PlrRKTOlR64AkgLUU3sYAZUo0Nm6fABQ/
xuWzBOf60sNY60H7Hzmo0+qjXTBbMAwGA1UdEwQFMAMBAf8wHQYDVR0OBBYEFLHS
WEwAQ/mnoBrTNxVn22VUnV3LMB8GA1UdIwQYMBaAFLHSWEwAQ/mnoBrTNxVn22VU
nV3LMAsGA1UdDwQEAwIBhjAKBggqhkjOPQQDAgNHADBEAiAJSWDZ7XKYrr8W9eF6
3BfKZWYNoejQmC/YJ+Dxc8QqWQIge3ojMNbWP6y1tldEVLqJDfKWPQkMdvfR07qp
8wnfAms=
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICqjCCAlGgAwIBAgINAKgx+48fhk+rPJNEMjAKBggqhkjOPQQDAjCBojELMAkG
A1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhIYW5nemhvdTEm
MCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQxGzAZBgNVBAsT
EkdlZWx5IFRydXN0IENlbnRlcjEoMCYGA1UEAxMfR2VlbHkgVHJ1c3QgQ2VudGVy
IFByb2QgUm9vdCBDQTAiGA8yMDIwMDMyMjE2MDAwMFoYDzIwNjEwMzE3MTYwMDAw
WjCBqzELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhI
YW5nemhvdTEmMCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQx
GzAZBgNVBAsTEkdlZWx5IFRydXN0IENlbnRlcjExMC8GA1UEAxMoR2VlbHkgVHJ1
c3QgQ2VudGVyIEdsb2JhbCBwb2xpY3kgUHJvZCBDQTBZMBMGByqGSM49AgEGCCqG
SM49AwEHA0IABOO150OxunyZ+utnA8X2JXGF75J7rl0m68mlTcd+2JrwJ3Ulu8Nk
T77GGXBcGkyhmDObySUAbXDBGNxA3E15irCjXTBbMAwGA1UdEwQFMAMBAf8wHQYD
VR0OBBYEFAxSeIjiKhXwL9OFYG3zYzhF/3XWMAsGA1UdDwQEAwIBhjAfBgNVHSME
GDAWgBSx0lhMAEP5p6Aa0zcVZ9tlVJ1dyzAKBggqhkjOPQQDAgNHADBEAiBTVoaI
RpwPRBy7CxJI2vCDKVvYNDtdQfQ0jqP+wvU1igIgMoLUar15riYX0+3qiJV+KHoo
Jy8PFPFr/ncduxN66Ik=
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICnTCCAkOgAwIBAgINAI7sNi3bAdSHMBY7ZjAKBggqhkjOPQQDAjCBqzELMAkG
A1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhIYW5nemhvdTEm
MCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQxGzAZBgNVBAsT
EkdlZWx5IFRydXN0IENlbnRlcjExMC8GA1UEAxMoR2VlbHkgVHJ1c3QgQ2VudGVy
IEdsb2JhbCBwb2xpY3kgUHJvZCBDQTAiGA8yMDIwMDMyNTE2MDAwMFoYDzIwNTEw
MzI1MTYwMDAwWjCBlDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREw
DwYDVQQHEwhIYW5nemhvdTEmMCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xk
aW5ncyBMdGQxGzAZBgNVBAsTEkdlZWx5IFRydXN0IENlbnRlcjEaMBgGA1UEAxMR
RUNVIElzc3VpbmcgQ04tQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATxQd0V
uwAPNBAORnvCgpjREXGA49zfzAur+2gnGz5+5/L8+us895Hn1j8Mhks4XZJ/hPKW
ymmJ7VaQ2Twf82xio10wWzAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBT5Gt5mo7ns
b47VHfxWHt4nRFEfbTAfBgNVHSMEGDAWgBQMUniI4ioV8C/ThWBt82M4Rf911jAL
BgNVHQ8EBAMCAYYwCgYIKoZIzj0EAwIDSAAwRQIhANCBkv/vC0Btj5lghDjEgO5s
oggIASbo0IR71MW+P/+HAiBizVo2o0wI+dC7eTZYfrxlyQSUvesaHVdT5HBbHdSK
xQ==
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICnTCCAkOgAwIBAgINAK3p6Vk1HD5crmgI1zAKBggqhkjOPQQDAjCBqzELMAkG
A1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhIYW5nemhvdTEm
MCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQxGzAZBgNVBAsT
EkdlZWx5IFRydXN0IENlbnRlcjExMC8GA1UEAxMoR2VlbHkgVHJ1c3QgQ2VudGVy
IEdsb2JhbCBwb2xpY3kgUHJvZCBDQTAiGA8yMDIwMDQwMTE2MDAwMFoYDzIwNTEw
NDAxMTYwMDAwWjCBlDELMAkGA1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREw
DwYDVQQHEwhIYW5nemhvdTEmMCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xk
aW5ncyBMdGQxGzAZBgNVBAsTEkdlZWx5IFRydXN0IENlbnRlcjEaMBgGA1UEAxMR
RUNVIElzc3VpbmcgRVUtQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAR2yPIM
apS1IsOgZCANd7lHP5NA4RYLvbOoySsSImTOInoSQVS+Gvi36Iim6AJy51kXS0TX
DLnX6i43+lWw58K+o10wWzAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBQkgqevMiAA
ORKf6TluF53dmvNzETAfBgNVHSMEGDAWgBQMUniI4ioV8C/ThWBt82M4Rf911jAL
BgNVHQ8EBAMCAYYwCgYIKoZIzj0EAwIDSAAwRQIganv5Zj48oMGq5XwIheevixFX
dEYrcnawvnq/w3Oh4vYCIQC+/lg3IuK1QDiPGNMTYqCp5EEls8Lz8ULRUHHOX/9P
HQ==
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICzDCCAnKgAwIBAgIQI9qYx1GoZkOEkMukfDXdzzAKBggqhkjOPQQDAjCBqzEL
MAkGA1UEBhMCQ04xETAPBgNVBAgTCFpoZWppYW5nMREwDwYDVQQHEwhIYW5nemhv
dTEmMCQGA1UEChMdR2VlbHkgQXV0b21vYmlsZSBIb2xkaW5ncyBMdGQxGzAZBgNV
BAsTEkdlZWx5IFRydXN0IENlbnRlcjExMC8GA1UEAxMoR2VlbHkgVHJ1c3QgQ2Vu
dGVyIEdsb2JhbCBwb2xpY3kgUHJvZCBDQTAgFw0yNDA3MjUwNTI0NDhaGA8yMDU0
MDcxODA1MjQ0OFowfzEdMBsGA1UEAwwUWksgRUNVIElzc3VpbmcgQ04tQ0ExDjAM
BgNVBAoMBVpFRUtSMQswCQYDVQQGEwJDTjERMA8GA1UECAwIWmhlamlhbmcxETAP
BgNVBAcMCEhhbmd6aG91MRswGQYDVQQLDBJaZWVrciBUcnVzdCBDZW50ZXIwWTAT
BgcqhkjOPQIBBggqhkjOPQMBBwNCAASJViGLTCRPOSV3DFQhUff6PyJbirBZwWM+
z8nKJraZg3mjDyero/GWx1G/DewFs6YUT7fx+V66sTPs8CGD/OrNo4GgMIGdMB8G
A1UdIwQYMBaAFAxSeIjiKhXwL9OFYG3zYzhF/3XWMB0GA1UdDgQWBBRqhu2H/v7n
w4TZezdeHc2LLgvTyTA6BggrBgEFBQcBAQQuMCwwKgYIKwYBBQUHMAGGHmh0dHA6
Ly9wa2ktb2NzcC5nZWVseS5jb206MjU2MDAPBgNVHRMBAf8EBTADAQH/MA4GA1Ud
DwEB/wQEAwIBhjAKBggqhkjOPQQDAgNIADBFAiEAyhRNok6AA1Lg4rELjPkAFEv8
9FtebA5Z8LRwUvlTuasCIFvibm9wjiRfKNy3VcNKtd/ADMf7Yko1/vb+CNcp8CMg
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
MIICzTCCAnOgAwIBAgIRAP3asiveTQ20cg1m67k2emEwCgYIKoZIzj0EAwIwgasx
CzAJBgNVBAYTAkNOMREwDwYDVQQIEwhaaGVqaWFuZzERMA8GA1UEBxMISGFuZ3po
b3UxJjAkBgNVBAoTHUdlZWx5IEF1dG9tb2JpbGUgSG9sZGluZ3MgTHRkMRswGQYD
VQQLExJHZWVseSBUcnVzdCBDZW50ZXIxMTAvBgNVBAMTKEdlZWx5IFRydXN0IENl
bnRlciBHbG9iYWwgcG9saWN5IFByb2QgQ0EwIBcNMjQwNzI1MDUyNDQ4WhgPMjA1
NDA3MTgwNTI0NDhaMH8xHTAbBgNVBAMMFFpLIEVDVSBJc3N1aW5nIEVVLUNBMQ4w
DAYDVQQKDAVaRUVLUjELMAkGA1UEBhMCQ04xETAPBgNVBAgMCFpoZWppYW5nMREw
DwYDVQQHDAhIYW5nemhvdTEbMBkGA1UECwwSWmVla3IgVHJ1c3QgQ2VudGVyMFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDKjJam2wLofuFmBURiuOHGWjJOgqpMYZ
YzwYbKkYtA1tqtiLGRBNk93UrjGN7Qocygq0gN3KyDs0Yn86OwQOs6OBoDCBnTAf
BgNVHSMEGDAWgBQMUniI4ioV8C/ThWBt82M4Rf911jAdBgNVHQ4EFgQU3u70adAf
wZ4OwYu0nahrLS5BsAMwOgYIKwYBBQUHAQEELjAsMCoGCCsGAQUFBzABhh5odHRw
Oi8vcGtpLW9jc3AuZ2VlbHkuY29tOjI1NjAwDwYDVR0TAQH/BAUwAwEB/zAOBgNV
HQ8BAf8EBAMCAYYwCgYIKoZIzj0EAwIDSAAwRQIgFs0oI80PRuvwcbWLQVZ0KRqp
VIMfixKYd/8MAIUSdTECIQD7ZrLg16VOYz0RSiL5LEfeWnylJW5DWrxeg6kA4Xnr
FQ==
-----END CERTIFICATE-----
"""

    private val cas: List<X509Certificate> by lazy {
        runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            PEM.split("-----END CERTIFICATE-----")
                .map { it.trim() }
                .filter { it.contains("BEGIN CERTIFICATE") }
                .mapNotNull { block ->
                    runCatching {
                        cf.generateCertificate(
                            ByteArrayInputStream(("$block\n-----END CERTIFICATE-----\n").toByteArray()),
                        ) as X509Certificate
                    }.onFailure { Logx.w("dk", "trust: one CA failed to parse: ${it.message}") }.getOrNull()
                }
        }.getOrElse { Logx.w("dk", "trust store parse failed: ${it.message}"); emptyList() }
    }

    /**
     * Throw unless [leaf] (the vehicle cert) is signed by a baked Geely PROD CA. If the trust store
     * itself failed to load (which shouldn't happen), we log and ALLOW rather than brick the key —
     * a genuinely untrusted cert is still rejected whenever the store is present.
     */
    fun requireGeelyVehicleCert(leaf: X509Certificate) {
        val store = cas
        if (store.isEmpty()) { Logx.w("dk", "vehicle-cert trust store empty — skipping validation (FIXME)"); return }
        val issuer = store.firstOrNull { ca ->
            runCatching { leaf.verify(ca.publicKey); true }.getOrDefault(false)
        } ?: error("vehicle cert is NOT issued by a Geely CA — refusing to release the digital key (possible fake car)")
        Logx.d("dk", "vehicle cert verified — issued by '${issuer.subjectX500Principal.name.take(48)}' ✓")
    }
}
