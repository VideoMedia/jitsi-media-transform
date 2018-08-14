package org.jitsi.nlj.transform.module

import io.kotlintest.specs.ShouldSpec
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v1CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder
import org.bouncycastle.crypto.tls.Certificate
import org.bouncycastle.crypto.tls.DTLSClientProtocol
import org.bouncycastle.crypto.tls.DTLSServerProtocol
import org.bouncycastle.crypto.tls.DatagramTransport
import org.bouncycastle.crypto.tls.DefaultTlsServer
import org.bouncycastle.crypto.tls.DefaultTlsSignerCredentials
import org.bouncycastle.crypto.tls.ProtocolVersion
import org.bouncycastle.crypto.tls.SRTPProtectionProfile
import org.bouncycastle.crypto.tls.TlsSRTPUtils
import org.bouncycastle.crypto.tls.TlsSignerCredentials
import org.bouncycastle.crypto.tls.TlsUtils
import org.bouncycastle.crypto.tls.UseSRTPData
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.jitsi.nlj.dtls.TlsClientImpl
import java.lang.Thread.sleep
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread


data class PacketData(val buf: ByteArray, val off: Int, val length: Int)

class FakeTransport : DatagramTransport {
    val incomingQueue = LinkedBlockingQueue<PacketData>()
    var sendFunc: (ByteArray, Int, Int) -> Unit = { _, _, _ -> Unit}
    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
        val pData: PacketData? = incomingQueue.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS)
        pData?.let {
            System.arraycopy(it.buf, it.off, buf, off, Math.min(length, it.length))
        }
        return pData?.length ?: 0
    }

    override fun send(buf: ByteArray, off: Int, length: Int) {
        sendFunc(buf, off, length)
    }

    override fun close() {
    }

    override fun getReceiveLimit(): Int = 1350

    override fun getSendLimit(): Int = 1350
}

fun generateCert(keyPair: KeyPair): Certificate {

    val sigAlgId = DefaultSignatureAlgorithmIdentifierFinder().find("SHA1withRSA")
    val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId)
    val res = PrivateKeyFactory.createKey(keyPair.private.encoded)
    val sigGen2 = BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(res)

    val startDate = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
    val endDate = Date(System.currentTimeMillis() + 365 * 86400000L)
    val v1CertGen: X509v1CertificateBuilder = JcaX509v1CertificateBuilder(
          X500Principal("CN=Test"),
          BigInteger.ONE,
          startDate, endDate,
          X500Principal("CN=Test"),
          keyPair.public);

    val certHolder: X509CertificateHolder = v1CertGen.build(sigGen2);
    return Certificate(arrayOf(certHolder.toASN1Structure()))
}

class TlsServerImpl : DefaultTlsServer() {
    val keyPair: KeyPair
    val certificate: Certificate
    private val mki = TlsUtils.EMPTY_BYTES
    private val srtpProtectionProfiles = intArrayOf(
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
    )
    init {
        val keypairGen = KeyPairGenerator.getInstance("RSA")
        keypairGen.initialize(1024, SecureRandom())
        keyPair = keypairGen.generateKeyPair()
        certificate = generateCert(keyPair)
    }
    override fun getMinimumVersion(): ProtocolVersion = ProtocolVersion.DTLSv10
    override fun getMaximumVersion(): ProtocolVersion = ProtocolVersion.DTLSv10
    override fun getRSASignerCredentials(): TlsSignerCredentials {
        return DefaultTlsSignerCredentials(context, certificate, PrivateKeyFactory.createKey(keyPair.private.encoded))
    }

    override fun getServerExtensions(): Hashtable<*, *> {
        var serverExtensions = super.getServerExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(serverExtensions) == null) {
            if (serverExtensions == null) {
                serverExtensions = Hashtable<Int, ByteArray>()
            }

            TlsSRTPUtils.addUseSRTPExtension(
                serverExtensions,
                UseSRTPData(srtpProtectionProfiles, mki)
            )
        }

        return serverExtensions
    }
}

internal class DtlsStackTest : ShouldSpec() {
    init {
        val serverTransport = FakeTransport()
        val dtlsServer = TlsServerImpl()
        val serverProtocol = DTLSServerProtocol(SecureRandom())

        val clientProtocol = DTLSClientProtocol(SecureRandom());
        val clientTransport = FakeTransport()
        val tlsClient = TlsClientImpl()

        clientTransport.sendFunc = { buf, off, len ->
            println("Client sending message")
            serverTransport.incomingQueue.add(PacketData(buf, off, len))
        }
        serverTransport.sendFunc = { buf, off, len ->
            println("Server sending message")
            clientTransport.incomingQueue.add(PacketData(buf, off, len))
        }

        thread {
            val serverDtlsTransport = serverProtocol.accept(dtlsServer, serverTransport)
            println("Server accept")
            while (true) {
                val buf = ByteArray(1500)
                val len = serverDtlsTransport.receive(buf, 0, 1500, 1000)
                println("Server got dtls data: ${String(buf, 0, len)}")
            }
        }

        println("Client connecting")
        val dtlsTransport = clientProtocol.connect(tlsClient, clientTransport)
        println("Client done connecting")
        val message = "Hello, world"
        dtlsTransport.send(message.toByteArray(), 0, message.length)

        sleep(5000)
        println(tlsClient.getContext().securityParameters.masterSecret)
    }
}

internal class DtlsStack2Test : ShouldSpec() {
    init {
//        val dtlsInputQueue = LinkedBlockingQueue<ByteBuffer>();
//        val dtlsOutputQueue = LinkedBlockingQueue<ByteBuffer>();
//        val transport = QueueDatagramTransport(dtlsInputQueue, dtlsOutputQueue)
//        val dtlsStack = DtlsClientStack(transport);
//
//        dtlsStack.connect()
//
//        Thread.sleep(60000)
//
//        println("local fingperint: ${dtlsStack.localFingerprint}")
    }
}