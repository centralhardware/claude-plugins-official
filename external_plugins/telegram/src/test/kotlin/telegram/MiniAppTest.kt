package telegram

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniAppTest {
    private val token = "123456:TEST-token"

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** Builds initData the way Telegram signs it. */
    private fun initData(userId: Long, authDate: Long, tamper: Boolean = false): String {
        val fields = mapOf(
            "auth_date" to authDate.toString(),
            "query_id" to "AAH",
            "user" to """{"id":$userId,"first_name":"A"}""",
        )
        val check = fields.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
        val secret = hmac("WebAppData".toByteArray(), token.toByteArray())
        val hash = hmac(secret, check.toByteArray()).joinToString("") { "%02x".format(it) }
        val enc = fields.map { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }.toMutableList()
        if (tamper) enc[0] = "auth_date=${authDate + 1}"
        return (enc + "hash=$hash").joinToString("&")
    }

    companion object {
        init {
            val dir = kotlin.io.path.createTempDirectory().toFile()
            java.io.File(dir, "access.json").writeText("""{"dmPolicy":"allowlist","allowFrom":["42"]}""")
            System.setProperty("telegram.stateDir", dir.path)
        }
    }

    private val app by lazy { MiniApp(token) }

    private val now get() = System.currentTimeMillis() / 1000

    @Test fun acceptsGenuineAllowlisted() = assertEquals("42", app.authorize(initData(42, now)))
    @Test fun rejectsNotAllowlisted() = assertNull(app.authorize(initData(7, now)))
    @Test fun rejectsTampered() = assertNull(app.authorize(initData(42, now, tamper = true)))
    @Test fun rejectsStale() = assertNull(app.authorize(initData(42, now - 2 * 86_400)))
}
