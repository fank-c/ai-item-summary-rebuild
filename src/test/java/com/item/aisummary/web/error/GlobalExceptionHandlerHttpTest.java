package com.item.aisummary.web.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实用户和文件入口的 415 合同；保留请求标识和框架提供的支持类型头。 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerHttpTest {
    @Autowired MockMvc mvc;

    @Test
    void unsupportedUserCreationContentTypeReturns415() throws Exception {
        assertUnsupported(post("/api/users").contentType("text/plain"), "/api/users", "application/json");
    }

    @Test
    void unsupportedUserUpdateContentTypeReturns415BeforeLookingUpUser() throws Exception {
        assertUnsupported(put("/api/users/missing").contentType("text/plain"), "/api/users/missing", "application/json");
    }

    @Test
    void unsupportedFileUploadContentTypeReturns415BeforeResolvingStorage() throws Exception {
        assertUnsupported(post("/api/files?storageType=minio").contentType("application/json"), "/api/files", "multipart/form-data");
    }

    private void assertUnsupported(MockHttpServletRequestBuilder request, String instance, String supportedType) throws Exception {
        var result = mvc.perform(request.content("PRIVATE_BODY_MARKER").header("X-Request-Id", "media-type-review"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(415))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.type").value("urn:problem:unsupported-media-type"))
            .andExpect(jsonPath("$.title").value("请求媒体类型不支持"))
            .andExpect(jsonPath("$.detail").value("请求的 Content-Type 不受支持"))
            .andExpect(jsonPath("$.instance").value(instance))
            .andExpect(jsonPath("$.requestId").value("media-type-review"))
            .andExpect(header().string("X-Request-Id", "media-type-review"))
            .andReturn();
        assertThat(result.getResponse().getHeader("Accept")).contains(supportedType);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE_BODY_MARKER");
    }
}
