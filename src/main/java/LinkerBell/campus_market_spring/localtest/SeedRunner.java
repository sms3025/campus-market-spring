package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.domain.Category;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.IntFunction;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit one-shot preparation, never invoked during a normal app startup. */
@Component @Profile("local-test") @ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class SeedRunner implements CommandLineRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Environment env;
    private final ConfigurableApplicationContext context;
    public SeedRunner(JdbcTemplate db, TransactionTemplate tx, Environment env, ConfigurableApplicationContext context) {
        this.db = db; this.tx = tx; this.env = env; this.context = context;
    }
    public record Scale(int users, int items, int likes, int keywords) {
        public static Scale of(String name) {
            return switch (name) {
                case "smoke" -> new Scale(100, 1000, 5000, 200);
                case "small" -> new Scale(1000, 10000, 50000, 2000);
                case "medium" -> new Scale(10000, 100000, 500000, 20000);
                case "large" -> new Scale(100000, 1000000, 5000000, 200000);
                default -> throw new IllegalArgumentException("Unknown seed scale: " + name);
            };
        }
    }
    private Timestamp date(int index) {
        return Timestamp.valueOf(LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(index / 10));
    }
    private void batch(String sql, int count, IntFunction<Object[]> row) {
        int chunk = env.getProperty("seed.chunk", Integer.class, 1000);
        if (chunk < 1 || chunk > 10000) throw new IllegalArgumentException("seed.chunk must be 1..10000");
        for (int start = 1; start <= count; start += chunk) {
            List<Object[]> rows = new ArrayList<>(chunk);
            for (int i = start; i < Math.min(start + chunk, count + 1); i++) rows.add(row.apply(i));
            tx.executeWithoutResult(status -> db.batchUpdate(sql, rows));
            if (start == 1 || start % 100000 < chunk) System.out.println("seed progress " + start + "/" + count);
        }
    }
    @Override public void run(String... args) throws Exception {
        String database = db.queryForObject("select database()", String.class);
        if (!"campus_local_test".equals(database)) throw new IllegalStateException("Seed requires campus_local_test database");
        String scaleName = env.getProperty("seed.scale", "smoke");
        Scale s = Scale.of(scaleName);
        String distribution = env.getProperty("seed.distribution", "uniform");
        if (!Set.of("uniform", "skewed").contains(distribution)) throw new IllegalArgumentException("Invalid distribution");
        long seed = env.getProperty("seed.random", Long.class, 3025L);
        int fanout = env.getProperty("seed.fanout", Integer.class, 1);
        if (fanout < 1 || fanout > Math.min(100, s.users() - 3)) throw new IllegalArgumentException("Invalid fanout for scale");
        db.execute("create table if not exists local_seed_manifest (id int primary key, state varchar(20), manifest longtext)");
        if (db.queryForObject("select count(*) from local_seed_manifest", Long.class) != 0
            || db.queryForObject("select count(*) from users", Long.class) != 0)
            throw new IllegalStateException("Database already seeded or incomplete. Explicit reset required.");
        db.update("insert into local_seed_manifest values (1,'incomplete','{}')");
        long start = System.nanoTime();
        batch("insert into campus(campus_id,university_name,region,email,created_date,last_modified_date) values(?,?,?,?,?,?)", 10,
            i -> new Object[]{i,"테스트대학교" + i,"지역" + i,"school" + i + ".invalid",date(i),date(i)});
        batch("insert into users(user_id,campus_id,login_email,school_email,nickname,rating,role,is_deleted,created_date,last_modified_date) values(?,?,?,?,?,?,?,?,?,?)", s.users(),
            i -> new Object[]{i, i <= 103 ? 1 : 1 + i % 10,
                i <= 3 ? "fixture" + i + "@example.invalid" : "seed" + i + "@example.invalid",
                "seed" + i + "@school1.invalid", "seed-user-" + i, 0, i == 3 ? "ADMIN" : "USER", false,date(i),date(i)});
        batch("insert into terms(terms_id,title,terms_url,is_required,created_date,last_modified_date) values(?,?,?,?,?,?)", 1,
            i -> new Object[]{i,"로컬 테스트 약관","https://example.invalid/terms",true,date(i),date(i)});
        Random random = new Random(seed);
        batch("insert into item(item_id,user_id,campus_id,title,description,price,category,item_status,thumbnail,is_deleted,created_date,last_modified_date) values(?,?,?,?,?,?,?,?,?,?,?,?)", s.items(),
            i -> {
                int user = i == 1 ? 1 : 4 + (i % (s.users() - 3));
                return new Object[]{i,user,user <= 103 ? 1 : 1 + user % 10,
                    (i % 100 == 0 ? "rare " : "상품 ") + i,"로컬 생성 데이터",random.nextInt(1000) * 100,
                    Category.values()[i % Category.values().length].name(),i % 4 == 0 ? 1 : 0,
                    "https://example.invalid/item.png",i % 20 == 0,date(i),date(i)};
            });
        // First 500 items belong to fixture user 2's likes; all other pairs are deterministic and unique.
        batch("insert into likes(like_id,user_id,item_id,created_date,last_modified_date) values(?,?,?,?,?)", s.likes(),
            i -> {
                long n = i - 501L;
                long user = i <= 500 ? 2 : 4 + n % (s.users() - 3);
                long round = i <= 500 ? 0 : n / (s.users() - 3);
                long item = i <= 500 ? i : 1 + (distribution.equals("skewed") ? round : n) % s.items();
                return new Object[]{i,user,item,date(i),date(i)};
            });
        batch("insert into keyword(keyword_id,user_id,keyword_name,created_date,last_modified_date) values(?,?,?,?,?)", s.keywords(),
            i -> new Object[]{i,4 + (i - 1) % (s.users() - 3), "seed-key-" + i,date(i),date(i)});
        batch("insert into keyword(keyword_id,user_id,keyword_name,created_date,last_modified_date) values(?,?,?,?,?)", fanout,
            i -> new Object[]{s.keywords() + i, i + 3,"FCM-BENCH",date(i),date(i)});
        batch("insert into user_fcm_token(user_fcm_token_id,user_id,fcm_token,created_date,last_modified_date) values(?,?,?,?,?)", fanout,
            i -> new Object[]{i,i + 3,"mock-seed-" + i,date(i),date(i)});
        batch("insert into chat_room(chat_room_id,user_id,item_id,user_count,created_date,last_modified_date) values(?,?,?,?,?,?)", 100,
            i -> new Object[]{i,2,i,2,date(i),date(i)});
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("scale", scaleName); manifest.put("distribution", distribution); manifest.put("seed", seed);
        manifest.put("fanout", fanout); manifest.put("referenceTime", "2025-01-01T00:00:00");
        manifest.put("codeVersion", env.getProperty("seed.version", "working-tree"));
        Map<String,Long> counts = new LinkedHashMap<>();
        for (String table : List.of("users","item","likes","keyword","campus","chat_room","user_fcm_token"))
            counts.put(table, db.queryForObject("select count(*) from " + table, Long.class));
        if (counts.get("item") != s.items() || counts.get("likes") != s.likes()
            || counts.get("users") != s.users() || counts.get("keyword") != s.keywords() + fanout)
            throw new IllegalStateException("Seed count mismatch");
        long duplicates = db.queryForObject("select count(*) from (select user_id,item_id from likes group by user_id,item_id having count(*)>1) d", Long.class);
        if (duplicates != 0) throw new IllegalStateException("Duplicate likes");
        manifest.put("counts", counts); manifest.put("duplicateLikes", duplicates);
        manifest.put("integrity", "foreign keys enforced during every insert");
        manifest.put("elapsedSeconds", (System.nanoTime() - start) / 1e9);
        manifest.put("completedAt", Instant.now().toString());
        db.execute("analyze table users,item,likes,keyword");
        manifest.put("estimatedDatabaseBytes", db.queryForObject("select coalesce(sum(data_length+index_length),0) from information_schema.tables where table_schema=database()", Long.class));
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        db.update("update local_seed_manifest set state='complete',manifest=? where id=1", json);
        Path output = Path.of(env.getProperty("seed.output", "artifacts/performance/seed-manifest.json"));
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output,json);
        System.out.println(json);
        System.exit(SpringApplication.exit(context));
    }
}
