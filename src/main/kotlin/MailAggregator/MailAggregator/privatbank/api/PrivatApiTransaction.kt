package MailAggregator.MailAggregator.privatbank.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slice of the ACP statements/transactions row we actually use downstream.
 * The ACP response carries ~30 fields per row; we deserialise only what the [PrivatStatementMapper]
 * reads and let Jackson drop the rest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrivatApiTransaction(
    @JsonProperty("REF") val ref: String,
    @JsonProperty("DATE_TIME_DAT_OD_TIM_P") val dateTime: String,
    @JsonProperty("OSND") val purpose: String?,
    @JsonProperty("SUM") val sum: String,
    @JsonProperty("CCY") val currency: String,
    @JsonProperty("TRANTYPE") val tranType: String,
    @JsonProperty("AUT_CNTR_NAM") val counterName: String?,
)
