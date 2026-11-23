package dev.nafs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.flywaydb.core.Flyway

fun main() {
    val dbUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nafs"
    val dbUser = System.getenv("POSTGRES_USER") ?: "nafs_app"
    val dbPass = System.getenv("POSTGRES_PASSWORD") ?: "change_me_local_dev_only"

    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPass
        maximumPoolSize = 10
    })

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    embeddedServer(Netty, port = 8081, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        routing {
            get("/health") {
                call.respond(mapOf("status" to "ok", "service" to "nafs-backend-ktor"))
            }
        }
    }.start(wait = true)
}