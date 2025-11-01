package MailAggregator.MailAggregator.spreadsheet

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import java.io.InputStream

/**
 * Auth for Google Sheets using a Service Account.
 * Make sure the spreadsheet is SHARED with the service account email.
 */
class Authentication {
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val HTTP_TRANSPORT by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private val DEFAULT_SCOPES = listOf(
        SheetsScopes.SPREADSHEETS
    )

    /** Build Sheets from a service account JSON input stream. */
    fun fromStream(
        serviceAccountJson: InputStream,
        applicationName: String = "MailAggregator",
        scopes: List<String> = DEFAULT_SCOPES
    ): Sheets {
        serviceAccountJson.use {
            val creds = GoogleCredentials.fromStream(it).createScoped(scopes)
            val requestInitializer = HttpCredentialsAdapter(creds)
            return Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, requestInitializer)
                .setApplicationName(applicationName)
                .build()
        }
    }

    /** Build Sheets from a resource on the classpath (e.g. /service-account.json). */
    fun authenticate(
        resourcePath: String = "/service-account.json",
        applicationName: String = "MailAggregator",
        scopes: List<String> = DEFAULT_SCOPES
    ): Sheets {
        val resPath = resourcePath.removePrefix("/")
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resPath)
            ?: throw IllegalArgumentException("Service account file not found at '$resourcePath'")
        return fromStream(stream, applicationName, scopes)
    }
}
