package MailAggregator.MailAggregator.spreadsheet.config

import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SpreadsheetConfig {
    @Bean
    fun getSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase
    ) = GetSpendingsByDateUseCase(
        sheetRequester,
        sheetId,
        verifyMonthSheetExistsUseCase
    )

    @Bean
    fun updateSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase
    ) = UpdateSpendingsByDateUseCase(
        sheetRequester,
        sheetId,
        verifyMonthSheetExistsUseCase = verifyMonthSheetExistsUseCase
    )

    @Bean
    fun prepareTemplateSpreadsheetUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String
    ) = VerifyMonthSheetExistsUseCase(
        sheetRequester,
        sheetId
    )

    @Bean
    fun sheetRequester(authentication: Authentication) = SheetRequester(authentication)

    @Bean
    fun authentication() = Authentication()
}