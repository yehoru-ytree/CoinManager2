package MailAggregator.MailAggregator.common

enum class Category(
    val index: Int,
    val displayName: String,
) {
    FLAT(0, "Квартира"),
    UTILITIES(1, "Комуналка"),
    GROCERIES(2, "Еда"),
    FUEL(3, "Бензин"),
    CAR_REPAIR(4, "Ремонт авто"),
    TRANSIT_TAXI(5, "Маршрутка/такси"),
    CAFE_DELIVERY(6, "Кафе/Доставка"),
    LEISURE(7, "Досуг"),
    CLOTHING(8, "Одежда"),
    HOUSEHOLD_GOODS(9, "Хоз.товары"),
    POKER(10, "Покер"),
    GYM(11, "КОчалка"),
    HEALTH(12, "Здоровье"),
    RESERVATION(13, "Бронь"),
    DONATIONS(14, "Донаты"),
    BEAUTY(15, "Красота"),
    SAVINGS(16, "Отложили"),
    UNIVERSITY(17, "Универ"),
    WORK_LUNCH(18, "Пожрать на работе"),
    CREDIT_CARD(19, "Кредитка"),
    PHONE_INTERNET(20, "Телефон/Интернет"),
    OTHER(21, "Прочее"),
    SUBSCRIPTIONS(22, "Подписки");

    companion object {
        private val byDisplay = entries.associateBy { it.displayName.lowercase() }
        private val byIndex = entries.associateBy { it.index }

        fun fromDisplayName(name: String): Category? =
            byDisplay[name.trim().lowercase()]

        fun fromIndex(idx: Int): Category =
            byIndex[idx]?: OTHER
    }
}
