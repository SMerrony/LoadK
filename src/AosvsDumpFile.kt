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

// import org.junit.jupiter.api.Assertions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

typealias dgWord = UShort
typealias dgDword = UInt

const val DISK_BLOCK_BYTES = 512

/**
 * A representation of the AOS/VS (and other) DUMP_II and DUMP_III format.
 */
class AosvsDumpFile(dumpFileStream: BufferedInputStream) {

    private val dumpStream = dumpFileStream
    private var loadIt = false
    private var inFile = false
    private var baseDir = ""
    private var workingDir = ""
    private val separator: String = File.separator
    private lateinit var writeFile: FileOutputStream
    private var totalFileSize = 0
    private val knownEntryTypes = knownFstatEntryTypes()

    private enum class RecordType(val id: Int) {
        START(0),
        FSB(1),
        NB(2),
        UDA(3),
        ACL(4),
        LINK(5),
        START_BLOCK(6),
        DATA_BLOCK(7),
        END_BLOCK(8),
        END(9),
        Unknown(99);  // This dummy value is returned when an unknown type is encountered.

        companion object {
            private val map = values().associateBy( RecordType::id )
            fun fromInt(rt: Int) = map[rt] ?: Unknown
        }
    }

    private data class RecordHeader (
        val recordType: RecordType,
        val recordLength: Int
    )

    private fun readHeader(): RecordHeader {
        val twoBytes: ByteArray = readBlob( 2, "header")
        val rt = twoBytes[0].toInt().shr(2) and 0x00FF
        val rl = (twoBytes[0].toInt() and 0x03).shl(8) + twoBytes[1].toInt()
        return RecordHeader(RecordType.fromInt(rt), rl)
    }

    private data class SOD (
        val header: RecordHeader,
        val dumpFormatRevision: dgWord,
        val dumpTimeSecs: dgWord,
        val dumpTimeMins: dgWord,
        val dumpTimeHours: dgWord,
        val dumpTimeDay: dgWord,
        val dumpTimeMonth: dgWord,
        val dumpTimeYear: dgWord
    )

    private fun readSOD(): SOD {
        val hdr = readHeader()
        if (hdr.recordType != RecordType.START) {
            println("ERROR: This does not appear to be an AOS/VS DUMP_II or DUMP_III file (No SOD record found).")
            exitProcess(1)
        }
        val rev = readWord()
        val secs = readWord()
        val mins = readWord()
        val hrs = readWord()
        val day = readWord()
        val mnth = readWord()
        val year = readWord()
        return SOD(hdr, rev, secs, mins, hrs, day, mnth, year)
    }
    private data class FstatEntryType (
        val dgMnemonic: String,
        val desc: String,
        val isDir: Boolean,
        val hasPayload: Boolean )

    private fun knownFstatEntryTypes(): Map<Int, FstatEntryType> {
        return mapOf(
            0 to FstatEntryType(dgMnemonic = "FLNK", desc = "=>Link=>", isDir = false, hasPayload = false),
            1 to FstatEntryType(dgMnemonic = "FDSF", desc = "System Data File", isDir = false, hasPayload = true),
            2 to FstatEntryType(dgMnemonic = "FMTF", desc = "Mag Tape File", isDir = false, hasPayload = true),
            3 to FstatEntryType(dgMnemonic = "FGFN", desc = "Generic File", isDir = false, hasPayload = true),
            10 to FstatEntryType(dgMnemonic = "FDIR", desc = "<Directory>", isDir = true, hasPayload = false),
            11 to FstatEntryType(dgMnemonic = "FLDU", desc = "<LDU Directory>", isDir = true, hasPayload = false),
            12 to FstatEntryType(dgMnemonic = "FCPD", desc = "<Control Point Dir>", isDir = true, hasPayload = false),
            64 to FstatEntryType(dgMnemonic = "FUDF", desc = "User Data File", isDir = false, hasPayload = true),
            66 to FstatEntryType(dgMnemonic = "FUPD", desc = "User Profile", isDir = false, hasPayload = true),
            67 to FstatEntryType(dgMnemonic = "FSTF", desc = "Symbol Table", isDir = false, hasPayload = true),
            68 to FstatEntryType(dgMnemonic = "FTXT", desc = "Text File", isDir = false, hasPayload = true),
            69 to FstatEntryType(dgMnemonic = "FLOG", desc = "System Log File", isDir = false, hasPayload = true),
            74 to FstatEntryType(dgMnemonic = "FPRV", desc = "Program File", isDir = false, hasPayload = true),
            87 to FstatEntryType(dgMnemonic = "FPRG", desc = "Program File", isDir = false, hasPayload = true)
        )
    }

    private fun processNameBlock(recHeader: RecordHeader, fsbBlob: ByteArray, summary: Boolean, verbose: Boolean, extract: Boolean, ignoreErrors: Boolean): String {
        var fileType: String
        val nameBytes: ByteArray = readBlob(recHeader.recordLength, "file name")
        val fileName = nameBytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
        if (summary and verbose) println()
        val thisEntry: FstatEntryType? = knownEntryTypes[fsbBlob[1].toInt()]
        if (thisEntry == null) {
            fileType = "Unknown File"
            loadIt = true
        } else {
            fileType = thisEntry.desc
            loadIt = thisEntry.hasPayload
            if (thisEntry.isDir) {
                workingDir += separator + fileName
                if (extract) {
                    if (!File(workingDir).mkdirs()) {
                        println("ERROR: Could not create directory <$workingDir>")
                        if (!ignoreErrors) {
                            println("Giving up.")
                            exitProcess(1)
                        }
                    }
                }
            }
        }

        if (summary) {
            val displayPath = if (workingDir.isEmpty()) fileName else File(workingDir).resolve(fileName).toString()
            print("%-20s: ".format(fileType) + "%-48s".format(displayPath))
            if (verbose || (thisEntry !=null && thisEntry.isDir)) println()
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

    private data class DataHeader (
        val header: RecordHeader,
        val byteAddress: dgDword,
        val byteLength: dgDword,
        val alignmentCount: dgWord
    )

    private fun processDataBlock(recHeader: RecordHeader, verbose: Boolean, extract: Boolean ) {
        val baBytes = readBlob(4, "byte address")
        val ba: dgDword = baBytes[0].toUInt().shl(24) or
                (baBytes[1].toUInt().shl(16) and 0x00ff0000U) or
                (baBytes[2].toUInt().shl(8) and 0x0000ff00U) or
                (baBytes[3].toUInt() and 0x000000ffU)

        val blBytes = readBlob(4, "byte length")
        val bl: dgDword = blBytes[0].toUInt().shl(24) or
                (blBytes[1].toUInt().shl(16) and 0x00ff0000U) or
                (blBytes[2].toUInt().shl(8) and 0x0000ff00U) or
                (blBytes[3].toUInt() and 0x000000ffU)

        val alnBytes = readBlob(2, "alignment count")
        val ac: dgWord = (alnBytes[0].toUInt().shl(8) or alnBytes[1].toUInt()).toUShort()

        val dhb = DataHeader(recHeader, ba, bl, ac)

        if (verbose) println(" Data Block: ${dhb.byteLength} (bytes)")

        // skip any alignment bytes - usually zero or one
        if (ac > 0u) {
            if (verbose) println("  Skipping ${dhb.alignmentCount} alignment byte(s)")
            readBlob(ac.toInt(), "alignment byte(s)")
        }

        if (extract) {
            // large areas of NULLs may be skipped over by DUMP_II/III
            // this is achieved by simply advancing the block address so
            // we must pad out if block address is beyond end of last block
            if (dhb.byteAddress.toInt() > totalFileSize + 1) {
                val paddingSize = dhb.byteAddress.toInt() - totalFileSize
                val paddingBlocks = paddingSize / DISK_BLOCK_BYTES
                val paddingBlock = ByteArray(DISK_BLOCK_BYTES)
                for (p in 1..paddingBlocks) {
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
                writeFile.write(readBlob(dhb.byteLength.toInt(), "data blob"))
            } catch (e: Exception) {
                println("ERROR: Count not write data to file due to ${e.message}")
                exitProcess(1)
            }
        }
        totalFileSize += dhb.byteLength.toInt()
        inFile = true
    }

    private fun processEndBlock(verbose: Boolean, extract: Boolean, summary: Boolean) {
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

    private fun processLink(recHeader: RecordHeader, linkName: String, verbose: Boolean, extract: Boolean, summary: Boolean, ignoreErrors: Boolean ) {
        val linkTargetBA = readBlob(recHeader.recordLength, "Link Target").dropLastWhile{it == 0.toByte()}.toByteArray()
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

    public fun parse(extract: Boolean, ignoreErrors: Boolean, list: Boolean, summary: Boolean, verbose: Boolean, baseDir: String) {
        this.baseDir = baseDir
        workingDir = baseDir
        var fsbBlob = ByteArray(0)
        var fileName = ""

        // there should always be a SOD record...
        val sod = readSOD()
        if (summary or verbose) {
            println("AOS/VS DUMP version  : ${sod.dumpFormatRevision}")
            println("DUMP date (y-m-d)    : ${sod.dumpTimeYear}-${sod.dumpTimeMonth}-${sod.dumpTimeDay}")
            println("DUMP time (hh:mm:ss) : ${sod.dumpTimeHours}:${sod.dumpTimeMins}:${sod.dumpTimeSecs}")
        }

        // now work through the dump examining each block type and handling accordingly...
        var done = false
        while (!done) {
            val recHdr = readHeader()
            if (verbose) {
                println("Found block of type: " + recHdr.recordType.name + " length: ${recHdr.recordLength}")
            }
            when (recHdr.recordType) {
                RecordType.START -> {
                    println("ERROR: Another START record found in DUMP - this should not happen.")
                    exitProcess(1)
                }
                RecordType.FSB -> {
                    fsbBlob = readBlob(recHdr.recordLength, "FSB" )
                    loadIt = false
                }
                RecordType.NB -> {
                    fileName = processNameBlock( recHdr, fsbBlob, summary, verbose, extract, ignoreErrors )
                }
                RecordType.UDA -> {
                    // throw away for now
                    readBlob(recHdr.recordLength, "UDA")
                }
                RecordType.ACL -> {
                    val aclBlob =  readBlob(recHdr.recordLength, "ACL")
                    if (verbose) println(" ACL: " + aclBlob.toString(Charsets.US_ASCII).trimEnd('\u0000'))
                }
                RecordType.LINK -> {
                    processLink(recHdr, fileName, verbose, extract, summary, ignoreErrors)
                }
                RecordType.START_BLOCK -> {
                    // nothing to do - it's just a recHdr
                }
                RecordType.DATA_BLOCK -> {
                    processDataBlock(recHdr, verbose, extract)
                }
                RecordType.END_BLOCK -> {
                    processEndBlock(verbose, extract, summary)
                }
                RecordType.END -> {
                    println("=== End of Dump ===")
                    done = true
                }
                RecordType.Unknown -> {
                    println("ERROR: Unknown block type in DUMP file.  Giving up.")
                    exitProcess(1)
                }
            }
        }
    }

    // helper functions...

    private fun readBlob(len: Int, desc: String): ByteArray {
        val blob = ByteArray(len)
        try {
            val n = dumpStream.read(blob)
            check( n == len) {"Did not get expected number of bytes"}
        } catch (e: Exception) {
            println("ERROR: Could not read $desc record - ${e.message}")
            exitProcess(1)
        }
        return blob
    }

    private fun readWord(): dgWord {
        val twoBytes: ByteArray = readBlob( 2, "DG Word")
        return (twoBytes[0].toUInt().shl(8)).toUShort().or(twoBytes[1].toUShort())
    }

//    private class AosvsDumpFileTest {
//        @org.junit.jupiter.api.Test
//        fun getKnownEntryTypes() {
//            val kfet = super.knownFstatEntryTypes()
//            val fmtf: FstatEntryType? = kfet[2]
//            Assertions.assertEquals("FMTF", fmtf!!.dgMnemonic)
//        }
//    }
}