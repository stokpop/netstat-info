package nl.stokpop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Parses JDK jcmd style thread-dumps (JDK 21+) and aggregates:
 * - counts of platform vs virtual threads
 * - groups of similar threads by normalized stacktrace
 * - counts per dump across time to see if similar threads are still alive
 * - counts of virtual threads without a stacktrace
 *
 * Usage:
 *   kotlin -jar app.jar thread-dumps [output.txt]
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
        fun normalizedKey(): String {
            // Normalize stack frames: keep method owner and method, strip line numbers and Unknown Source details
            if (stack.isEmpty()) return "<empty>"
            val normFrames = stack.map { frame ->
                var f = frame.trim()
                // remove leading numbers or bullets if any
                f = f.replace("^at\\s+".toRegex(), "")
                // remove line numbers like (:123) or (Foo.java:123) or (Unknown Source)
                f = f.replace("\\(.*?\\)".toRegex(), "")
                // collapse multiple spaces and slashes
                f = f.replace("\\s+".toRegex(), " ")
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
        val groups: Map<String, GroupCount>
    )

    data class GroupCount(
        var total: Int = 0,
        var platform: Int = 0,
        var virtual: Int = 0
    )

    fun parseDump(file: File): DumpResult {
        val lines = file.readLines()
        val entries = mutableListOf<ThreadEntry>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#")) {
                // Header example: #97407 "tomcat-handler-2093" virtual
                val header = line
                val isVirtual = header.contains(" virtual")
                val idMatch = "#(\\S+)".toRegex().find(header)
                val id = idMatch?.groupValues?.get(1) ?: "?"
                val nameMatch = "\"(.*?)\"".toRegex().find(header)
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
        val groups = mutableMapOf<String, GroupCount>()

        for (e in entries) {
            if (e.isVirtual) {
                virtual++
                if (!e.hasStack) virtualNoStack++
            } else {
                platform++
            }
            val key = e.normalizedKey()
            val gc = groups.getOrPut(key) { GroupCount() }
            gc.total++
            if (e.isVirtual) gc.virtual++ else gc.platform++
        }

        val timestamp = extractTimestamp(file.name) ?: extractTimestampFromContent(lines) ?: file.name
        return DumpResult(timestamp, platform, virtual, virtualNoStack, groups)
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

    fun analyzeDirectory(dir: File): List<DumpResult> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()
        return files.map { parseDump(it) }
    }

    fun makeReport(results: List<DumpResult>): String {
        if (results.isEmpty()) return "No thread dumps found."
        val sb = StringBuilder()
        sb.append("Thread-dump analysis report\n")
        sb.append("\n")
        for (r in results) {
            sb.append("Dump: ${r.timestamp}\n")
            sb.append("  Platform threads: ${r.platformCount}\n")
            sb.append("  Virtual threads:  ${r.virtualCount}\n")
            sb.append("  Virtual w/o stacktrace: ${r.virtualWithoutStack}\n")
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
        return sb.toString()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val dir = if (args.isNotEmpty()) File(args[0]) else File("thread-dumps")
            val outFile = if (args.size >= 2) File(args[1]) else null
            if (!dir.exists() || !dir.isDirectory) {
                System.err.println("Directory not found: ${dir.absolutePath}")
                return
            }
            val analyzer = ThreadDumpAnalyzer()
            val results = analyzer.analyzeDirectory(dir)
            val report = analyzer.makeReport(results)
            if (outFile != null) {
                outFile.writeText(report)
                println("Report written to: ${outFile.absolutePath}")
            } else {
                println(report)
            }
        }
    }
}
