package app.transittracker.core

import app.transittracker.model.ConfigResponse
import app.transittracker.model.Mode
import app.transittracker.model.PackInfo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.ListSerializer

/** A [DefaultTransitCore] wired to in-memory stores, a fake timetable opener and a [FakeServer]. */
class Harness(private val scope: TestScope, startMs: Long = at(6, 50)) {
    val clock = scope.virtualClock(startMs)
    val server = FakeServer(scope) { clock.nowMillis() }
    val kv = InMemoryKeyValueStore()
    val files = InMemoryFileStore()
    var unmetered = true
    val opened = mutableMapOf<String, InMemoryTimetable>()
    var pollIntervalS = 30
    var wifiOnly = true

    /** Opens a fake pack: the version is taken from the file name `syd-rail-<version>.sqlite`. */
    val opener = TimetableOpener { path ->
        val version = path.substringAfterLast("syd-rail-").removeSuffix(".sqlite")
        TestNet.timetable(version).also { opened[path] = it }
    }

    /** "Compression" is byte reversal, so hashes of compressed and raw bytes differ. */
    val decompressor = Decompressor { it.reversedArray() }

    val core: DefaultTransitCore by lazy {
        TransitCoreFactory.create(
            CoreConfig("https://tt.test", appVersion = "1.2.3", platform = "android", wifiOnlyPackUpdates = wifiOnly),
            CoreDependencies(
                httpClient = server.client,
                kv = kv,
                files = files,
                opener = opener,
                decompressor = decompressor,
                network = NetworkStatus { unmetered },
                clock = clock,
                scope = scope.backgroundScope,
                ioContext = StandardTestDispatcher(scope.testScheduler),
            ),
        )
    }

    fun sqlite(version: String) = "SQLite format 3 / syd-rail $version".encodeToByteArray()
    fun compressed(version: String) = sqlite(version).reversedArray()

    fun packInfo(version: String, sha256: String = Sha256.hex(compressed(version)), sqliteSha: String = Sha256.hex(sqlite(version))) =
        PackInfo(
            name = "syd-rail", version = version, sizeBytes = compressed(version).size.toLong(), sha256 = sha256,
            url = "/v1/packs/syd-rail/$version", modes = listOf(Mode.TRAIN, Mode.METRO), validTo = "20261231",
            sqliteSha256 = sqliteSha,
        )

    /** Serves `/v1/config` offering [packs] and the pack files themselves. */
    fun serveConfig(vararg packs: PackInfo, problems: Map<String, String> = emptyMap()) {
        server.on("GET", "/v1/config") {
            with(server) {
                json(ConfigResponse.serializer(), ConfigResponse(pollIntervalS = pollIntervalS, attribution = "Test attribution", statusMessage = "All good", packs = packs.toList(), timetableProblems = problems))
            }
        }
        server.on("GET", "/v1/packs/syd-rail/*") { req ->
            val version = req.path.substringAfterLast('/')
            respond(compressed(version), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        }
    }

    suspend fun join(token: String = "tok") {
        kv.put(KvKeys.DEVICE_TOKEN, token)
        kv.put(KvKeys.DEVICE_ID, "dev")
    }

    /** Puts pack [version] on "disk" and records it as installed. */
    suspend fun preinstall(version: String) {
        val name = "syd-rail-$version.sqlite"
        files.replace(files.writeTemp(sqlite(version)), name)
        kv.put(
            KvKeys.INSTALLED_PACKS,
            ApiJson.encodeToString(ListSerializer(InstalledPack.serializer()), listOf(InstalledPack("syd-rail", version, name, "20261231", listOf(Mode.TRAIN)))),
        )
    }
}
