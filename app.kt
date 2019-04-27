/*
MIT License

Copyright (c) 2019 Stephen Merrony

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

const val SEM_VER = "v1.4.1" // matches Go SEM_VER from v1.4.0

fun main(args: Array<String>) {
    // program options...
    var extract = false         // do not extract unless requested
    var ignoreErrors = false    // if true, then some errors are ignored when restoring files/dirs/links
    var list = false            // not really used at present
    var summary = false         // summarise the contents of the DUMP
    var verbose = false         // very wordy output
    var version = false

    val baseDir: String = System.getProperty("user.dir")  // as DUMP files can legally contain too many POPs we store cwd and avoid traversing above it
    var dump = ""
    var arg: String

    if (args.isEmpty()) {
        println("ERROR: No arguments supplied.")
        printHelp()
        return
    }
    for (str in args) {
        arg = str.replace(Regex("^-*"),"")  // remove leading hyphens
        when {
            arg.startsWith("dumpfile") -> dump = arg.removePrefix("dumpfile=")
            arg.startsWith("extract") -> extract = true
            arg.startsWith("help") -> printHelp()
            arg.startsWith("ignoreerrors") -> ignoreErrors = true
            arg.startsWith("list") -> list = true
            arg.startsWith("summary") -> summary = true
            arg.startsWith("verbose") -> verbose = true
            arg.startsWith("version" ) -> version = true
            else -> {
                println("ERROR: Unknown option... $arg")
                printHelp()
            }
        }
    }
    if (verbose or version) {
        println("LoadK version $SEM_VER")
        if (!verbose) exitProcess(0)
    }
    if (dump.isEmpty()) {
        println("ERROR: Must specify dump file name with --dumpfile=<dumpfile> option")
        exitProcess(1)
    }

    try {
        val path = Paths.get(dump)
        val dumpStream = Files.newInputStream(path, StandardOpenOption.READ)
        val aosvsDump = AosvsDumpFile(dumpStream.buffered(512))
        if (verbose or summary) println("Summary of DUMP file : $dump")
        aosvsDump.parse(extract, ignoreErrors, list, summary, verbose, baseDir)
    } catch (e: Exception) {
        println("ERROR: Could not open DUMP file - ${e.message}")
        exitProcess(1)
    }
}

fun printHelp() {
    println( "Usage: LoadK [--help]|--dumpfile=<filename> [--version] [--extract] [--ignoreerrors] [--list] [--summary]")
    exitProcess(0)
}
