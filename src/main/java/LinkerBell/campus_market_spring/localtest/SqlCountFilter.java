package LinkerBell.campus_market_spring.localtest;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Counts JDBC executions on this request thread, including authentication and DTO mapping. */
@Component @Profile("local-test") @Order(-200)
public class SqlCountFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/api/")
            || request.getRequestURI().startsWith("/admin/api/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        QueryCountHolder.clear();
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
            wrapped.setHeader("X-Local-SQL-Count", Long.toString(QueryCountHolder.getGrandTotal().getTotal()));
            wrapped.setHeader("X-Local-SQL-Ms", Long.toString(QueryCountHolder.getGrandTotal().getTime()));
        } finally {
            QueryCountHolder.clear();
            wrapped.copyBodyToResponse();
        }
    }
}
