package MailAggregator.MailAggregator.household.usecase

import MailAggregator.MailAggregator.bank.BankAccount
import MailAggregator.MailAggregator.bank.BankType
import MailAggregator.MailAggregator.bank.repository.BankAccountRepository
import MailAggregator.MailAggregator.household.BotUser
import java.util.UUID

class AddBankAccountUseCase(
    private val bankAccountRepository: BankAccountRepository,
) {
    fun add(user: BotUser, bankType: BankType, token: String, accountId: String): BankAccount =
        bankAccountRepository.insert(
            BankAccount(
                id = UUID.randomUUID(),
                userId = user.id,
                bankType = bankType,
                token = token,
                accountId = accountId,
            ),
        )
}
