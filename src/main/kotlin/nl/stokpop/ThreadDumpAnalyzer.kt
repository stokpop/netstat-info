package nl.stokpop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val THREAD_ID_REGEX = "#(\\S+)".toRegex()
private val THREAD_NAME_REGEX = "\"(.*?)\"".toRegex()

private val STACK_LINE_AT_REGEX = "^at\\s+".toRegex()
private val STACK_LINE_X = "\\(.*?\\)".toRegex()
private val MULTIPLE_SPACES_REGEX = "\\s+".toRegex()

/**
 * Parses JDK jcmd style thread-dumps (JDK 21+) and aggregates:
 * - counts of platform vs virtual threads
 * - groups of similar threads by normalized stacktrace
 * - counts per dump across time to see if similar threads are still alive
 * - counts of virtual threads without a stacktrace
 *
 * Usage:
 *   java -cp build/libs/netstat-info-1.0.0-all.jar nl.stokpop.ThreadDumpAnalyzer [inputDir] [output.txt] [--filter <substring>]
 * If no args are given, defaults to ./thread-dumps and writes report to stdout.
 */
class ThreadDumpAnalyzer {
    data class ThreadEntry(
        val id: String,
        val name: String,
        val isVirtual: Boolean,
        val stack: List<String>
    ) {
        val hasStack: Boolean get() = stack.isNotEmpty()

        fun framesMatching(filter: Regex?): List<String> {
            if (filter == null) return stack
            return stack.filter { it.contains(filter) }
        }

        fun normalizedKey(frames: List<String> = stack): String {
            // Normalize stack frames: keep method owner and method, strip line numbers and Unknown Source details
            if (frames.isEmpty()) return "<empty>"
            val normFrames = frames.map { frame ->
                var f = frame.trim()
                // remove leading numbers or bullets if any
                f = f.replace(STACK_LINE_AT_REGEX, "")
                // remove line numbers like (:123) or (Foo.java:123) or (Unknown Source)
                f = f.replace(STACK_LINE_X, "")
                // collapse multiple spaces and slashes
                f = f.replace(MULTIPLE_SPACES_REGEX, " ")
                // keep first token like package.Class.method
                f
            }
            return normFrames.joinToString(" | ")
        }
    }

    data class DumpResult(
        val timestamp: String,
        val platformCount: Int,
        val virtualCount: Int,
        val virtualWithoutStack: Int,
        val carrierCount: Int,
        val groups: Map<String, GroupCount>,
        val threadInfoById: Map<String, ThreadInfo>
    )

    data class GroupCount(
        var total: Int = 0,
        var platform: Int = 0,
        var virtual: Int = 0
    )

    data class ThreadInfo(
        val key: String,
        val isVirtual: Boolean,
        val hasStack: Boolean,
        val name: String
    )

    fun parseDump(file: File, filter: Regex? = null): DumpResult {
        val lines = file.readLines()
        val entries = mutableListOf<ThreadEntry>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#")) {
                // Header example: #97407 "tomcat-handler-2093" virtual
                val header = line
                val isVirtual = header.contains(" virtual")
                val idMatch = THREAD_ID_REGEX.find(header)
                val id = idMatch?.groupValues?.get(1) ?: "?"
                val nameMatch = THREAD_NAME_REGEX.find(header)
                val name = nameMatch?.groupValues?.get(1) ?: "?"
                // collect subsequent indented stack lines until blank line or next header
                val stack = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.isBlank()) break
                    if (l.startsWith("#")) break
                    // often stack frames start with spaces; treat any non-blank, non-header as frame
                    stack.add(l.trim())
                    j++
                }
                entries.add(ThreadEntry(id, name, isVirtual, stack))
                i = j
                continue
            }
            i++
        }

        var platform = 0
        var virtual = 0
        var virtualNoStack = 0
        var carrier = 0
        val groups = mutableMapOf<String, GroupCount>()
        val idToInfo = mutableMapOf<String, ThreadInfo>()

        for (e in entries) {
            val matchingFrames = e.framesMatching(filter)
            // If a filter is provided, only include threads that have at least one matching frame.
            if (filter != null && matchingFrames.isEmpty()) continue

            if (e.isVirtual) {
                virtual++
                // In filtered mode, count virtual without stacktrace only if there are no frames at all.
                if (!e.hasStack) virtualNoStack++
            } else {
                platform++
            }
            val framesForDetection = if (filter != null) matchingFrames else e.stack
            val key = e.normalizedKey(framesForDetection)
            if (!e.isVirtual) {
                // Heuristic: platform threads acting as carrier threads typically show these frames
                if (framesForDetection.any { frame ->
                        frame.contains("jdk.internal.vm.Continuation.run") ||
                        frame.contains("java.lang.VirtualThread.runContinuation") ||
                        frame.contains("java.lang.VirtualThread.run")
                    }) {
                    carrier++
                }
            }
            idToInfo[e.id] = ThreadInfo(key, e.isVirtual, e.hasStack, e.name)
            val gc = groups.getOrPut(key) { GroupCount() }
            gc.total++
            if (e.isVirtual) gc.virtual++ else gc.platform++
        }

        val timestamp = extractTimestamp(file.name) ?: extractTimestampFromContent(lines) ?: file.name
        return DumpResult(timestamp, platform, virtual, virtualNoStack, carrier, groups, idToInfo)
    }

    private fun extractTimestamp(filename: String): String? {
        // expected like thread.dump.jcmd.smurf.2025-09-04T22-48-28.txt
        val tsRegex = ".*(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}).*".toRegex()
        val m = tsRegex.find(filename) ?: return null
        val raw = m.groupValues[1]
        return try {
            val dt = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
            dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            raw
        }
    }

    private fun extractTimestampFromContent(lines: List<String>): String? {
        // Often the third line is a timestamp
        return lines.firstOrNull { it.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}".toRegex()) }
    }

    fun analyzeDirectory(dir: File, filter: Regex? = null): List<DumpResult> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()
        return files.map { parseDump(it, filter) }
    }

    fun makeReport(results: List<DumpResult>, filterText: String? = null): String {
        if (results.isEmpty()) return "No thread dumps found."
        val sb = StringBuilder()
        sb.append("Thread-dump analysis report\n")
        filterText?.let { sb.append("Filter: '$it' (case-insensitive substring)\n") }
        sb.append("\n")
        for (r in results) {
            sb.append("Dump: ${r.timestamp}\n")
            sb.append("  Platform threads: ${r.platformCount}\n")
            sb.append("  Virtual threads:  ${r.virtualCount}\n")
            sb.append("  Virtual w/o stacktrace: ${r.virtualWithoutStack}\n")
            sb.append("  Carrier threads active: ${r.carrierCount}\n")
            sb.append("  Groups (by normalized stacktrace): ${r.groups.size}\n")
            // show top 10 groups by total count
            val top = r.groups.entries.sortedByDescending { it.value.total }.take(10)
            for ((k, v) in top) {
                sb.append("    count=${v.total} (plat=${v.platform}, virt=${v.virtual}) :: ${k}\n")
            }
            sb.append("\n")
        }

        // Cross-dump comparison: for each group observed in any dump, show counts across dumps
        val allKeys = linkedSetOf<String>()
        results.forEach { allKeys.addAll(it.groups.keys) }
        sb.append("Cross-dump group continuity (first 50 groups):\n")
        var shown = 0
        for (key in allKeys) {
            if (shown >= 50) break
            sb.append("Group: ${key}\n")
            for (r in results) {
                val c = r.groups[key]
                sb.append("  ${r.timestamp}: ${c?.total ?: 0} (plat=${c?.platform ?: 0}, virt=${c?.virtual ?: 0})\n")
            }
            sb.append("\n")
            shown++
        }

        // Alive threads between consecutive dumps (same id and same normalized stack)
        sb.append("Alive threads between consecutive dumps (same id + same stack):\n")
        for (i in 0 until results.size - 1) {
            val a = results[i]
            val b = results[i + 1]
            var count = 0
            val examples = mutableListOf<String>()
            for ((id, infoA) in a.threadInfoById) {
                val infoB = b.threadInfoById[id]
                if (infoB != null && infoB.key == infoA.key) {
                    count++
                    if (examples.size < 20) {
                        examples.add("#${id} \"${infoA.name}\" ${if (infoA.isVirtual) "virtual" else "platform"} :: ${infoA.key}")
                    }
                }
            }
            sb.append("${a.timestamp} -> ${b.timestamp}: ${count} threads\n")
            if (examples.isNotEmpty()) {
                for (ex in examples) {
                    sb.append("  ${ex}\n")
                }
            }
        }

        // Virtual threads alive with different or missing stack between consecutive dumps
        sb.append("Virtual threads alive with different or missing stack (same id, stack changed):\n")
        for (i in 0 until results.size - 1) {
            val a = results[i]
            val b = results[i + 1]
            var count = 0
            val examples = mutableListOf<String>()
            for ((id, infoA) in a.threadInfoById) {
                val infoB = b.threadInfoById[id]
                if (infoB != null && infoA.isVirtual && infoB.isVirtual) {
                    if (infoA.key != infoB.key) {
                        count++
                        if (examples.size < 20) {
                            examples.add("#${id} \"${infoA.name}\" virtual :: ${infoA.key} -> ${infoB.key}")
                        }
                    }
                }
            }
            sb.append("${a.timestamp} -> ${b.timestamp}: ${count} virtual threads\n")
            if (examples.isNotEmpty()) {
                for (ex in examples) {
                    sb.append("  ${ex}\n")
                }
            }
        }
        return sb.toString()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            var dir: File? = null
            var outFile: File? = null
            var filterText: String? = null

            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "--filter" || arg == "-f" -> {
                        if (i + 1 < args.size) {
                            filterText = args[i + 1]
                            i += 1
                        }
                    }
                    arg.startsWith("--filter=") -> {
                        filterText = arg.substringAfter("=")
                    }
                    dir == null -> dir = File(arg)
                    outFile == null -> outFile = File(arg)
                    else -> { /* ignore extra args */ }
                }
                i += 1
            }

            val directory = dir ?: File("thread-dumps")
            if (!directory.exists() || !directory.isDirectory) {
                System.err.println("Directory not found: ${directory.absolutePath}")
                return
            }

            val filterRegex = filterText?.let { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

            val analyzer = ThreadDumpAnalyzer()
            val results = analyzer.analyzeDirectory(directory, filterRegex)
            val report = analyzer.makeReport(results, filterText)
            if (outFile != null) {
                outFile.writeText(report)
                println("Report written to: ${outFile.absolutePath}")
            } else {
                println(report)
            }
        }
    }
}
