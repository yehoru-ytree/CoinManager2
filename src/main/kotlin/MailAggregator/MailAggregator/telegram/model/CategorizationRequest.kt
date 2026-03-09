package MailAggregator.MailAggregator.telegram.model

class CategorizationRequest(
    val transactionId: String,
    val transactionTime: String, // Human-read time, e.g. "2024-06-01T12:00:00Z"
    val description: String, //Нова пошта
    val amount: String, // 565.78 ₴
)