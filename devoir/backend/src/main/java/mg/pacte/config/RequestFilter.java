package mg.pacte.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Limite également les corps envoyés sans Content-Length (transfert chunked). */
@Component
public class RequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY=2_000_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        if (!request.getRequestURI().startsWith("/api/") || !(request.getMethod().equals("PUT") || request.getMethod().equals("POST"))) {
            chain.doFilter(request,response); return;
        }
        String origin=request.getHeader("Origin");
        String expected=request.getScheme()+"://"+request.getHeader("Host");
        if (origin != null && !origin.equals(expected)) { error(response,403,"Origine interdite."); return; }
        int limit=request.getRequestURI().equals("/api/backups/full") ? 50_000_000 : request.getMethod().equals("POST") && request.getRequestURI().matches("/api/contracts/CT-[a-zA-Z0-9-]+/documents") ? 10_000_000 : MAX_BODY;
        if (request.getContentLengthLong()>limit) { error(response,413,"Requête trop volumineuse."); return; }
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if (body.length>limit) { error(response,413,"Requête trop volumineuse (2 Mo maximum)."); return; }
        var input=new ByteArrayInputStream(body);
        HttpServletRequest wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        chain.doFilter(wrapped,response);
    }

    private void error(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\""+message+"\"}");
    }
}
