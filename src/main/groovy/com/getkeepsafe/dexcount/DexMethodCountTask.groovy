/*
 * Copyright (C) 2015-2016 KeepSafe Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.getkeepsafe.dexcount

import com.android.annotations.Nullable
import com.android.build.gradle.api.BaseVariantOutput
import com.android.dexdeps.HasDeclaringClass
import com.android.dexdeps.Output
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.api.DefaultTask
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.logging.StyledTextOutput
import org.gradle.logging.StyledTextOutputFactory

class DexMethodCountTask extends DefaultTask {
    /**
     * The maximum number of method refs and field refs allowed in a single Dex
     * file.
     */
    private static final int MAX_DEX_REFS = 0xFFFF;

    def BaseVariantOutput apkOrDex

    @Nullable
    def File mappingFile

    @OutputFile
    def File outputFile

    @OutputFile
    def File summaryFile

    @OutputFile
    def File chartDir

    def DexMethodCountExtension config

    @TaskAction
    void countMethods() {
        def startTime = System.currentTimeMillis()

        def excludedPackages = config.excludedPackages
        def dexData = extractDexData()
        def ioTime = System.currentTimeMillis()

        def tree = generatePackageTree(dexData)
        def excludedTree = excludedPackages.size() > 0 ?
                generatePackageTree(dexData, excludedPackages) : null
        def treegenTime = System.currentTimeMillis()

        printSummary(tree)
        if (excludedTree != null) {
            printSummary(excludedTree, excludedPackages)
        }
        printFullTree(tree)
        printChart(tree)
        def outputTime = System.currentTimeMillis()

        printTaskDiagnosticData(tree, startTime, ioTime, treegenTime, outputTime)
    }

    static def percentUsed(int count) {
        def used = ((double) count / MAX_DEX_REFS) * 100.0
        return sprintf("%.2f", used)
    }

    /**
     * Prints a summary of method and field counts
     */
    def printSummary(PackageTree tree, List<String> excludedPackages = []) {
        def filename = apkOrDex.outputFile.name
        withStyledOutput(StyledTextOutput.Style.Info) { out ->
            def percentMethodsUsed = percentUsed(tree.methodCount)
            def percentFieldsUsed = percentUsed(tree.fieldCount)

            def methodsRemaining = Math.max(MAX_DEX_REFS - tree.methodCount, 0)
            def fieldsRemaining = Math.max(MAX_DEX_REFS - tree.fieldCount, 0)

            out.println("")
            if (excludedPackages.size() > 0) {
                out.println("Excluding packages: ${excludedPackages}")
            }

            out.println("Total methods in ${filename}: ${tree.methodCount} ($percentMethodsUsed% used)")
            out.println("Total fields in ${filename}:  ${tree.fieldCount} ($percentFieldsUsed% used)")
            out.println("Methods remaining in ${filename}: $methodsRemaining")
            out.println("Fields remaining in ${filename}:  $fieldsRemaining")
        }

        if (summaryFile != null) {
            summaryFile.parentFile.mkdirs()
            summaryFile.createNewFile()

            final String headers = "methods,fields";
            final String counts = "${tree.methodCount},${tree.fieldCount}";

            summaryFile.withOutputStream { stream ->
                def appendableStream = new PrintStream(stream)
                appendableStream.println(headers)
                appendableStream.println(counts);
            }
        }

        if (getPrintOptions().teamCityIntegration) {
            withStyledOutput(StyledTextOutput.Style.Normal) { out ->
                printTeamCityStatisticValue(out, "DexCount_${apkOrDex.name}_MethodCount", tree.methodCount.toString())
                printTeamCityStatisticValue(out, "DexCount_${apkOrDex.name}_FieldCount", tree.fieldCount.toString())
            }
        }
    }

    /**
     * Reports to Team City statistic value
     * Doc: https://confluence.jetbrains.com/display/TCD9/Build+Script+Interaction+with+TeamCity#BuildScriptInteractionwithTeamCity-ReportingBuildStatistics
     */
    def printTeamCityStatisticValue(StyledTextOutput out, String key, String value) {
        out.println("##teamcity[buildStatisticValue key='${key}' value='${value}']")
    }

    /**
     * Prints the package tree to the usual outputs/dexcount/variant.txt file.
     */
    def printFullTree(PackageTree tree) {
        printToFile(outputFile) { PrintStream out ->
            print(tree, out)
        }
    }

    /**
     * Prints the package tree as chart to the outputs/dexcount/${variant}Chart directory.
     */
    def printChart(PackageTree tree) {
        def printOptions = getPrintOptions()
        printOptions.includeClasses = true
        printToFile(new File(chartDir, "data.js")) { PrintStream out ->
            out.print("var data = ")
            tree.printJson(out, printOptions);
        }

        ["chart-builder.js", "d3.v3.min.js", "index.html", "styles.css"].each { String resourceName ->
            def resource = getClass().getResourceAsStream("/com/getkeepsafe/dexcount/" + resourceName);
            def targetFile = new File(chartDir, resourceName)
            targetFile.write resource.text
        }
    }

    /**
     * Logs the package tree to stdout at {@code LogLevel.DEBUG}, or at the
     * default level if verbose-mode is configured.
     */
    def printTaskDiagnosticData(PackageTree tree, long startTime, long ioTime, long treegenTime,
                                long outputTime) {
        // Log the entire package list/tree at LogLevel.DEBUG, unless
        // verbose is enabled (in which case use the default log level).
        def level = config.verbose ? null : LogLevel.DEBUG

        withStyledOutput(StyledTextOutput.Style.Info, level) { out ->
            print(tree, out)
            out.println("\n\nTask runtimes:")
            out.println("--------------")
            out.println("parsing:    ${ioTime - startTime} ms")
            out.println("counting:   ${treegenTime - ioTime} ms")
            out.println("printing:   ${outputTime - treegenTime} ms")
            out.println("total:      ${outputTime - startTime} ms")
        }
    }

    def print(PackageTree tree, Appendable writer) {
        tree.print(writer, config.format, getPrintOptions())
    }

    private void withStyledOutput(
            StyledTextOutput.Style style,
            LogLevel level = null,
            @ClosureParams(value = SimpleType, options = ['org.gradle.logging.StyledTextOutput']) Closure closure) {
        def factory = services.get(StyledTextOutputFactory)
        def output = level == null ? factory.create('dexcount') : factory.create('dexcount', level)

        closure(output.withStyle(style))
    }

    private void printToFile(
            File file,
            @ClosureParams(value = SimpleType, options = ['java.io.PrintStream']) Closure closure) {
        if (outputFile != null) {
            file.parentFile.mkdirs()
            file.createNewFile()
            file.withOutputStream { stream ->
                def out = new PrintStream(stream)
                closure(out)
                out.flush()
                out.close()
            }
        }
    }

    private List<DexFile> extractDexData() {
        return DexFile.extractDexData(apkOrDex.outputFile, config.dxTimeoutSec)
    }

    /**
     * Creates a new PackageTree and populates it with the method and field
     * counts of the current dex/apk file.
     */
    private PackageTree generatePackageTree(List<DexFile> dataList, List<String> excludedPackages = []) {
        // Create a de-obfuscator based on the current Proguard mapping file.
        // If none is given, we'll get a default mapping.
        def deobs = getDeobfuscator()
        def result = new PackageTree()
        try {
            refListToClassNames(dataList*.getMethodRefs(), deobs).each {
                if (!excludedPackages.any { pkg -> it.startsWith(pkg) }) {
                    result.addMethodRef(it)
                }
            }

            refListToClassNames(dataList*.getFieldRefs(), deobs).each {
                if (!excludedPackages.any { pkg -> it.startsWith(pkg) }) {
                    result.addFieldRef(it)
                }
            }
        } finally {
            dataList*.dispose()
        }

        return result
    }

    static refListToClassNames(List<List<HasDeclaringClass>> refs, Deobfuscator deobfuscator) {
        return refs.flatten().collect { ref ->
            def descriptor = ref.getDeclClassName()
            def dot = Output.descriptorToDot(descriptor)
            dot = deobfuscator.deobfuscate(dot)
            if (dot.indexOf('.') == -1) {
                // Classes in the unnamed package (e.g. primitive arrays)
                // will not appear in the output in the current PackageTree
                // implementation if classes are not included.  To work around,
                // we make an artificial package named "<unnamed>".
                dot = "<unnamed>." + dot
            }
            return dot
        }
    }

    private def getPrintOptions() {
        return new PrintOptions(
                includeMethodCount: true,
                includeFieldCount: config.includeFieldCount,
                includeTotalMethodCount: config.includeTotalMethodCount,
                teamCityIntegration: config.teamCityIntegration,
                orderByMethodCount: config.orderByMethodCount,
                includeClasses: config.includeClasses,
                printHeader: true,
                maxTreeDepth: config.maxTreeDepth)
    }

    private def getDeobfuscator() {
        if (mappingFile != null && !mappingFile.exists()) {
            withStyledOutput(StyledTextOutput.Style.Normal, LogLevel.DEBUG) {
                it.println("Mapping file specified at ${mappingFile.absolutePath} does not exist, assuming output is not obfuscated.")
            }
            mappingFile = null
        }

        return Deobfuscator.create(mappingFile)
    }
}
