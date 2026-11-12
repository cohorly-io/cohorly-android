package com.cohorly.android.core.crash

import com.cohorly.android.core.CohorlyClock
import com.cohorly.android.core.CohorlyHttpException
import com.cohorly.android.core.CohorlyTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections

const val TEST_PACKAGE = "com.cohorly.spike.crash"
const val TEST_TOKEN = "tok_test"
const val TEST_HOST = "https://ingest.example.com"
const val TEST_DEVICE_ID = "b6247659-69a5-46fd-ad0d-a667283607b6"

class MemoryCrashStore : CrashKeyValueStore {
    val values = HashMap<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    fun putConfig(config: CrashConfig) = putString(CrashConfig.KEY_CONFIG, config.toJson())
}

/** Records every POST; [responder] decides the outcome (throw to fail). */
class RecordingCrashTransport(var responder: (body: String) -> Unit = {}) : CohorlyTransport {
    data class Post(val url: String, val body: String, val headers: Map<String, String>)

    val posts = Collections.synchronizedList(mutableListOf<Post>())

    override fun post(url: String, body: String) = post(url, body, emptyMap())

    override fun post(url: String, body: String, headers: Map<String, String>) {
        posts.add(Post(url, body, headers))
        responder(body)
    }

    fun events(): List<JSONObject> = posts.map { JSONObject(it.body) }

    companion object {
        fun status(code: Int) = RecordingCrashTransport { if (code !in 200..299) throw CohorlyHttpException(code) }

        fun offline() = RecordingCrashTransport { throw IOException("offline") }
    }
}

class FakeClock(var nowMs: Long = 1_794_408_031_706L) : CohorlyClock {
    override fun nowMillis(): Long = nowMs
}

class FakeSlot(var current: Thread.UncaughtExceptionHandler? = null) : UncaughtHandlerSlot {
    var sets = 0

    override fun get(): Thread.UncaughtExceptionHandler? = current

    override fun set(handler: Thread.UncaughtExceptionHandler) {
        sets++
        current = handler
    }
}

class RecordingHandler : Thread.UncaughtExceptionHandler {
    val calls = mutableListOf<Throwable>()

    override fun uncaughtException(t: Thread, e: Throwable) {
        calls.add(e)
    }
}

val TEST_APP = CrashAppInfo(
    packageName = TEST_PACKAGE,
    versionName = "0.1.0",
    versionCode = 7,
    osVersion = "16",
    model = "sdk_gphone64_arm64",
    manufacturer = "Google",
    libVersion = "0.3.0",
)

fun enabledConfig(
    distinctId: String? = "spike-user-42",
    deviceId: String? = TEST_DEVICE_ID,
    release: String? = "com.cohorly.spike.crash@0.1.0+7",
    dist: String? = "5f3c2a10-8e4b-4c7d-9a61-0b2e7d4f9c13",
) = CrashConfig(
    enabled = true,
    release = release,
    dist = dist,
    distinctId = distinctId,
    deviceId = deviceId,
    token = TEST_TOKEN,
    apiHost = TEST_HOST,
)

fun tempDir(): File = Files.createTempDirectory("cohorly-crash").toFile().apply { deleteOnExit() }

fun pendingFiles(dir: File): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }?.toList().orEmpty()

fun resourceBytes(name: String): ByteArray =
    requireNotNull(CrashTestSupport::class.java.getResourceAsStream("/crash/$name")) { "missing $name" }
        .use { it.readBytes() }

fun fixture(name: String): JSONObject = JSONObject(String(resourceBytes(name), Charsets.UTF_8))

fun JSONObject.props(): JSONObject = getJSONObject("properties")

fun JSONObject.firstException(): JSONObject = props().getJSONArray("\$exception_list").getJSONObject(0)

fun JSONObject.frames(): JSONArray = getJSONObject("stacktrace").getJSONArray("frames")

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

object CrashTestSupport {
    /** Collects background jobs so a test decides when "the background" runs. */
    class Background {
        val jobs = mutableListOf<Runnable>()

        fun submit(job: Runnable) {
            jobs.add(job)
        }

        fun runAll() {
            while (jobs.isNotEmpty()) jobs.removeAt(0).run()
        }
    }

    /** A tiny protobuf writer, enough to build tombstones for tests. */
    class Proto {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) = apply {
            raw((field.toLong() shl 3) or 0)
            raw(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun message(field: Int, message: Proto) = bytes(field, message.toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun bytes(field: Int, value: ByteArray) = apply {
            raw((field.toLong() shl 3) or 2)
            raw(value.size.toLong())
            out.write(value)
        }

        private fun raw(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) {
                    out.write(x.toInt())
                    return
                }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
    }
}

fun newReporter(
    store: CrashKeyValueStore,
    dir: File,
    transport: CohorlyTransport,
    slot: UncaughtHandlerSlot = FakeSlot(),
    background: CrashTestSupport.Background? = null,
    clock: CohorlyClock = FakeClock(),
    threadDump: () -> Map<Thread, Array<StackTraceElement>> = { emptyMap() },
    terminate: () -> Unit = { error("terminate called") },
    metaToken: String? = null,
    metaApiHost: String? = null,
    handlerSendTimeoutMs: Long = CrashReporter.HANDLER_SEND_TIMEOUT_MS,
) = CrashReporter(
    store = store,
    pending = PendingReportStore(dir),
    transport = transport,
    app = TEST_APP,
    metaToken = metaToken,
    metaApiHost = metaApiHost,
    clock = clock,
    slot = slot,
    background = { job -> if (background != null) background.submit(job) else job.run() },
    terminate = terminate,
    threadDump = threadDump,
    handlerSendTimeoutMs = handlerSendTimeoutMs,
)
