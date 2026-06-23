package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.repository.BankAccountRepository
import MailAggregator.MailAggregator.household.BotUser
import java.util.UUID

class AddBankAccountUseCase(
    private val bankAccountRepository: BankAccountRepository,
) {
    fun add(
        user: BotUser,
        bankType: BankType,
        token: String,
        accountId: String,
        clientId: String? = null,
    ): BankAccount =
        bankAccountRepository.insert(
            BankAccount(
                id = UUID.randomUUID(),
                userId = user.id,
                bankType = bankType,
                token = token,
                accountId = accountId,
                clientId = clientId,
            ),
        )

    // Privat onboarding via email-forwarding is per-user (not per-card): one alias suffix
    // catches all of the user's Privat cards. Looking up the existing row lets the bot show the
    // same suffix again if the user re-runs «Привязать карту → PrivatBank» (lost the message,
    // wants to re-read setup instructions).
    fun findFirstPrivatForUser(user: BotUser): BankAccount? =
        bankAccountRepository.findAllByUser(user.id).firstOrNull { it.bankType == BankType.PRIVATBANK }
}
