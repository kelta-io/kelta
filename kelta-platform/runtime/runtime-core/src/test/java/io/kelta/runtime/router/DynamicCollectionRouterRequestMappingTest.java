package io.kelta.runtime.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the exact request-mapping surface of {@link DynamicCollectionRouter}.
 *
 * <p>The router's all-variable mappings under {@code /api} decide Spring's handler ranking for
 * every other worker controller (a {@code /**} mapping under {@code /api} loses every GET the
 * router can match — {@code concerns.md} → Fragile Areas), and worker controllers delegate to the
 * handler methods directly while controller advices ({@code CerbosFieldSecurityAdvice}, the write
 * advice, masking, record authorization) depend on their return types. So the patterns, HTTP
 * methods, every other mapping attribute that participates in ranking ({@code params},
 * {@code headers}, {@code consumes}, {@code produces}) and the public handler signatures are
 * asserted verbatim. Changing any of them is an API change, not a refactor.
 */
class DynamicCollectionRouterRequestMappingTest {

    @Test
    @DisplayName("class-level mapping is @RestController + @RequestMapping(\"/api\")")
    void classLevelMapping() {
        assertNotNull(DynamicCollectionRouter.class.getAnnotation(RestController.class));
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                DynamicCollectionRouter.class, RequestMapping.class);
        assertNotNull(mapping);
        assertEquals(List.of("/api"), Arrays.asList(mapping.path()));
        assertEquals(0, mapping.method().length);
        assertEquals(0, mapping.params().length + mapping.headers().length
                + mapping.consumes().length + mapping.produces().length);
    }

    @Test
    @DisplayName("handler mappings are exactly the documented set")
    void handlerMappings() {
        List<String> actual = new ArrayList<>();
        for (Method m : DynamicCollectionRouter.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            actual.add(Arrays.toString(mapping.method()) + " " + String.join("|", mapping.path())
                    + " params=" + Arrays.toString(mapping.params())
                    + " headers=" + Arrays.toString(mapping.headers())
                    + " consumes=" + Arrays.toString(mapping.consumes())
                    + " produces=" + Arrays.toString(mapping.produces())
                    + " -> " + m.getName());
        }
        actual.sort(null);

        List<String> expected = List.of(
                "[DELETE] /{collectionName}/{id} params=[] headers=[] consumes=[] produces=[] -> delete",
                "[DELETE] /{parentName}/{parentId}/{childName}/{childId} params=[] headers=[] consumes=[] produces=[] -> deleteChild",
                "[GET] /{collectionName} params=[] headers=[] consumes=[] produces=[] -> list",
                "[GET] /{collectionName}/{id} params=[] headers=[] consumes=[] produces=[] -> get",
                "[GET] /{parentName}/{parentId}/{childName} params=[] headers=[] consumes=[] produces=[] -> listChildren",
                "[GET] /{parentName}/{parentId}/{childName}/{childId} params=[] headers=[] consumes=[] produces=[] -> getChild",
                "[PATCH] /{collectionName}/{id} params=[] headers=[] consumes=[] produces=[] -> updatePatch",
                "[PATCH] /{parentName}/{parentId}/{childName}/{childId} params=[] headers=[] consumes=[] produces=[] -> patchChild",
                "[POST] /{collectionName} params=[] headers=[] consumes=[] produces=[] -> create",
                "[POST] /{parentName}/{parentId}/{childName} params=[] headers=[] consumes=[] produces=[] -> createChild",
                "[PUT] /{collectionName}/{id} params=[] headers=[] consumes=[] produces=[] -> updatePut",
                "[PUT] /{parentName}/{parentId}/{childName}/{childId} params=[] headers=[] consumes=[] produces=[] -> updateChild");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("public method signatures (handlers + setters) are unchanged")
    void publicSignatures() {
        List<String> actual = Arrays.stream(DynamicCollectionRouter.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(DynamicCollectionRouterRequestMappingTest::describe)
                .sorted()
                .collect(Collectors.toList());

        List<String> expected = List.of(
                "org.springframework.http.ResponseEntity<java.lang.Void> delete(@PathVariable(collectionName) java.lang.String, @PathVariable(id) java.lang.String, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.lang.Void> deleteChild(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @PathVariable(childId) java.lang.String, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> create(@PathVariable(collectionName) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> createChild(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> get(@PathVariable(collectionName) java.lang.String, @PathVariable(id) java.lang.String, @RequestParam(required=false) org.springframework.util.MultiValueMap<java.lang.String, java.lang.String>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> getChild(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @PathVariable(childId) java.lang.String, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> list(@PathVariable(collectionName) java.lang.String, @RequestParam(required=false) org.springframework.util.MultiValueMap<java.lang.String, java.lang.String>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> listChildren(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @RequestParam(required=false) org.springframework.util.MultiValueMap<java.lang.String, java.lang.String>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> patchChild(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @PathVariable(childId) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> updateChild(@PathVariable(parentName) java.lang.String, @PathVariable(parentId) java.lang.String, @PathVariable(childName) java.lang.String, @PathVariable(childId) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> updatePatch(@PathVariable(collectionName) java.lang.String, @PathVariable(id) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "org.springframework.http.ResponseEntity<java.util.Map<java.lang.String, java.lang.Object>> updatePut(@PathVariable(collectionName) java.lang.String, @PathVariable(id) java.lang.String, @RequestBody(required=true) java.util.Map<java.lang.String, java.lang.Object>, jakarta.servlet.http.HttpServletRequest)",
                "void setOnDemandLoader(io.kelta.runtime.registry.CollectionOnDemandLoader)",
                "void setSubResourceAuthorizer(io.kelta.runtime.router.SubResourceAuthorizer)",
                "void setSystemCollectionCache(io.kelta.runtime.router.SystemCollectionCache)",
                "void setUserIdResolver(io.kelta.runtime.router.UserIdResolver)");
        assertEquals(expected, actual);
    }

    private static String describe(Method m) {
        List<String> params = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            StringBuilder sb = new StringBuilder();
            for (Annotation a : p.getAnnotations()) {
                if (a instanceof PathVariable pv) {
                    sb.append("@PathVariable(").append(pv.value()).append(") ");
                } else if (a instanceof RequestParam rp) {
                    sb.append("@RequestParam(required=").append(rp.required()).append(") ");
                } else if (a instanceof RequestBody rb) {
                    sb.append("@RequestBody(required=").append(rb.required()).append(") ");
                } else {
                    sb.append('@').append(a.annotationType().getSimpleName()).append(' ');
                }
            }
            sb.append(p.getParameterizedType().getTypeName());
            params.add(sb.toString());
        }
        return m.getGenericReturnType().getTypeName() + " " + m.getName() + "(" + String.join(", ", params) + ")";
    }
}
