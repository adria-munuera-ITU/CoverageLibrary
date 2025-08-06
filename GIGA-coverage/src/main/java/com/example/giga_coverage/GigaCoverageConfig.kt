package com.example.giga_coverage

object GigaCoverageConfig {
    var baseUrl: String = "https://your.server.domain.com"
    var imageUrl: String = "https://storage.googleapis.com/gd-prod/images/a910d418-7123-4bc4-aa3b-ef7e25e74ae6.60c498c559810aa0.webp"
    
    fun configure(block: GigaCoverageConfig.() -> Unit) {
        this.apply(block)
    }
}