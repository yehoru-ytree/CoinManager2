package MailAggregator.MailAggregator.spreadsheet.util

import MailAggregator.MailAggregator.spreadsheet.Authentication
import MailAggregator.MailAggregator.spreadsheet.GoogleApiRequester
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.DuplicateSheetRequest
import com.google.api.services.sheets.v4.model.GridProperties
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.Sheet
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.Spreadsheet
import com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest

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

    fun sheetExists(spreadsheetId: String, sheetTitle: String): Boolean {
        val spreadsheet = GoogleApiRequester.sendRequest {
            sheets()
                .get(spreadsheetId)
                .execute()
        }

        return spreadsheet.sheets
            ?.any { it.properties?.title == sheetTitle }
            ?: false
    }

    fun createSheet(spreadsheetId: String, sheetTitle: String) {
        val addSheetRequest = AddSheetRequest().setProperties(
            SheetProperties().setTitle(sheetTitle)
        )

        val batchUpdateSpreadsheetRequest = BatchUpdateSpreadsheetRequest().setRequests(
            listOf(
                Request().setAddSheet(addSheetRequest)
            )
        )

        GoogleApiRequester.sendRequest {
            sheets()
                .batchUpdate(spreadsheetId, batchUpdateSpreadsheetRequest)
                .execute()
        }

        expandSheetColumns(spreadsheetId, sheetTitle, 50)
    }

    fun createSheetIfAbsent(spreadsheetId: String, sheetTitle: String) {
        if (!sheetExists(spreadsheetId, sheetTitle)) {
            createSheet(spreadsheetId, sheetTitle)
        }
    }

    fun expandSheetColumns(spreadsheetId: String, sheetTitle: String, columnCount: Int) {
        val spreadsheet = GoogleApiRequester.sendRequest {
            sheets()
                .get(spreadsheetId)
                .execute()
        }

        val sheet = spreadsheet.sheets
            ?.firstOrNull { it.properties?.title == sheetTitle }
            ?: error("Sheet with title '$sheetTitle' not found")

        val request = BatchUpdateSpreadsheetRequest().setRequests(
            listOf(
                Request().setUpdateSheetProperties(
                    UpdateSheetPropertiesRequest()
                        .setProperties(
                            SheetProperties()
                                .setSheetId(sheet.properties.sheetId)
                                .setGridProperties(
                                    GridProperties().setColumnCount(columnCount)
                                )
                        )
                        .setFields("gridProperties.columnCount")
                )
            )
        )

        GoogleApiRequester.sendRequest {
            sheets()
                .batchUpdate(spreadsheetId, request)
                .execute()
        }
    }

    fun getSpreadsheet(spreadsheetId: String): Spreadsheet {
        return GoogleApiRequester.sendRequest {
            sheets()
                .get(spreadsheetId)
                .execute()
        }
    }

    fun getSheet(spreadsheetId: String, sheetTitle: String): Sheet {
        return getSpreadsheet(spreadsheetId).sheets
            ?.firstOrNull { it.properties?.title == sheetTitle }
            ?: error("Sheet with title '$sheetTitle' not found")
    }

    fun getSheetId(spreadsheetId: String, sheetTitle: String): Int {
        return getSheet(spreadsheetId, sheetTitle).properties.sheetId
    }

    fun duplicateSheet(
        spreadsheetId: String,
        sourceSheetTitle: String,
        newSheetTitle: String,
    ) {
        val spreadsheet = getSpreadsheet(spreadsheetId)

        val sourceSheet = spreadsheet.sheets
            ?.firstOrNull { it.properties?.title == sourceSheetTitle }
            ?: error("Sheet with title '$sourceSheetTitle' not found")

        val insertSheetIndex = spreadsheet.sheets?.size ?: 0

        val duplicateSheetRequest = DuplicateSheetRequest()
            .setSourceSheetId(sourceSheet.properties.sheetId)
            .setNewSheetName(newSheetTitle)
            .setInsertSheetIndex(insertSheetIndex)

        val batchUpdateSpreadsheetRequest = BatchUpdateSpreadsheetRequest().setRequests(
            listOf(
                Request().setDuplicateSheet(duplicateSheetRequest)
            )
        )

        GoogleApiRequester.sendRequest {
            sheets()
                .batchUpdate(spreadsheetId, batchUpdateSpreadsheetRequest)
                .execute()
        }
    }

    fun clearRange(spreadsheetId: String, rangeName: String) {
        GoogleApiRequester.sendRequest {
            sheets()
                .values()
                .clear(
                    spreadsheetId,
                    rangeName,
                    ClearValuesRequest(),
                )
                .execute()
        }
    }
}