package MailAggregator.MailAggregator.spreadsheet

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import java.net.SocketTimeoutException


object GoogleApiRequester {
    private val log = org.slf4j.LoggerFactory.getLogger(GoogleApiRequester::class.java)
    private const val THREAD_SLEEP = 10000L

    fun <Response : Any> sendRequest(block: () -> Response): Response {
        var executed = false
        var counter = -1
        var res: Response? = null

        while (!executed) {
            try {
                res = block.invoke()
                executed = true
            } catch (exception: Exception) {
                counter = handleException(counter, exception)
            }
        }

        return res ?: Any() as Response
    }

    private fun handleException(
        c: Int,
        exception: Exception,
    ): Int {
        var counter = c
        when (exception.javaClass) {
            GoogleJsonResponseException::class.java -> {
                if ((exception as GoogleJsonResponseException).statusCode == 429) {
                    counter++
                    when (counter) {
                        0 -> log.info("Google api 429 exception warning")
                        6 -> log.warn("Google api 429 mass exception warning")
                        12 -> {
                            log.error("Google api 429 mass exception error")
                            throw exception
                        }
                    }
                    Thread.sleep(THREAD_SLEEP)
                } else if (exception.statusCode == 400) {
                    counter++
                    when (counter) {
                        0 -> log.info("Google api 400 exception warning")
                        3 -> log.warn("Google api 400 mass exception warning")
                        6 -> {
                            log.error("Google api 400 mass exception error")
                            throw exception
                        }
                    }
                    Thread.sleep(THREAD_SLEEP)
                } else if (exception.statusCode == 500) {
                    counter++
                    when (counter) {
                        0 -> log.info("Google internal server error warning")
                        3 -> log.warn("Google internal server error mass warning")
                        6 -> {
                            log.error("Google internal server error error")
                            throw exception
                        }
                    }
                    Thread.sleep(THREAD_SLEEP)
                } else {
                    throw exception
                }
            }

            SocketTimeoutException::class.java -> {
                counter++
                when (counter) {
                    0 -> log.info("Google api SocketTimeoutException warning")
                    6 -> log.warn("Google api mass SocketTimeoutException warning")
                    12 -> {
                        log.error("Google api mass SocketTimeoutException error")
                        throw exception
                    }
                }
                Thread.sleep(THREAD_SLEEP)
            }

            else -> throw exception
        }
        return counter
    }
}