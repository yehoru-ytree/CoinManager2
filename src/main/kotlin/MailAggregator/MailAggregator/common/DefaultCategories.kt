package MailAggregator.MailAggregator.common

/**
 * Seed list of categories applied to every newly created household. Mirrors `V3__CategoryTable.sql`
 * (the original single-tenant seed) so that yehor's household and new households start from the
 * same set. Keep this list and V3 in sync — V3 seeded the bootstrap household once; this seeds
 * every household created via the Telegram flow.
 */
object DefaultCategories {
    data class Seed(
        val name: String,
        val displayName: String,
        val sheetRow: Int,
        val priority: Int,
        val keywords: List<String>,
        val isOther: Boolean = false,
    )

    val ALL: List<Seed> = listOf(
        Seed("FLAT", "Квартира", 0, 70, listOf("аренда", "оренда", "квартира", "rent", "landlord")),
        Seed("UTILITIES", "Комуналка", 1, 75, listOf("коммун", "комун", "свет", "електро", "газ", "вода", "тепло", "осбб", "жек")),
        Seed("GROCERIES", "Еда", 2, 85, listOf("\\batb\\b", "атб", "\\bsilpo\\b", "сильпо", "\\bvarus\\b", "варус", "\\bauchan\\b", "ашан", "\\bmetro\\b", "супермаркет", "продукт")),
        Seed("FUEL", "Бензин", 3, 90, listOf("\\bokko\\b", "\\bwog\\b", "\\bsocar\\b", "\\bbrsm\\b", "азс", "бензин", "дизель", "БРСМ-Нафта")),
        Seed("CAR_REPAIR", "Ремонт авто", 4, 80, listOf("\\bsto\\b", "сто", "шиномонтаж", "развал", "запчаст", "сервис", "ремонт авто", "масло", "аккумулятор", "Anzhela O.", "MAGAZYN PAROM", "Ілона Р.")),
        Seed("TRANSIT_TAXI", "Маршрутка/такси", 5, 95, listOf("\\buber\\b", "\\bbolt\\b", "\\buklon\\b", "\\btaxi\\b", "такси", "Громадський транспорт")),
        Seed("CAFE_DELIVERY", "Кафе/Доставка", 6, 92, listOf("\\bglovo\\b", "\\bbolt food\\b", "\\brocket\\b", "доставка", "кафе", "ресторан", "Булочник")),
        Seed("LEISURE", "Досуг", 7, 45, listOf("steam", "psn", "xbox", "кино", "cinema", "bar", "club", "Akvarel")),
        Seed("CLOTHING", "Одежда", 8, 50, listOf("одежд", "обув", "clothing", "zara", "hm", "h&m")),
        Seed("HOUSEHOLD_GOODS", "Хоз.товары", 9, 55, listOf("epicentr", "эпицентр", "ikea", "хоз", "быт", "household", "PROSTOR", "Rozetka", "Zooalliance", "Аврора")),
        Seed("POKER", "Покер", 10, 60, listOf("poker", "ggpoker", "pokerstars", "partypoker")),
        Seed("GYM", "КОчалка", 11, 60, listOf("gym", "fitness", "спортзал", "качалк")),
        Seed("HEALTH", "Здоровье", 12, 65, listOf("аптека", "pharmacy", "стомат", "клиника", "doctor", "анализ", "мед")),
        Seed("RESERVATION", "Бронь", 13, 30, listOf("booking", "airbnb", "бронь", "депозит")),
        Seed("DONATIONS", "Донаты", 14, 30, listOf("Поповнення", "донат", "пожертв", "donation")),
        Seed("BEAUTY", "Красота", 15, 30, listOf("салон", "парикмахер", "маникюр", "космет", "talalai")),
        Seed("SAVINGS", "Отложили", 16, 30, listOf("накоп", "сбереж", "savings")),
        Seed("UNIVERSITY", "Универ", 17, 30, listOf("university", "универ", "оплата обуч", "tuition")),
        Seed("WORK_LUNCH", "Пожрать на работе", 18, 25, listOf("столов", "canteen", "обед на работе")),
        Seed("CREDIT_CARD", "Кредитка", 19, 35, listOf("погашени.*кредит", "credit card", "минимальн.*платеж", "проценты")),
        Seed("PHONE_INTERNET", "Телефон/Интернет", 20, 40, listOf("kyivstar", "киевстар", "vodafone", "lifecell", "интернет", "internet", "isp", "пополнени.*тел")),
        Seed("OTHER", "Прочее", 21, 0, emptyList(), isOther = true),
        Seed("SUBSCRIPTIONS", "Подписки", 22, 100, listOf("\\bnetflix\\b", "\\bspotify\\b", "\\byoutube premium\\b", "\\bicloud\\b", "\\bgoogle one\\b", "\\bpatreon\\b")),
    )
}
