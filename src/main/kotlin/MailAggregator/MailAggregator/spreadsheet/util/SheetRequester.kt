package MailAggregator.MailAggregator.spreadsheet.util

import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.spreadsheet.GoogleApiRequester
import com.google.api.services.sheets.v4.model.Spreadsheet

class SheetRequester(
    val authentication: Authentication
) {
    fun getSpreadSheetByRange(sheetId: String, range: String, sheetName: String): Spreadsheet {
        val sheet = sheets()[sheetId]
        sheet.includeGridData = true
        sheet.ranges = listOf("'$sheetName'!$range")

        return GoogleApiRequester.sendRequest {
            sheet.execute()
        }
    }

    private fun sheets() = authentication.authenticate().spreadsheets()

    fun updateTableRange(spreadsheetId: String, rangeName: String, rangeContent: List<List<Any>>) {
        val valueRange = GoogleApiRequester.sendRequest {
            sheets().values()[spreadsheetId, rangeName].execute()
        }

        valueRange.setValues(rangeContent)

        GoogleApiRequester.sendRequest {
            sheets()
                .values()
                .update(
                    spreadsheetId,
                    valueRange.range,
                    valueRange,
                )
                .setValueInputOption("USER_ENTERED")
                .execute()
        }
    }
}