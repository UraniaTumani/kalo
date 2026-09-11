package com.kalo.config;

import com.kalo.common.exception.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 401 with the standard error body when a protected endpoint is called
 * without valid authentication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    private final ErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        log.debug(
                "Unauthenticated request rejected: method={} path={}",
                request.getMethod(),
                request.getRequestURI()
        );

        errorResponseWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication is required"
        );
    }
}
