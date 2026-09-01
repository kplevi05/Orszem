package hu.orszem.support

import hu.orszem.demo.DemoDataService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration test base that restores the documented demo baseline
 * (120 reports + `demo.service` user) before every test method.
 */
abstract class AbstractDemoIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var demoDataService: DemoDataService

    @BeforeEach
    fun resetDemoData() {
        demoDataService.reset()
    }
}
