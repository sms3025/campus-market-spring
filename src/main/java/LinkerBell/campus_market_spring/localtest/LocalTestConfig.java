package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.service.S3Service;
import LinkerBell.campus_market_spring.dto.S3ResponseDto;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@Profile("local-test")
public class LocalTestConfig implements WebMvcConfigurer {
    @Bean @Order(0)
    SecurityFilterChain localTools(HttpSecurity http) throws Exception {
        return http.securityMatcher("/local-test/**", "/actuator/**")
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/local-test/**")
            .addResourceLocations("classpath:/local-test/");
    }

    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/local-test/").setViewName("forward:/local-test/index.html");
    }

    @Bean
    static BeanPostProcessor queryCountingDataSource() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && name.equals("dataSource")) {
                    return ProxyDataSourceBuilder.create(ds).name("local-test")
                        .countQuery().build();
                }
                return bean;
            }
        };
    }

    @Bean
    S3Service localS3() {
        return new S3Service(null, null, null) {
            @Override public void deleteS3File(String url) { /* Local fixtures only. */ }
            @Override public S3ResponseDto createPreSignedPutUrl(String fileName) {
                throw new UnsupportedOperationException("S3 upload is outside local-test scope");
            }
        };
    }
}
