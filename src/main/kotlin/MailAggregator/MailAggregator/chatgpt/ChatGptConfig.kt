package MailAggregator.MailAggregator.chatgpt

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChatGptConfig {
    @Bean
    fun chatGptApi(): ChatGPTApi = ChatGPTApi()
}