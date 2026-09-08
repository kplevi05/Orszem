package hu.orszembejelento.backend

import hu.orszembejelento.backend.common.config.ApiProperties
import hu.orszembejelento.backend.common.config.AuthProperties
import hu.orszembejelento.backend.common.config.ReportSubmissionProperties
import hu.orszembejelento.backend.maintenance.MaintenanceProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    ApiProperties::class,
    AuthProperties::class,
    MaintenanceProperties::class,
    ReportSubmissionProperties::class,
)
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
