package app

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import fr.dutra.tools.maven.deptree.core.InputType
import fr.dutra.tools.maven.deptree.core.Node
import java.io.*
import java.lang.RuntimeException
import java.text.DecimalFormat
import java.util.*
import java.util.jar.JarFile

const val POM_PROP_FILE = "pom.properties"
const val JAR = "pleeco-core-acl-core-1.4.0-SNAPSHOT.jar"
val DEPS_WHITE_LIST: List<Dep> = listOf(
        Dep("org.apache.logging.log4j", "log4j-api")
)

fun main() {
    val dir = "/home/mirian/data/mvn_dependency_pollution"
    val libsDir = "$dir/libs"
    val depsTree = parseDepsTree("$dir/deps_tree.txt")
    val allDeps = File(libsDir).list()!!.toMutableList().map { convertLib2Dep(libsDir, depsTree, it) }

    val usedDeps = findUsedDeps(libsDir, depsTree, "$dir/jdeps.out")
    assertUsedDeps(usedDeps)

    var unusedDeps = allDeps.minus(usedDeps.keys)
//    unusedDeps = applyDepsBlackList(unusedDeps)
    assertUnusedDeps(unusedDeps)
    printDepsStats(allDeps, usedDeps.keys, unusedDeps)

    val exclusions = findMavenExclusions(depsTree, unusedDeps)
    assertExclusions(exclusions, unusedDeps)
    printExclusions(dir, exclusions)
}

fun assertUsedDeps(usedDeps: Map<Dep, List<Dep>>) {
    fun depSmokeCheck(usedDeps: Map<Dep, List<Dep>>, dep: Dep) {
        if (!usedDeps.contains(dep)) {
            throw RuntimeException("$dep must be in used list")
        }
    }

    depSmokeCheck(usedDeps, Dep("org.apache.logging.log4j", "log4j-api"))
    depSmokeCheck(usedDeps, Dep("com.google.guava", "guava"))
}

fun assertUnusedDeps(unusedDeps: List<Dep>) {
    fun depSmokeCheck(unusedDeps: List<Dep>, dep: Dep) {
        if (unusedDeps.contains(dep)) {
            throw RuntimeException("$dep must not be in unused list")
        }
    }

    depSmokeCheck(unusedDeps, Dep("org.apache.logging.log4j", "log4j-api"))
    depSmokeCheck(unusedDeps, Dep("com.google.guava", "guava"))
}

fun applyDepsBlackList(unusedDeps: List<Dep>): List<Dep> {
    //TODO black list should be emtpy.
    return unusedDeps.asSequence()
            .filter { d -> !d.groupId.contains("spring") || !d.artifactId.contains("spring") }
            .filter { d -> !d.groupId.contains("commons-") || !d.artifactId.contains("commons-") }
            .filter { d -> !d.groupId.contains("validation") || !d.artifactId.contains("validation") }
            .filter { d -> !d.groupId.contains("undertow") || !d.artifactId.contains("undertow") }
            .filter { d -> !d.groupId.contains("servlet") || !d.artifactId.contains("servlet") }
            .filter { d -> !d.groupId.contains("cassandra") || !d.artifactId.contains("cassandra") }
            .filter { d -> !d.groupId.contains("kafka") || !d.artifactId.contains("kafka") }
            .filter { d -> !d.artifactId.contains("commons-collections4") }
            .toList()
}

fun printExclusions(dir: String, exclusions: Multimap<Dep, Dep>) {
    File("$dir/exclusions.xml").writer().use { out ->
        out.write("<dependencies>\n")
        for (dep in exclusions.keySet()) {
            out.write("<dependency>\n")
            out.write("<groupId>${dep.groupId}</groupId>\n")
            out.write("<artifactId>${dep.artifactId}</artifactId>\n")
            out.write("<exclusions>\n")
            for (exclusion in exclusions.get(dep)) {
                out.write("<exclusion>\n")
                out.write("<groupId>${exclusion.groupId}</groupId>\n")
                out.write("<artifactId>${exclusion.artifactId}</artifactId>\n")
                out.write("</exclusion>\n")
            }
            out.write("</exclusions>\n")
            out.write("</dependency>\n")
        }
        out.write("</dependencies>\n")
    }
}

fun assertExclusions(exclusions: Multimap<Dep, Dep>, unusedDeps: List<Dep>) {
    for (rootDep in exclusions.keySet()) {
        val excludedDeps = exclusions.get(rootDep)
        for (excludedDep in excludedDeps) {
            if (!unusedDeps.contains(excludedDep)) {
                throw RuntimeException("Excluded dep $excludedDep missing in unused deps list")
            }
            if (DEPS_WHITE_LIST.contains(excludedDep)) {
                throw RuntimeException("Dep $excludedDep must not be in exclusions")
            }
        }
    }
}

fun findMavenExclusions(depsTree: Node, unusedDeps: List<Dep>): Multimap<Dep, Dep> {
    val exclusions = LinkedHashMultimap.create<Dep, Dep>()
    for (childNode in depsTree.childNodes) {
        val rootDep = Dep(childNode.groupId, childNode.artifactId)

        for (unusedDep in unusedDeps) {
            doFindMavenExclusions(rootDep, unusedDep, depsTree.childNodes, exclusions)
        }
    }
    return exclusions
}

fun doFindMavenExclusions(
        rootDep: Dep,
        unusedDep: Dep,
        childNodes: List<Node>?,
        exclusions: Multimap<Dep, Dep>
) {
    if (childNodes == null) {
        return
    }

    for (node in childNodes) {
        if (node.artifactId == unusedDep.artifactId) {
            exclusions.put(rootDep, unusedDep)
            return
        }

        doFindMavenExclusions(rootDep, unusedDep, node.childNodes, exclusions)
    }
}

fun parseDepsTree(depsTreePath: String): Node {
    val r = BufferedReader(InputStreamReader(FileInputStream(depsTreePath), "UTF-8"));
    val parser = InputType.TEXT.newParser();
    return parser.parse(r);
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

//    println("Unused: $unusedDeps")
}

fun findUsedDeps(libsDir: String, depsTree: Node, jdepsOutPath: String): Map<Dep, List<Dep>> {
    val deps = mutableMapOf<Dep, MutableList<Dep>>()
    File(jdepsOutPath).forEachLine { line ->
        if (line.contains("->")
                && !line.contains(JAR)
                && !line.contains("jce.jar")
                && !line.contains("jfxrt.jar")
        ) {
            val parts = line.split("->")
            val parent = convertLib2Dep(libsDir, depsTree, parts[0].trim())

            val child = if (line.contains("not found") || line.contains("/usr/lib/jvm")) {
                parent
            } else {
                convertLib2Dep(libsDir, depsTree, parts[1].trim().replaceFirst("libs/", ""))
            }

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

fun convertLib2Dep(libsDir: String, depsTree: Node, jarFileName: String): Dep {
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

    var artifactId = jarFileName.replace("-SNAPSHOT", "").replace("-native", "").replace("-1.jar", "")
    artifactId = artifactId.substring(0, artifactId.lastIndexOf("-"))

    val groupId = mutableSetOf<String>()
    findGroupId(groupId, artifactId, depsTree)
    if (groupId.isEmpty()) {
        throw RuntimeException("Group is not found for artifact $artifactId")
    }

    if (groupId.size > 1) {
        throw RuntimeException("More than one group found for artifact $artifactId")
    }

    return Dep(groupId.iterator().next(), artifactId, jarAbsPath)
}

fun findGroupId(groupId: MutableSet<String>, artifactId: String, node: Node) {
    if (node.childNodes == null) {
        return
    }

    if (node.artifactId == artifactId) {
        groupId.add(node.groupId)
    }

    for (childNode in node.childNodes) {
        findGroupId(groupId, artifactId, childNode)
    }
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

data class Dep(val groupId: String, val artifactId: String, val jarAbsPath: String? = null) {
    override fun toString(): String {
        return "$groupId:$artifactId"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Dep

        if (groupId != other.groupId) return false
        if (artifactId != other.artifactId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + artifactId.hashCode()
        return result
    }


}