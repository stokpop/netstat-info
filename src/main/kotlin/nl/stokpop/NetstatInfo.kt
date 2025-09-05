package nl.stokpop

import nl.stokpop.TcpDirection.INCOMING
import nl.stokpop.TcpDirection.OUTGOING
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {

    if (args.isEmpty()) {
        println("provide command: report, compare")
        exitProcess(1)
    }

    when (Command.valueOf(args[0])) {
        Command.report -> report(args)
        Command.compare -> compare(args)
    }

}

private fun report(args: Array<String>) {
    if (! (args.size == 2 || args.size == 3) ) {
        println("provide filename or directory and optional mapper filename")
        exitProcess(1)
    }

    val netstatFilename = args[1]
    val file = File(netstatFilename)

    val mappers = if (args.size == 3) {
            val mapperFilename = args[2]
            readMappers(mapperFilename)
        }
        else emptyMap()

    if (file.isFile) {
        processFile(file, mappers)
    } else {
        file.walk().forEach {
            if (it.isFile) processFile(it, mappers)
        }
    }
}

fun compare(args: Array<String>) {
    if (! (args.size == 4 || args.size == 5)) {
        println("provide port number and two filenames or dirs and optional mapper file")
        exitProcess(1)
    }

    val port = args[1].toInt()
    val filename1 = args[2]
    val filename2 = args[3]

    val mappers = if (args.size == 5) {
        val mapperFilename = args[4]
        readMappers(mapperFilename)
    }
    else emptyMap()

    val file1 = File(filename1)
    val file2 = File(filename2)

    if (file1.isFile && file2.isFile) {
        compareFiles(port, file1, file2, mappers)
    } else if (file1.isFile && file2.isDirectory) {
        file2.walk().forEach {
            if (it.isFile && file1 != it) compareFiles(port, file1, it, mappers)
        }
    } else if (file1.isDirectory && file2.isDirectory) {
        val processed = HashSet<File>()
        file1.walk().forEach { fileA ->
            if (fileA.isFile) {
                file2.walk().forEach { fileB ->
                    if (fileB.isFile && fileA != fileB && !processed.contains(fileB)) {
                        compareFiles(port, fileA, fileB, mappers)
                        processed.add(fileA)
                    }
                }

            }
        }
    } else {
        println("ERROR: one or both are not files")
    }
}

fun compareFiles(port: Int, file1: File, file2: File, mappers: Map<String, String>) {
    val netstatInfos1 = readTcpStates(file1)
    val netstatInfos2 = readTcpStates(file2)

    println("==> compare ${file1.name} and ${file2.name}")
    netstatInfos1.asSequence()
            .filter { info -> info.state != TcpState.LISTEN }
            .filter { info -> info.local.port == port || info.foreign.port == port }
            .filter { info -> netstatInfos2.contains(info) }
            .forEach {
                val otherState = netstatInfos2[netstatInfos2.indexOf(it)].state
                val ipLocal = mappers.getOrDefault(it.local.ip, it.local.ip)
                val ipRemote = mappers.getOrDefault(it.foreign.ip, it.foreign.ip)
                // don't know what file was first yet...
                val orderedStates = orderStates(it.state, otherState)
                println("$ipRemote(${it.foreign.port})-$ipLocal(${it.local.port}) $orderedStates")
            }
}

fun orderStates(state: TcpState, otherState: TcpState): String {
    if (state == TcpState.ESTABLISHED) return "$state ==> $otherState"
    if (otherState == TcpState.ESTABLISHED) return "$otherState ==> $state"
    if (state == TcpState.FIN_WAIT1) return "$state ==> $otherState"
    if (otherState == TcpState.FIN_WAIT1) return "$otherState ==> $state"
    if (state == TcpState.FIN_WAIT2) return "$state ==> $otherState"
    if (otherState == TcpState.FIN_WAIT2) return "$otherState ==> $state"
    if (state == TcpState.CLOSE_WAIT) return "$state ==> $otherState"
    if (otherState == TcpState.CLOSE_WAIT) return "$otherState ==> $state"
    return "$state ==> $otherState"
}

enum class Command {
    report, compare
}

private fun processFile(netstatFile: File, mappers: Map<String, String>) {

    println("\n\n========= Processing $netstatFile =========")

    val netstatInfos = readTcpStates(netstatFile)

    val listenPorts = listenPorts(netstatInfos)
    println("\n==> Listen ports (${listenPorts.size})")
    println(listenPorts.toSortedSet().joinToString())

    println("\n=== INCOMING ===")
    report(netstatInfos, listenPorts, INCOMING, mappers)

    println("\n=== OUTGOING ===")
    report(netstatInfos, listenPorts, OUTGOING, mappers)
}

private fun report(
        netstatInfos: List<NetstatInfo>,
        listenPorts: Set<Int>,
        direction: TcpDirection,
        mappers: Map<String, String>
) {
    println("\n==> Count per state ($direction)")
    countStates(netstatInfos, listenPorts, direction).toSortedMap().forEach { println(it) }

    println("\n==> Count established per address and port ($direction)")
    statePerLocalAddress(netstatInfos, TcpState.ESTABLISHED, mappers, listenPorts, direction).toSortedMap()
            .forEach { println(it) }

}

fun listenPorts(netstatInfos: List<NetstatInfo>): Set<Int> {
    return netstatInfos
            .filter { it.state == TcpState.LISTEN }
            .map { it.local.port }
            .toHashSet()
}

fun readMappers(mapperFilename: String): Map<String, String> {
    val lines = File(mapperFilename).readLines()

    return lines.associate {
        val (name, value) = it.split("=")
        name to value
    }
}

fun statePerLocalAddress(
        netstatInfos: List<NetstatInfo>,
        state: TcpState,
        mappers: Map<String, String>,
        listenPorts: Set<Int>,
        direction: TcpDirection
): Map<String, Int> {

    val predicate: (NetstatInfo) -> Boolean = createIncomingOutgoingFilter(listenPorts, direction)

    return netstatInfos
            .asSequence()
            .filter { it.state == state }
            .filter(predicate) // incoming connections only
            .map(createIPwithPortName(mappers, direction))
            .groupingBy { it }
            .eachCount()
}

private fun createIPwithPortName(
        mappers: Map<String, String>,
        direction: TcpDirection
): (NetstatInfo) -> String {
    return {
        val ipRemote = mappers.getOrDefault(it.foreign.ip, it.foreign.ip)
        if (direction == INCOMING) "I $ipRemote(${it.local.port})" else "O $ipRemote(${it.foreign.port})"
    }
}

private fun createIncomingOutgoingFilter(listenPorts: Set<Int>, direction: TcpDirection): (NetstatInfo) -> Boolean {
    when (direction) {
        INCOMING -> return { listenPorts.contains(it.local.port) }
        OUTGOING -> return { !listenPorts.contains(it.local.port) }
    }
}

fun countStates(netstatInfos: List<NetstatInfo>, listenPorts: Set<Int>, direction: TcpDirection): Map<String, Int> {
    return netstatInfos
            .asSequence()
            .filter { it.state != TcpState.LISTEN }
            .filter(createIncomingOutgoingFilter(listenPorts, direction))
            .map { if (direction == INCOMING) "I ${it.state}(${it.local.port})" else "O ${it.state}(${it.foreign.port})" }
            .groupingBy { it }
            .eachCount()
}

fun readTcpStates(file: File): List<NetstatInfo> {
    val lines = file.readLines()

    return lines
            .filter { line -> line.startsWith("tcp ") || line.startsWith("tcp6 ") || line.startsWith("tcp4 ") }
            .map { line -> NetstatInfo.fromLine(line) }
            .toCollection(ArrayList())
}

data class NetstatInfo(
        val proto: String,
        val recvQ: Int,
        val sendQ: Int,
        val local: Address,
        val foreign: Address,
        val state: TcpState
) {
    override fun equals(other: Any?): Boolean =
            (other is NetstatInfo)
                    && proto == other.proto
                    && local == other.local
                    && foreign == other.foreign

    override fun hashCode(): Int {
        return proto.hashCode() + local.hashCode() + foreign.hashCode()
    }

    companion object {
        fun fromLine(line: String): NetstatInfo {
            val parts = line.split("\\s+".toRegex())
            return NetstatInfo(
                    parts[0],
                    parts[1].toInt(),
                    parts[2].toInt(),
                    Address.fromString(parts[3]),
                    Address.fromString(parts[4]),
                    TcpState.valueOf(parts[5])
            )
        }
    }
}

data class Address(val ip: String, val port: Int) {
    companion object {
        fun fromString(text: String): Address = run {
            // netstat on mac has . instead of : before port number
            val parseText = macToUnixNetstatIpAndPortFormat(text)
            val countSemiCols = parseText.count { c -> c == ':' }
            val split = if (countSemiCols == 1) {
                parseText.split(":")
            } else if (countSemiCols == 3) {
                // example line: tcp6       0      0 :::8080                 :::*                    LISTEN
                parseText.replace("::", "0.0.0.0").split(":")
            } else {
                throw RuntimeException("cannot parse address from: $text")
            }
            if (split[1] == "*") {
                Address(split[0], -1)
            } else {
                Address(split[0], split[1].toInt())
            }
        }

        private fun macToUnixNetstatIpAndPortFormat(text: String): String {
            return if (text.contains(":")) {
                text
            } else {
                val lastIndexOfDot = text.lastIndexOf('.')
                text.take(lastIndexOfDot) + ':' + text.substring(lastIndexOfDot + 1)
            }
        }
    }
}

enum class TcpState {
    LISTEN, ESTABLISHED, TIME_WAIT, FIN_WAIT1, FIN_WAIT2, CLOSE_WAIT, SYN_RECV, SYN_SENT, LAST_ACK
}

enum class TcpDirection {
    INCOMING, OUTGOING
}