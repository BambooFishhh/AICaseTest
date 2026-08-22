package com.testagent.security;

import com.testagent.entity.User;
import com.testagent.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * vT2: JWT 认证过滤器基线测试。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "01234567890123456789012345678901";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthFilter newFilter(User user) throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 12);
        UserRepository userRepository = mock(UserRepository.class);
        if (user != null) {
            when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        }
        JwtAuthFilter filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        // v6.3: SSE 票据鉴权接入后，缺失该 Bean 会导致无 token 路径 NPE
        ReflectionTestUtils.setField(filter, "sseTicketService", mock(SseTicketService.class));
        return filter;
    }

    @Test
    void validBearerTokenSetsAuthentication() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 12);
        User user = new User();
        user.setUsername("admin");
        user.setRole("ADMIN");

        JwtAuthFilter filter = newFilter(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken("admin", "ADMIN"));
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("admin", auth.getName());
        assertEquals(1, auth.getAuthorities().stream()
                .filter(a -> a.getAuthority().equals("ROLE_ADMIN")).count());
    }

    @Test
    void missingTokenLeavesContextEmpty() throws Exception {
        JwtAuthFilter filter = newFilter(null);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void invalidTokenLeavesContextEmpty() throws Exception {
        JwtAuthFilter filter = newFilter(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.token.here");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
