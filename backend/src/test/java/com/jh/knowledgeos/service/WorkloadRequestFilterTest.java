package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import com.jh.knowledgeos.config.WorkloadRequestFilter;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class WorkloadRequestFilterTest {
    @Test void preservesRawSignedBytesAndReadableControllerBody() throws Exception {
        byte[] raw = "{ \"query\": \"报销\" }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setContent(raw); request.addHeader(WorkloadRequestFilter.HEADER, "proof");
        MockFilterChain chain = new MockFilterChain();
        new WorkloadRequestFilter().doFilter(request, new MockHttpServletResponse(), chain);
        HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
        assertArrayEquals(raw, (byte[]) wrapped.getAttribute(WorkloadRequestFilter.BODY));
        assertArrayEquals(raw, org.springframework.util.StreamUtils.copyToByteArray(wrapped.getInputStream()));
    }
    @Test void rejectsOversizedBodiesWithoutCallingController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setContent(new byte[1024 * 1024 + 1]); request.addHeader(WorkloadRequestFilter.HEADER, "proof");
        MockFilterChain chain = new MockFilterChain(); MockHttpServletResponse response = new MockHttpServletResponse();
        new WorkloadRequestFilter().doFilter(request, response, chain);
        assertEquals(413, response.getStatus()); assertNull(chain.getRequest());
    }
}
