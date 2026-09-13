package LinkerBell.campus_market_spring.localtest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** Runs against the dedicated, seeded MariaDB/Redis stack. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local-test")
class LocalApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private String token(int account) throws Exception {
        var body=mvc.perform(post("/local-test/api/session/"+account)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("accessToken").asText();
    }
    @Test void localPageAndLimitedFixtureAccounts() throws Exception {
        mvc.perform(get("/local-test/index.html")).andExpect(status().isOk());
        mvc.perform(post("/local-test/api/session/4")).andExpect(status().isBadRequest());
    }
    @Test void rolesAndValidationRemainEnforced() throws Exception {
        mvc.perform(get("/admin/api/v1/campuses").header("Authorization","Bearer "+token(1))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/api/v1/campuses").header("Authorization","Bearer "+token(3))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/items?minPrice=100&maxPrice=10").header("Authorization","Bearer "+token(1))).andExpect(status().is4xxClientError());
    }
    /** Before the fix this grew by exactly one query per returned item: 13, 53 and 103. */
    @Test void keepsQueryCountFlatAcrossPageSizes() throws Exception {
        String access=token(2);
        long small=sqlCountOfLikes(access,10);
        long large=sqlCountOfLikes(access,50);
        long largest=sqlCountOfLikes(access,100);
        assertThat(small).isEqualTo(large).isEqualTo(largest);
        assertThat(small).isLessThan(13);
        System.out.println("likes query count: size10="+small+", size50="+large+", size100="+largest);
    }
    private long sqlCountOfLikes(String access,int size) throws Exception {
        var response=mvc.perform(get("/api/v1/items/likes?size="+size).header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(size)).andReturn().getResponse();
        return Long.parseLong(response.getHeader("X-Local-SQL-Count"));
    }
}
