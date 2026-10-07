package com.aktcl.aron.core.geo.integrity

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fake AndroidKeyStore so AndroidDeviceKeyStore.create runs on the JVM. */
object FakeKs {
    val keys = mutableMapOf<String, KeyPair>()
    var failStrongBox = false
    var failAll = false
}

class FakeCert(private val pub: PublicKey) : Certificate("X.509") {
    override fun getEncoded() = byteArrayOf(1, 2, 3)
    override fun verify(key: PublicKey?) {}
    override fun verify(key: PublicKey?, sigProvider: String?) {}
    override fun toString() = "fake"
    override fun getPublicKey() = pub
}

class FakeKsSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = FakeKs.keys[alias]?.private
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = FakeKs.keys[alias]?.let { arrayOf(FakeCert(it.public)) }
    override fun engineGetCertificate(alias: String): Certificate? = FakeKs.keys[alias]?.let { FakeCert(it.public) }
    override fun engineGetCreationDate(alias: String) = Date()
    override fun engineSetKeyEntry(a: String, k: Key, p: CharArray?, c: Array<out Certificate>?) {}
    override fun engineSetKeyEntry(a: String, k: ByteArray, c: Array<out Certificate>?) {}
    override fun engineSetCertificateEntry(a: String, c: Certificate) {}
    override fun engineDeleteEntry(alias: String) { FakeKs.keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(FakeKs.keys.keys.toList())
    override fun engineContainsAlias(alias: String) = alias in FakeKs.keys
    override fun engineSize() = FakeKs.keys.size
    override fun engineIsKeyEntry(alias: String) = alias in FakeKs.keys
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
}

class FakeKpgSpi : KeyPairGeneratorSpi() {
    private var spec: KeyGenParameterSpec? = null
    override fun initialize(keysize: Int, random: SecureRandom?) {}
    override fun initialize(params: AlgorithmParameterSpec, random: SecureRandom?) { spec = params as KeyGenParameterSpec }
    override fun generateKeyPair(): KeyPair {
        val s = spec!!
        if (FakeKs.failAll) throw ProviderException("Keystore key generation failed")
        if (s.isStrongBoxBacked && FakeKs.failStrongBox) throw ProviderException("Failed to generate key pair") // not StrongBoxUnavailableException
        val kp = (Security.getProviders().firstNotNullOfOrNull { p -> runCatching { KeyPairGenerator.getInstance("EC", p).also { it.initialize(ECGenParameterSpec("secp256r1")); it.generateKeyPair() } }.getOrNull() } ?: error(Security.getProviders().joinToString { it.name })).apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        FakeKs.keys[s.keystoreAlias] = kp
        return kp
    }
}

class FakeAndroidKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "fake") {
    init {
        putService(object : Service(this, "KeyStore", "AndroidKeyStore", FakeKsSpi::class.java.name, null, null) {
            override fun newInstance(p: Any?): Any = FakeKsSpi()
        })
        putService(object : Service(this, "KeyPairGenerator", "EC", FakeKpgSpi::class.java.name, null, null) {
            override fun newInstance(p: Any?): Any = FakeKpgSpi()
        })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CheckerRefuteKeyStoreTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val alias = "aron-device-key-v1"

    @Before fun setUp() {
        Security.removeProvider("AndroidKeyStore"); Security.addProvider(FakeAndroidKeyStoreProvider())
        FakeKs.keys.clear(); FakeKs.failAll = false; FakeKs.failStrongBox = false
    }
    @After fun tearDown() { Security.removeProvider("AndroidKeyStore") }

    @Test fun strongBoxProviderExceptionFallsBackToTeeInsteadOfFailingEnrolment() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE, true)
        FakeKs.failStrongBox = true
        val store = AndroidDeviceKeyStore(app)
        val key = runCatching { store.create(alias, ByteArray(32)) }
        assertTrue("create threw ${key.exceptionOrNull()}", key.isSuccess)
        assertEquals(false, key.getOrNull()!!.strongBox)
    }

    @Test fun aFailedReEnrolmentDoesNotWipeTheKeyInUse() {
        val store = AndroidDeviceKeyStore(app)
        // The enrolled key the server already holds (seeded directly: SunEC is unusable inside the Robolectric sandbox).
        FakeKs.keys[alias] = KeyPair(object : PublicKey { override fun getAlgorithm() = "EC"; override fun getFormat() = null; override fun getEncoded() = null },
            object : PrivateKey { override fun getAlgorithm() = "EC"; override fun getFormat() = null; override fun getEncoded() = null })
        assertTrue(store.exists(alias))
        FakeKs.failAll = true
        runCatching { store.create(alias, ByteArray(32)) }
        assertTrue("the enrolled key was deleted before its replacement existed", store.exists(alias))
    }
}

class CheckerRefutePureTest {
    private val pkg = "com.aktcl.aron.sr"
    private class CleanProbe(override val dataDir: String) : RootProbe {
        override fun exists(path: String) = false
        override val buildTags: String? = "release-keys"
        override fun systemProperty(name: String): String? = null
        override fun installed(packageName: String) = false
        override fun mounts() = ""
        override val userId: Int = 0
    }

    @Test fun appOnAdoptedSdStorageIsNotAForeignDataDir() {
        // ApplicationInfo.dataDir for an app moved to adoptable storage, main user 0.
        assertEquals(emptyList<String>(), RootHints.evaluate(CleanProbe("/mnt/expand/1b2c3d4e-0000-4a4a-8b8b-0123456789ab/user/0/$pkg"), pkg))
    }

    @Test fun collectNeverThrowsEvenIfTheTokenSourceDoes() = runTest {
        val bad = object : IntegrityTokenSource { override suspend fun token(requestHash: String): IntegrityResult = throw IllegalStateException("binder died") }
        val r = runCatching { IntegrityEvidenceService({ "n".repeat(43) }, bad, { "dev" }).collect() }
        assertTrue("collect threw ${r.exceptionOrNull()}", r.isSuccess)
    }

    @Test fun truncatedDerIsAnIllegalArgumentNotAnIndexError() {
        for (der in listOf(byteArrayOf(), byteArrayOf(0x30), byteArrayOf(0x30, 0))) {
            val e = runCatching { IntegrityCodec.derToRawB64url(der) }.exceptionOrNull()
            assertTrue("got $e for ${der.toList()}", e is IllegalArgumentException)
        }
    }

    @Test fun trackerWriteFailureDoesNotEscapeIntoTheBatch() {
        val notADir = Files.createTempFile("sig", ".x").toFile() // e.g. storage full / unwritable
        val t = IntegritySignalsTracker(File(notADir, "sub"))
        val r = runCatching { t.changed(IntegritySignals(false, false, true, true, emptyList(), emptyList())) }
        assertTrue("changed() threw ${r.exceptionOrNull()}", r.isSuccess)
    }
}
