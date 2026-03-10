package MailAggregator.MailAggregator.common.usecases

import MailAggregator.MailAggregator.common.Category
import MailAggregator.MailAggregator.monobank.application.MonoTransaction
import kotlin.math.abs

class CategorizeExpenseUseCase {
    private companion object {
        const val UAH_MINOR: Long = 100L
        const val BRSM_FUEL_THRESHOLD_MINOR: Long = 300L * UAH_MINOR
    }

    private val rules: List<Rule> = listOf(
        rule(Category.CAFE_DELIVERY, 101,
            "БРСМ-Нафта", "\\bbrsm\\b",
            amountPredicate = { amountMinor -> amountMinor <= BRSM_FUEL_THRESHOLD_MINOR }
        ), // БРСМ-кафешка

        rule(Category.UNIVERSITY, 101,
            literal("516875****2613"),
        ), // Настя контрольные

        rule(Category.SUBSCRIPTIONS, 100,
            "\\bnetflix\\b", "\\bspotify\\b", "\\byoutube premium\\b", "\\bicloud\\b", "\\bgoogle one\\b",
            "\\bpatreon\\b"
        ),
        rule(Category.TRANSIT_TAXI, 95, "\\buber\\b", "\\bbolt\\b", "\\buklon\\b", "\\btaxi\\b", "такси", "Громадський транспорт"),
        rule(Category.CAFE_DELIVERY, 92, "\\bglovo\\b", "\\bbolt food\\b", "\\brocket\\b", "доставка", "кафе", "ресторан", "Булочник"),

        rule(Category.FUEL, 90,
            "\\bokko\\b", "\\bwog\\b", "\\bsocar\\b", "\\bbrsm\\b", "азс", "бензин", "дизель", "БРСМ-Нафта"
        ),
        rule(Category.GROCERIES, 85,
            "\\batb\\b", "атб", "\\bsilpo\\b", "сильпо", "\\bvarus\\b", "варус",
            "\\bauchan\\b", "ашан", "\\bmetro\\b", "супермаркет", "продукт"
        ),

        rule(Category.CAR_REPAIR, 80,
            "\\bsto\\b", "сто", "шиномонтаж", "развал", "запчаст", "сервис", "ремонт авто", "масло", "аккумулятор", "Anzhela O.", "MAGAZYN PAROM", "Ілона Р."
        ),

        rule(Category.UTILITIES, 75,
            "коммун", "комун", "свет", "електро", "газ", "вода", "тепло", "осбб", "жек"
        ),
        rule(Category.FLAT, 70,
            "аренда", "оренда", "квартира", "rent", "landlord"
        ),

        rule(Category.HEALTH, 65,
            "аптека", "pharmacy", "стомат", "клиника", "doctor", "анализ", "мед"
        ),
        rule(Category.GYM, 60, "gym", "fitness", "спортзал", "качалк"),
        rule(Category.POKER, 60, "poker", "ggpoker", "pokerstars", "partypoker"),

        rule(Category.HOUSEHOLD_GOODS, 55,
            "epicentr", "эпицентр", "ikea", "хоз", "быт", "household", "PROSTOR", "Rozetka", "Zooalliance", "Аврора"
        ),
        rule(Category.CLOTHING, 50, "одежд", "обув", "clothing", "zara", "hm", "h&m"),
        rule(Category.LEISURE, 45, "steam", "psn", "xbox", "кино", "cinema", "bar", "club", "Akvarel"),

        rule(Category.PHONE_INTERNET, 40,
            "kyivstar", "киевстар", "vodafone", "lifecell", "интернет", "internet", "isp", "пополнени.*тел"
        ),

        // CREDIT_CARD / SAVINGS часто лучше решать по типу операции/направлению,
        // но если в описании есть явные слова — можно
        rule(Category.CREDIT_CARD, 35, "погашени.*кредит", "credit card", "минимальн.*платеж", "проценты"),
        rule(Category.SAVINGS, 30, "накоп", "сбереж", "savings"),

        // RESERVATION / DONATIONS / BEAUTY / UNIVERSITY / WORK_LUNCH — аналогично
        rule(Category.RESERVATION, 30, "booking", "airbnb", "бронь", "депозит"),
        rule(Category.DONATIONS, 30, "Поповнення", "донат", "пожертв", "donation"),
        rule(Category.BEAUTY, 30, "салон", "парикмахер", "маникюр", "космет", "talalai"),
        rule(Category.UNIVERSITY, 30, "university", "универ", "оплата обуч", "tuition"),
        rule(Category.WORK_LUNCH, 25, "столов", "canteen", "обед на работе")
    ).sortedByDescending { it.priority }

    data class Rule(
        val category: Category,
        val priority: Int,
        val patterns: List<Regex>,
        val amountPredicate: (Long) -> Boolean = { true } // было Double
    )

    private fun rule(
        category: Category,
        priority: Int,
        vararg patterns: String,
        amountPredicate: (Long) -> Boolean = { true }
    ): Rule = Rule(
        category = category,
        priority = priority,
        patterns = patterns.map { Regex(it, RegexOption.IGNORE_CASE) },
        amountPredicate = amountPredicate
    )

    fun categorize(tx: MonoTransaction): Category {
        val raw = tx.raw

        val d = normalize(
            buildString {
                append(raw.description)
                raw.comment?.let { append(' ').append(it) }
                raw.counterName?.let { append(' ').append(it) }
            }
        )

        val amountAbsMinor: Long = abs(raw.amount)

        val matched = rules.firstOrNull { rule ->
            rule.amountPredicate(amountAbsMinor) &&
                    rule.patterns.any { it.containsMatchIn(d) }
        }

        return matched?.category ?: Category.OTHER
    }

    operator fun invoke(txs: List<MonoTransaction>): Map<String, Category> =
        txs.associate { tx -> tx.id to categorize(tx) }

    private fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun literal(s: String): String = Regex.escape(s)
}