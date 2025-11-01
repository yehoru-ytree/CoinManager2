package MailAggregator.MailAggregator.common

enum class Month(
    val index: Int,
    val displayName: String,
) {
    JANUARY(1, "Январь"),
    FEBRUARY(2, "Февраль"),
    MARCH(3, "Март"),
    APRIL(4, "Апрель"),
    MAY(5, "Май"),
    JUNE(6, "Июнь"),
    JULY(7, "Июль"),
    AUGUST(8, "Август"),
    SEPTEMBER(9, "Сентябрь"),
    OCTOBER(10, "Октябрь"),
    NOVEMBER(11, "Ноябрь"),
    DECEMBER(12, "Декабрь");

    companion object {
        private val byDisplay = entries.associateBy { it.displayName.lowercase() }
        private val byIndex = entries.associateBy { it.index }

        fun fromDisplayName(name: String): Month? =
            byDisplay[name.trim().lowercase()]

        fun fromIndex(idx: Int): Month =
            byIndex[idx] ?: JANUARY
    }
}
