package LinkerBell.campus_market_spring.localtest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class LocalProfileIsolationTest {
    @Test void localComponentsAreAbsentWithoutProfile(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().setActiveProfiles("isolation");
            context.register(LocalTestConfig.class,LocalTestController.class,LocalFcmService.class,SeedRunner.class,SqlCountFilter.class);
            context.refresh();
            assertThat(context.getBeansOfType(LocalTestController.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalFcmService.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalTestConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(SeedRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(SqlCountFilter.class)).isEmpty();
        }
    }
}
