package dev.southstar21.droidtosh.transport

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        if (!PinVerifier.matches(leaf.encoded, expectedFingerprint)) {
            throw CertificateException("certificate fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
