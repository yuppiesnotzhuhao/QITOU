package app.qitou.bridge

object LogRedactor {
    fun redact(input: String): String {
        var text = input
        text = text.replace(
            Regex("""(?i)(pwd|password|passphrase|psk)\s*[=:]\s*([^&#\s]+)"""),
            "$1=***",
        )
        text = text.replace(
            Regex("""(?i)(Wifi=)[^&#\s]+#([^&#\s]+)#"""),
            "$1***#***#",
        )
        return text
    }
}
