package hu.orszem

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class OrszemApiApplication

fun main(args: Array<String>) {
    runApplication<OrszemApiApplication>(*args)
}
