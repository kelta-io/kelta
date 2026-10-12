package io.kelta.worker.interceptor;

import io.kelta.runtime.router.DynamicCollectionRouter;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiPaths#collectionName} — the collection the record/field advices authorize a request
 * against — and the router contract it relies on.
 */
class ApiPathsCollectionNameTest {

    @Test
    void childResourceHandlerResolvesTheChild() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/a1/contacts");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("parentName", "accounts", "parentId", "a1", "childName", "contacts"));

        assertThat(ApiPaths.collectionName(request)).isEqualTo("contacts");
    }

    @Test
    void otherHandlersResolveTheFirstSegment() {
        MockHttpServletRequest dedicated = new MockHttpServletRequest("POST", "/api/attachments/a1/finalize");
        dedicated.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "a1"));
        MockHttpServletRequest unmatched = new MockHttpServletRequest("GET", "/api/contacts/c1");

        assertThat(ApiPaths.collectionName(dedicated)).isEqualTo("attachments");
        assertThat(ApiPaths.collectionName(unmatched)).isEqualTo("contacts");
    }

    /**
     * Pins the variable name to the one {@link DynamicCollectionRouter} binds on its child-resource
     * handlers: renaming it in the router alone would make the advices fall back to the parent
     * segment on every child request.
     */
    @Test
    void everyChildResourceHandlerBindsTheChildNameVariable() {
        List<String> childPatterns = new ArrayList<>();
        for (Method method : DynamicCollectionRouter.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            for (String pattern : mapping.path()) {
                long depth = Arrays.stream(pattern.split("/")).filter(s -> !s.isEmpty()).count();
                if (depth >= 3) {
                    childPatterns.add(pattern);
                }
            }
        }

        assertThat(childPatterns).hasSize(6).allSatisfy(pattern ->
                assertThat(pattern.split("/")[3]).isEqualTo("{" + ApiPaths.CHILD_NAME_VARIABLE + "}"));
    }
}
