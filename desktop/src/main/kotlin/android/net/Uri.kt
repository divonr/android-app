package android.net

/**
 * Desktop shim for android.net.Uri.
 * On desktop, files are referenced by path strings rather than Android URIs.
 */
class Uri private constructor(val path: String) {
    override fun toString(): String = path

    companion object {
        fun parse(uriString: String): Uri = Uri(uriString)
        fun fromFile(file: java.io.File): Uri = Uri(file.toURI().toString())

        val EMPTY: Uri = Uri("")
    }
}
