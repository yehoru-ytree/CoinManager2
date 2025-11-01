package MailAggregator.MailAggregator.spreadsheet.config

import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Config {
    @Bean
    fun getSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String
    ) = GetSpendingsByDateUseCase(
        sheetRequester,
        sheetId
    )

    @Bean
    fun updateSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String
    ) = UpdateSpendingsByDateUseCase(
        sheetRequester,
        sheetId
    )

    @Bean
    fun sheetRequester(authentication: Authentication) = SheetRequester(authentication)

    @Bean
    fun authentication() = Authentication()
}