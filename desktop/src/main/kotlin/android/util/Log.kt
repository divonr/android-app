package android.util

object Log {
    fun d(tag: String, message: String): Int = print("DEBUG", tag, message, null)
    fun i(tag: String, message: String): Int = print("INFO", tag, message, null)
    fun w(tag: String, message: String): Int = print("WARN", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable?): Int = print("WARN", tag, message, throwable)
    fun e(tag: String, message: String): Int = print("ERROR", tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable?): Int = print("ERROR", tag, message, throwable)

    private fun print(level: String, tag: String, message: String, throwable: Throwable?): Int {
        val line = "[$level][$tag] $message"
        if (level == "ERROR") {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        } else {
            println(line)
            throwable?.printStackTrace()
        }
        return 0
    }
}
