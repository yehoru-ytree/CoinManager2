package MailAggregator.MailAggregator.spreadsheet.util

object ExcelUtil {
    private fun excelColumnName(index: Int): String {
        require(index >= 1) { "Index must be >= 1 (1-based)" }
        var i = index
        val sb = StringBuilder()
        while (i > 0) {
            val rem = (i - 1) % 26
            sb.append(('A'.code + rem).toChar())
            i = (i - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** 0-based индекс -> имя столбца (0 -> A, 25 -> Z, 26 -> AA) */
    fun toColumnName(index: Int): String {
        require(index >= 0) { "Index must be >= 0 (0-based)" }
        return excelColumnName(index + 1)
    }

    fun cellDAtaToDouble(cellData: String): Double {
        if (cellData.isEmpty()) return 0.0
        else if (cellData.contains("=")){
            return cellData.split("=")[1].split("+").sumOf { it.toDouble() }
        }
        return cellData.toDouble()
    }
}