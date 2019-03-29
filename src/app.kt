@file:UseExperimental(ExperimentalUnsignedTypes::class)

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
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.IllegalArgumentException
import kotlin.system.exitProcess

typealias dgByte = UByte
typealias dgWord = UShort
typealias dgDword = UInt

const val version = "v0.9.4" // following-on from Go version

const val DISK_BLOCK_BYTES = 512

var loadIt = false
var extract = false // do not extract unless requested
var ignoreErrors = false
var list = false
var summary = false
var verbose = false

val baseDir: String = System.getProperty("user.dir")  // as DUMP files can legally contain too many POPs we store cwd and avoid traversing above it
var workingDir = baseDir
val separator: String = File.separator
lateinit var writeFile: FileOutputStream

var inFile = false
var totalFileSize = 0

fun main(args: Array<String>) {
    var dump = ""
    var arg: String
    val bufferedDump: BufferedInputStream

    var fsbBlob = ByteArray(0)
    var fileName = ""

    if (args.isEmpty()) {
        println("ERROR: No arguments supplied, try --help")
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
            arg.startsWith("version" ) -> println("LoadK Version $version")
            else -> {
                println("ERROR: Unknown option... $arg")
                printHelp()
            }
        }
    }
    if (dump.isEmpty()) {
        println("ERROR: Must specify dump file name with --dumpfile=<dumpfile> option")
        exitProcess(1)
    }

    try {
        val path = Paths.get(dump)
        val dumpStream = Files.newInputStream(path, StandardOpenOption.READ)
        bufferedDump = dumpStream.buffered(512)
    } catch (e: Exception) {
        println("ERROR: Could not open DUMP file - ${e.message}")
        exitProcess(1)
    }

    // there should always be a SOD record...
    val sod = readSOD(bufferedDump)
    if (summary or verbose) {
        println("Summary of DUMP file : $dump")
        println("AOS/VS DUMP version  : ${sod.dumpFormatRevision}")
        println("DUMP date (y-m-d)    : ${sod.dumpTimeYear}-${sod.dumpTimeMonth}-${sod.dumpTimeDay}")
        println("DUMP time (hh:mm:ss) : ${sod.dumpTimeHours}:${sod.dumpTimeMins}:${sod.dumpTimeSecs}")
    }

    // now work through the dump examining each block type and handling accordingly...
    var done = false

    //var loadIt = false

    while (!done) {
        val recHdr = readHeader(bufferedDump)
        if (verbose) {
            println("Found block of type: " + recHdr.recordType.name + " length: ${recHdr.recordLength}")
        }
        when (recHdr.recordType) {
            RecordType.START -> {
                println("ERROR: Another START record found in DUMP - this should not happen.")
                exitProcess(1)
            }
            RecordType.FSB -> {
                fsbBlob = readBlob(recHdr.recordLength, bufferedDump, "FSB" )
                loadIt = false
            }
            RecordType.NB -> {
                fileName = processNameBlock( recHdr, fsbBlob, bufferedDump )
            }
            RecordType.UDA -> {
                // throw away for now
                readBlob(recHdr.recordLength, bufferedDump, "UDA")
            }
            RecordType.ACL -> {
                val aclBlob =  readBlob(recHdr.recordLength, bufferedDump, "ACL")
                if (verbose) println(" ACL: " + aclBlob.toString(Charsets.US_ASCII).trimEnd('\u0000'))
            }
            RecordType.LINK -> {
                processLink(recHdr, fileName,  bufferedDump)
            }
            RecordType.START_BLOCK -> {
                // nothing to do - it's just a recHdr
            }
            RecordType.DATA_BLOCK -> {
                processDataBlock(recHdr, bufferedDump)
            }
            RecordType.END_BLOCK -> {
                processEndBlock()
            }
            RecordType.END -> {
                println("=== End of Dump ===")
                done = true
            }
         }
    }
}

fun readBlob(len: Int, d: BufferedInputStream, desc: String): ByteArray {
    val blob = ByteArray(len)
    try {
        val n = d.read(blob)
        check( n == len) {"Did not get expected number of bytes"}
    } catch (e: Exception) {
        println("ERROR: Could not read $desc record - ${e.message}")
        exitProcess(1)
    }
    return blob
}

fun printHelp() {
    println( "Usage: LoadK [--help]|--dumpfile=<filename> [--version] [--extract] [--ignoreerrors] [--list] [--summary]")
    exitProcess(0)
}

enum class RecordType(val id: Int) {
    START(0),
    FSB(1),
    NB(2),
    UDA(3),
    ACL(4),
    LINK(5),
    START_BLOCK(6),
    DATA_BLOCK(7),
    END_BLOCK(8),
    END(9);

    companion object {
        private val map = values().associateBy( RecordType::id )
        fun fromInt(rt: Int) = map[rt] ?: throw IllegalArgumentException()
    }
}

data class RecordHeader (
    val recordType: RecordType,
    val recordLength: Int
)

fun readHeader(d: BufferedInputStream): RecordHeader {
    val twoBytes: ByteArray = byteArrayOf(0, 0)
    try {
        val n = d.read(twoBytes)
        check(n == 2) {"Did not read two bytes"}
    } catch (e: java.lang.Exception) {
        println("ERROR: Could not read Header record from DUMP - ${e.message}")
        exitProcess(1)
    }
    val rt = twoBytes[0].toInt().shr(2) and 0x00FF
    val rl = (twoBytes[0].toInt() and 0x03).shl(8) + twoBytes[1].toInt()
    return RecordHeader(RecordType.fromInt(rt), rl)
}

fun readWord(d: BufferedInputStream): dgWord {
    val twoBytes: ByteArray = byteArrayOf(0, 0)
    try {
        val n =  d.read(twoBytes)
        check(n == 2) {"Did not read two bytes"}
    } catch (e: java.lang.Exception) {
        println("ERROR: Could not read Word from DUMP - ${e.message}")
        exitProcess(1)
    }
    return (twoBytes[0].toUInt().shl(8)).toUShort().or(twoBytes[1].toUShort())
}

data class SOD (
    val header: RecordHeader,
    val dumpFormatRevision: dgWord,
    val dumpTimeSecs: dgWord,
    val dumpTimeMins: dgWord,
    val dumpTimeHours: dgWord,
    val dumpTimeDay: dgWord,
    val dumpTimeMonth: dgWord,
    val dumpTimeYear: dgWord
    )

fun readSOD(d: BufferedInputStream): SOD {
    val hdr = readHeader(d)
    if (hdr.recordType != RecordType.START) {
        println("ERROR: This does not appear to be an AOS/VS DUMP_II or DUMP_III file (No SOD record found).")
        exitProcess(1)
    }

    val rev = readWord(d)
    val secs = readWord(d)
    val mins = readWord(d)
    val hrs = readWord(d)
    val day = readWord(d)
    val mnth = readWord(d)
    val year = readWord(d)

    return SOD(hdr, rev, secs, mins, hrs, day, mnth, year)
}

enum class FSTATentryType(val id: dgByte) {
    FLNK(0U),
    FDIR(12U),
    FDMP(64U), // guessed symbol
    FSTF(67U),
    FTXT(68U),
    FPRV(74U),
    FPRG(87U);

    companion object {
        private val map = values().associateBy( FSTATentryType::id)
        fun fromByte(fe: dgByte) = map[fe] ?: throw IllegalArgumentException()
    }
}

fun processNameBlock(recHeader: RecordHeader, fsbBlob: ByteArray, d: BufferedInputStream ): String {
    val fileType: String
    val nameBytes: ByteArray = readBlob(recHeader.recordLength, d, "file name")
    val fileName = nameBytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
    if (summary and verbose) println()
    val entryType: dgByte = fsbBlob[1].toUByte()
    when (entryType) {
        FSTATentryType.FLNK.id -> {
            fileType = "=>Link=>"
            loadIt = false
        }
        FSTATentryType.FDIR.id -> {
            fileType = "<Directory>"
            workingDir += separator + fileName
            if (extract) {
                val dirPath = File(workingDir)
                if (!dirPath.mkdirs()) {
                    println("ERROR: Could not create directory <$workingDir>")
                    if (!ignoreErrors) {
                        println("Giving up.")
                        exitProcess(1)
                    }
                }
            }
            loadIt = false
        }
        FSTATentryType.FSTF.id -> {
            fileType = "Symbol Table"
            loadIt = true
        }
        FSTATentryType.FTXT.id -> {
            fileType = "Text File"
            loadIt = true
        }
        FSTATentryType.FPRG.id, FSTATentryType.FPRV.id -> {
            fileType = "Program File"
            loadIt = true
        }
        else -> {
            // we don't explicitly recognise the type
            // TODO: get definitive list from paru.32.sr
            fileType = "File"
            loadIt = true
        }
    }
    if (summary) {
        val displayPath = if (workingDir.isEmpty()) fileName else File(workingDir).resolve(fileName).toString()
        print("%-12s: ".format(fileType) + "%-48s".format(displayPath))
        if (verbose || entryType == FSTATentryType.FDIR.id) println()
        else print("\t")
    }

    if (extract && loadIt) {
        val writePath = if (workingDir.isEmpty()) fileName else workingDir + separator + fileName
        if (verbose) println(" Creating file: $writePath")
        try {
            writeFile = FileOutputStream(writePath)
        } catch (e: java.lang.Exception) {
            println("ERROR: Could not create file <$writePath> due to ${e.message}")
            if (!ignoreErrors) {
                println("Giving up.")
                exitProcess(1)
            }
        }
    }

    return fileName
}

data class DataHeader (
    val header: RecordHeader,
    val byteAddress: dgDword,
    val byteLength: dgDword,
    val alignmentCount: dgWord
)

fun processDataBlock(recHeader: RecordHeader, d: BufferedInputStream ) {
    val baBytes = readBlob(4, d, "byte address")
    val ba: dgDword = baBytes[0].toUInt().shl(24) or
            (baBytes[1].toUInt().shl(16) and 0x00ff0000U) or
            (baBytes[2].toUInt().shl(8) and 0x0000ff00U) or
            (baBytes[3].toUInt() and 0x000000ffU)
    val blBytes = readBlob(4, d, "byte length")

    val bl: dgDword = blBytes[0].toUInt().shl(24) or
            (blBytes[1].toUInt().shl(16) and 0x00ff0000U) or
            (blBytes[2].toUInt().shl(8) and 0x0000ff00U) or
            (blBytes[3].toUInt() and 0x000000ffU)

    val alnBytes = readBlob(2, d, "alignment count")
    val ac: dgWord = (alnBytes[0].toUInt().shl(8) or alnBytes[1].toUInt()).toUShort()

    val dhb = DataHeader(recHeader, ba, bl, ac)

    if (verbose) println(" Data Block: ${dhb.byteLength} (bytes)")

    // skip any alignment bytes - usually zero or one
    if (ac > 0u) {
        if (verbose) println("  Skipping ${dhb.alignmentCount} alignment byte(s)")
        readBlob(ac.toInt(), d, "alignment byte(s)")
    }

    val dataBlob = readBlob(dhb.byteLength.toInt(), d, "data blob")

    if (extract) {
        // large areas of NULLs may be skipped over by DUMP_II/III
        // this is achieved by simply advancing the block address so
        // we must pad out if block address is beyond end of last block
        if (dhb.byteAddress.toInt() > totalFileSize+ 1) {
            val paddingSize = dhb.byteAddress.toInt() - totalFileSize
            val paddingBlocks = paddingSize / DISK_BLOCK_BYTES
            val paddingBlock = ByteArray(DISK_BLOCK_BYTES)
            for (p in 1 .. paddingBlocks) {
                if (verbose) println("  Padding with one block")
                try {
                    writeFile.write(paddingBlock)
                    totalFileSize += DISK_BLOCK_BYTES
                } catch (e: Exception) {
                    println("ERROR: Could not write padding block due to ${e.message}")
                    exitProcess(1)
                }
            }
        }
        try {
            writeFile.write(dataBlob)
        } catch (e: Exception) {
            println("ERROR: Count not write data to file due to ${e.message}")
            exitProcess(1)
        }
    }

    totalFileSize += dhb.byteLength.toInt()
    inFile = true
}

fun processEndBlock() {
    if (inFile) {
        if (extract && loadIt) writeFile.close()
        if (summary) println(" %12d bytes".format(totalFileSize))
        totalFileSize = 0
        inFile = false
    } else {
        // don't move above start dir for safety...
        if (workingDir != baseDir) workingDir = Paths.get(workingDir).parent.toString()
        if (verbose) println(" Popped dir - new dir is: $workingDir")
    }
    if (verbose) println("End Block Processed")
}

fun processLink(recHeader: RecordHeader, linkName: String, d: BufferedInputStream) {
    val linkTargetBA = readBlob(recHeader.recordLength, d, "Link Target").dropLastWhile{it == 0.toByte()}.toByteArray()
    var link = linkName
    var linkTarget = linkTargetBA.toString(Charsets.US_ASCII)
    // convert AOS/VS : directory separators to platform-specific ones and ensure upper case
    linkTarget = linkTarget.replace(":", separator).toUpperCase()
    if (summary || verbose) println(" -> Link Target: $linkTarget")
    if (extract) {
        val targetName: String
        if (workingDir.isEmpty()) {
            targetName = linkTarget
        } else {
            targetName = workingDir + separator + linkTarget
            link  = workingDir + separator + linkName
        }
        val linkPath = Paths.get(link)
        val targetPath = Paths.get(targetName)
        try {
            Files.createSymbolicLink(linkPath, targetPath)
        } catch (e: java.lang.Exception) {
            println("ERROR: Could not create symbolic link")
            if (!ignoreErrors) {
                println("Giving up.")
                exitProcess(1)
            }
        }
    }
}