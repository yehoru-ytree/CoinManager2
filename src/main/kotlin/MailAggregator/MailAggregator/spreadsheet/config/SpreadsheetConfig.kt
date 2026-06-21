package MailAggregator.MailAggregator.spreadsheet.config

import MailAggregator.MailAggregator.common.repository.CategoryRepository
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
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
        categoryRepository: CategoryRepository,
    ) = GetSpendingsByDateUseCase(
        sheetRequester,
        sheetId,
        verifyMonthSheetExistsUseCase,
        categoryRepository,
    )

    @Bean
    fun updateSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
        categoryRepository: CategoryRepository,
    ) = UpdateSpendingsByDateUseCase(
        sheetRequester,
        sheetId,
        verifyMonthSheetExistsUseCase = verifyMonthSheetExistsUseCase,
        categoryRepository = categoryRepository,
    )

    @Bean
    fun prepareTemplateSpreadsheetUseCase(
        sheetRequester: SheetRequester,
        @Value("\${google.sheet-id}") sheetId: String,
        @Value("\${google.template-sheet-title}") templateSheetTitle: String,
    ) = VerifyMonthSheetExistsUseCase(
        sheetRequester,
        sheetId,
        templateSheetTitle,
    )

    @Bean
    fun sheetRequester(authentication: Authentication) = SheetRequester(authentication)

    @Bean
    fun authentication() = Authentication()
}