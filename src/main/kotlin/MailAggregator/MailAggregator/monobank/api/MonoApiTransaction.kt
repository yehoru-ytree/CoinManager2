package MailAggregator.MailAggregator.monobank.api

data class MonoApiTransaction(
    val id: String,
    val time: Long,               // Unix seconds
    val description: String,
    val mcc: Int,
    val originalMcc: Int,
    val hold: Boolean,
    val amount: Long,             // minor units
    val operationAmount: Long,    // minor units
    val currencyCode: Int,        // ISO 4217
    val commissionRate: Long,     // minor units
    val cashbackAmount: Long,     // minor units
    val balance: Long,            // minor units

    // опциональные (могут отсутствовать в JSON)
    val comment: String?,
    val receiptId: String?,
    val invoiceId: String?,
    val counterEdrpou: String?,
    val counterIban: String?,
    val counterName: String?
)

typealias MonoStatement = List<MonoApiTransaction>

