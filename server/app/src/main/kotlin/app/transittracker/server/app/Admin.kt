package app.transittracker.server.app

import app.transittracker.model.WallClock
import app.transittracker.model.WatchKind
import app.transittracker.server.pipeline.PackBuildException
import app.transittracker.server.pipeline.PackStore
import app.transittracker.server.pipeline.PipelineOptions
import app.transittracker.server.pipeline.PipelineTimetableRepository
import app.transittracker.server.spi.ServerConfig
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """Usage: tt-admin <command> [options]

Commands:
  invite [--expires-days N] [--note TEXT]   Create a one-time invite code
  revoke <deviceId>                         Cut a device off (deletes its token, push token and watches)
  devices                                   List devices
  invites                                   List invites (codes themselves are never stored)
  watches                                   List all watches
  notifications [--limit N]                 Recent push notifications (default 50)
  prune                                     Delete devices unseen for 90 days, old logs and invites
  backup <file>                             Write a consistent copy of the database to <file>
  import-gtfs <pack> <zip-or-dir...>        Build and publish a timetable pack from local GTFS zips or
                                            directories of GTFS .txt files (e.g. syd-rail)

Reads TT_DATA_DIR like tt-server; safe to run while the server is running (a running server
loads a pack imported this way at its next start)."""

/** `tt-admin` entry point. Operates directly on the SQLite database. */
fun main(args: Array<String>) {
    exitProcess(runAdmin(args.toList(), ServerConfig.fromEnv(), System.out, System.err))
}

/** Runs one admin command; returns the process exit code. */
fun runAdmin(
    args: List<String>,
    config: ServerConfig,
    out: PrintStream,
    err: PrintStream,
    clock: WallClock = SystemClock,
): Int {
    val command = args.firstOrNull()
    if (command == null || command in setOf("-h", "--help", "help")) {
        out.println(USAGE)
        return if (command == null) 2 else 0
    }
    val rest = args.drop(1)
    return try {
        if (command == "import-gtfs") return importGtfs(rest, config, out, err, clock)
        Database.open(config.dbPath).use { db -> runDbCommand(command, rest, db, out, clock) }
    } catch (e: UsageException) {
        err.println("tt-admin: ${e.message}")
        err.println()
        err.println(USAGE)
        2
    }
}

private class UsageException(message: String) : Exception(message)

private fun runDbCommand(command: String, args: List<String>, db: Database, out: PrintStream, clock: WallClock): Int {
    val devices = SqliteDeviceStore(db, clock)
    when (command) {
        "invite" -> {
            val opts = parseOptions(args, setOf("--expires-days", "--note"))
            val days = opts["--expires-days"]?.let {
                it.toIntOrNull()?.takeIf { d -> d > 0 } ?: throw UsageException("--expires-days must be a positive number")
            }
            val invite = devices.createInvite(days, opts["--note"])
            out.println("Invite code: ${invite.code}")
            out.println("Expires:     ${invite.expiresMs?.let(::formatTime) ?: "never"}")
            invite.note?.let { out.println("Note:        $it") }
            out.println("One-time use. Share it privately; it is not shown again.")
        }
        "revoke" -> {
            val id = args.singleOrNull() ?: throw UsageException("revoke needs exactly one device id")
            if (!devices.revoke(id)) {
                out.println("No device $id")
                return 1
            }
            out.println("Revoked $id: token, push token and watches deleted")
        }
        "devices" -> {
            val all = devices.all()
            out.println(row("DEVICE ID", "PLATFORM", "APP", "PUSH", "JOINED", "LAST SEEN", "STATE"))
            all.forEach {
                out.println(
                    row(
                        it.deviceId, it.platform, it.appVersion.ifBlank { "-" },
                        if (it.pushToken != null) (if (it.notificationsEnabled) "yes" else "muted") else "no",
                        formatTime(it.createdMs), formatTime(it.lastSeenMs), if (it.revoked) "revoked" else "active",
                    ),
                )
            }
            out.println("${all.count { !it.revoked }} active, ${all.count { it.revoked }} revoked")
        }
        "invites" -> {
            out.println(row("CREATED", "EXPIRES", "USED", "BY DEVICE", "NOTE"))
            devices.invites().forEach {
                out.println(
                    row(
                        formatTime(it.createdMs), it.expiresMs?.let(::formatTime) ?: "never",
                        formatTime(it.usedMs), it.usedByDevice ?: "-", it.note ?: "",
                    ),
                )
            }
        }
        "watches" -> {
            val all = SqliteWatchRepository(db, clock).all()
            out.println(row("DEVICE", "WATCH ID", "KIND", "LABEL", "FROM", "TO", "WHEN"))
            all.forEach { (deviceId, w) ->
                val schedule = when (w.kind) {
                    WatchKind.COMMUTE -> "days ${w.daysOfWeek.joinToString("")} ${secs(w.windowStartSecs)}-${secs(w.windowEndSecs)}"
                    WatchKind.SINGLE_SERVICE -> "${w.tripId} on ${w.serviceDate}"
                }
                out.println(row(deviceId.take(8), w.id, w.kind.name, w.label, w.originStationId, w.destinationStationId, schedule))
            }
            out.println("${all.size} watches")
        }
        "notifications" -> {
            val opts = parseOptions(args, setOf("--limit"))
            val limit = opts["--limit"]?.let {
                it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw UsageException("--limit must be a positive number")
            } ?: 50
            out.println(row("TIME", "DEVICE", "KIND", "TRIP", "TITLE", "RESULT"))
            SqliteNotificationLog(db).recent(limit).forEach {
                out.println(row(formatTime(it.atMs), it.deviceId.take(8), it.kind, it.tripId ?: "-", it.title, it.result))
            }
        }
        "prune" -> out.println(devices.prune())
        "backup" -> {
            val file = args.singleOrNull() ?: throw UsageException("backup needs exactly one target file")
            val target = Paths.get(file)
            db.backupTo(target)
            out.println("Backed up ${db.path} to ${target.toAbsolutePath()}")
        }
        else -> throw UsageException("unknown command '$command'")
    }
    return 0
}

private fun importGtfs(args: List<String>, config: ServerConfig, out: PrintStream, err: PrintStream, clock: WallClock): Int {
    if (args.size < 2) throw UsageException("import-gtfs needs a pack name and at least one GTFS zip or directory")
    val packName = args[0]
    if (!PackStore.isSafeComponent(packName)) throw UsageException("bad pack name '$packName'")
    val inputs = args.drop(1).map { Paths.get(it) }
    inputs.firstOrNull { !Files.isRegularFile(it) && !Files.isDirectory(it) }?.let {
        err.println("tt-admin: no such file or directory: $it")
        return 1
    }
    Files.createDirectories(config.packsDir)
    // No GTFS source: this only builds from the given local files and never calls TfNSW.
    val repository = PipelineTimetableRepository(config, source = null, options = PipelineOptions.fromEnv(), clock = clock)
    return repository.use {
        try {
            val info = it.importLocal(packName, inputs)
            out.println("Published timetable pack ${info.name}/${info.version}")
            out.println("Valid:       ${info.validFrom} to ${info.validTo}")
            out.println("Size:        ${info.sizeBytes} bytes compressed")
            out.println("SHA-256:     ${info.sha256}")
            out.println("Location:    ${config.packsDir.toAbsolutePath()}")
            0
        } catch (e: PackBuildException) {
            err.println("tt-admin: ${e.message}")
            e.report?.errors?.take(20)?.forEach { issue -> err.println("  $issue") }
            1
        }
    }
}

private fun parseOptions(args: List<String>, known: Set<String>): Map<String, String> {
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (key !in known) throw UsageException("unexpected argument '$key'")
        val value = args.getOrNull(i + 1) ?: throw UsageException("$key needs a value")
        result[key] = value
        i += 2
    }
    return result
}

private fun secs(s: Int): String = "%02d:%02d".format(s / 3600, (s % 3600) / 60)

private fun row(vararg cells: String): String = cells.joinToString("  ") { it.padEnd(12) }.trimEnd()
