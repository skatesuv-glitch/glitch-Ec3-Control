package com.ec3control.network

data class ApiEnvironment(val baseUrl: String) {
    init { require(baseUrl.startsWith("https://")) { "HTTPS required" } }
}
