package dev.nafs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.nafs.contracts.PrayerCardDto
import dev.nafs.contracts.PrayerName
import dev.nafs.contracts.SalahStatus
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import java.net.URI
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

@Serializable
data class LogSalahRequest(
    val prayer: PrayerName,
    val status: SalahStatus,
    val personalNote: String? = null
)

fun main() {
    val rawDbUrl = System.getenv("DATABASE_URL")
    val (jdbcUrl, dbUser, dbPass) = if (rawDbUrl != null && (rawDbUrl.startsWith("postgres://") || rawDbUrl.startsWith("postgresql://"))) {
        val uri = URI(rawDbUrl)
        val userInfo = uri.userInfo.split(":", limit = 2)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        Triple("jdbc:postgresql://${uri.host}$port${uri.path}", userInfo[0], userInfo.getOrElse(1) { "" })
    } else {
        Triple(
            rawDbUrl ?: "jdbc:postgresql://localhost:5433/nafs",
            System.getenv("POSTGRES_USER") ?: "nafs_app",
            System.getenv("POSTGRES_PASSWORD") ?: "change_me_local_dev_only"
        )
    }

    val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = dbUser
        this.password = dbPass
        maximumPoolSize = 5
    })

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    val devUserId: UUID = dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO users (nafs_public_id) VALUES ('nafs_local_dev')
            ON CONFLICT (nafs_public_id) DO UPDATE SET nafs_public_id = EXCLUDED.nafs_public_id
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getObject("id", UUID::class.java)
            }
        }
    }

    val defaultTimes = mapOf(
        PrayerName.FAJR to "05:15",
        PrayerName.DHUHR to "12:15",
        PrayerName.ASR to "15:35",
        PrayerName.MAGHRIB to "17:55",
        PrayerName.ISHA to "19:15"
    )

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val host = if (System.getenv("PORT") != null) "0.0.0.0" else "127.0.0.1"

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json() }

        routing {
            staticResources("/", "static")

            get("/health") {
                call.respond(mapOf("status" to "ok", "service" to "nafs-backend-ktor"))
            }

            get("/api/salah/today") {
                val today = LocalDate.now()
                val savedLogs = mutableMapOf<PrayerName, Pair<SalahStatus, String?>>()

                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "SELECT prayer_name, status, personal_note FROM salah_logs WHERE user_id = ? AND prayer_date = ?"
                    ).use { ps ->
                        ps.setObject(1, devUserId)
                        ps.setDate(2, Date.valueOf(today))
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                val name = PrayerName.valueOf(rs.getString("prayer_name"))
                                val status = SalahStatus.valueOf(rs.getString("status"))
                                val note = rs.getString("personal_note")
                                savedLogs[name] = status to note
                            }
                        }
                    }
                }

                val cards = PrayerName.entries.map { prayer ->
                    val saved = savedLogs[prayer]
                    PrayerCardDto(
                        prayer = prayer,
                        calculatedTime = defaultTimes[prayer] ?: "00:00",
                        verifiedJamaahTime = null,
                        jamaahSourceLabel = null,
                        status = saved?.first ?: SalahStatus.NOT_RECORDED,
                        personalNote = saved?.second
                    )
                }
                call.respond(cards)
            }

            post("/api/salah/log") {
                val req = call.receive<LogSalahRequest>()
                val today = LocalDate.now()

                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        """
                        INSERT INTO salah_logs (user_id, prayer_date, prayer_name, status, personal_note)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, prayer_date, prayer_name)
                        DO UPDATE SET status = EXCLUDED.status, personal_note = EXCLUDED.personal_note, recorded_at = NOW()
                        """.trimIndent()
                    ).use { ps ->
                        ps.setObject(1, devUserId)
                        ps.setDate(2, Date.valueOf(today))
                        ps.setString(3, req.prayer.name)
                        ps.setString(4, req.status.name)
                        ps.setString(5, req.personalNote)
                        ps.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("saved" to true, "prayer" to req.prayer.name, "status" to req.status.name))
            }
        }
    }.start(wait = true)
}