package MailAggregator.MailAggregator.spreadsheet.config

import MailAggregator.MailAggregator.common.repository.CategoryRepository
import MailAggregator.MailAggregator.common.repository.MonthCategoryLayoutRepository
import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.spreadsheet.usecase.VerifyMonthSheetExistsUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.GetSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.usecases.UpdateSpendingsByDateUseCase
import MailAggregator.MailAggregator.spreadsheet.util.SheetRequester
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SpreadsheetConfig {
    @Bean
    fun getSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
        categoryRepository: CategoryRepository,
        monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
    ) = GetSpendingsByDateUseCase(
        sheetRequester,
        verifyMonthSheetExistsUseCase,
        categoryRepository,
        monthCategoryLayoutRepository,
    )

    @Bean
    fun updateSpendingsByDateUseCase(
        sheetRequester: SheetRequester,
        verifyMonthSheetExistsUseCase: VerifyMonthSheetExistsUseCase,
        categoryRepository: CategoryRepository,
        monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
    ) = UpdateSpendingsByDateUseCase(
        sheetRequester = sheetRequester,
        verifyMonthSheetExistsUseCase = verifyMonthSheetExistsUseCase,
        categoryRepository = categoryRepository,
        monthCategoryLayoutRepository = monthCategoryLayoutRepository,
    )

    @Bean
    fun prepareTemplateSpreadsheetUseCase(
        sheetRequester: SheetRequester,
        categoryRepository: CategoryRepository,
        monthCategoryLayoutRepository: MonthCategoryLayoutRepository,
    ) = VerifyMonthSheetExistsUseCase(
        sheetRequester = sheetRequester,
        categoryRepository = categoryRepository,
        monthCategoryLayoutRepository = monthCategoryLayoutRepository,
    )

    @Bean
    fun sheetRequester(authentication: Authentication) = SheetRequester(authentication)

    @Bean
    fun authentication() = Authentication()
}
