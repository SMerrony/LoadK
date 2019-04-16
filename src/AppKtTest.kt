import org.junit.jupiter.api.Assertions.*

internal class AppKtTest {

    @org.junit.jupiter.api.Test
    fun getKnownEntryTypes() {
        val kfet = knownFtstatEntryTypes()
        val fmtf: FstatEntryType? = kfet[2]
        assertEquals("FMTF", fmtf!!.dgMnemonic)
    }
}