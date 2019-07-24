package app

import java.io.File
import java.text.DecimalFormat
import java.io.FileReader
import java.util.*
import java.util.jar.JarFile

const val POM_PROP_FILE = "pom.properties"
const val JAR = "pleeco-core-acl-core-1.4.0-SNAPSHOT.jar"

fun main(args: Array<String>) {
    val dir = args[0]
    val libsDir = "$dir/libs"
    val allDeps = File(libsDir).list()!!.toMutableList().map { convertLib2Dep(libsDir, it) }

    val serviceDepsPath = "$dir/pleeco-core-acl-core-1.4.0-SNAPSHOT-dependencies.xml"
    val serviceDeps: List<Dep> = parseServiceDeps(serviceDepsPath)

    val usedDeps = findUsedDeps(libsDir, "$dir/jdeps.out")
    val unusedDeps = allDeps.minus(usedDeps.keys)
    printDepsStats(allDeps, usedDeps.keys, unusedDeps)
}

fun printDepsStats(allDeps: List<Dep>, usedDeps: Set<Dep>, unusedDeps: List<Dep>) {
    val allDepsLength = calcJarLength(allDeps)
    val usedDepsLength = calcJarLength(usedDeps)
    val unusedDepsLength = calcJarLength(unusedDeps)

    println(
        """
        Total    ${allDeps.size},     ${allDepsLength.humanReadable()}
        Used      ${usedDeps.size},      ${usedDepsLength.humanReadable()},     ${(1.0 * usedDepsLength / allDepsLength * 100).toInt()}%
        Unused   ${unusedDeps.size},      ${unusedDepsLength.humanReadable()},       ${(1.0 * unusedDepsLength / allDepsLength * 100).toInt()}%
    """
    )
}

fun findUsedDeps(libsDir: String, jdepsOutPath: String): Map<Dep, List<Dep>> {
    val deps = mutableMapOf<Dep, MutableList<Dep>>()
    File(jdepsOutPath).forEachLine { line ->
        if (line.contains("->")
            && !line.contains("not found")
            && !line.contains("/usr/lib/jvm")
            && !line.contains(JAR)
        ) {
            val parts = line.split("->")
            val parent = convertLib2Dep(libsDir, parts[0].trim())
            val child = convertLib2Dep(libsDir, parts[1].trim().replaceFirst("libs/", ""))

            var depsList = deps[parent]
            if (depsList == null) {
                depsList = mutableListOf()
                deps[parent] = depsList
            }

            depsList.add(child)
        }
    }

    return deps
}

fun parseServiceDeps(serviceDepsPath: String): List<Dep> {
    val properties = Properties()
    properties.load(FileReader(serviceDepsPath))

    return properties.map { Dep(it.key as String, it.value as String) }
}

fun convertLib2Dep(libsDir: String, jarFileName: String): Dep {
    val jarAbsPath = "$libsDir/$jarFileName"
    val jarFile = JarFile(jarAbsPath)
    val entries = jarFile.entries()
    while (entries.hasMoreElements()) {
        val jarEntry = entries.nextElement()
        if (jarEntry.name.contains(POM_PROP_FILE)) {
            val properties = Properties()
            properties.load(jarFile.getInputStream(jarEntry))

            return Dep(
                checkNotNull(properties["groupId"]) as String,
                checkNotNull(properties["artifactId"]) as String,
                jarAbsPath
            )
        }
    }

    println("$POM_PROP_FILE isn't found in $jarFileName")
    return Dep(null, null, jarAbsPath)
}

fun calcJarLength(deps: Iterable<Dep>): Int {
    return deps.sumBy { File(it.jarAbsPath!!).length().toInt() }
}

fun Long.humanReadable(): String {
    if (this <= 0) return "0"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "EB")
    val digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt();
    return DecimalFormat("#,##0.#").format(this / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups];
}

fun Int.humanReadable(): String {
    return this.toLong().humanReadable()
}

data class Dep(val groupId: String?, val artifactId: String?, val jarAbsPath: String? = null)