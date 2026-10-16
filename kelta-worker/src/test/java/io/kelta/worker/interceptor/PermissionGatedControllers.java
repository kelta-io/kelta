package io.kelta.worker.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the {@link PermissionGatedController} promise: every request handler of a controller
 * answers 403 to a caller without its system permission. Handlers are found by reflection, so a
 * handler added later without the gate fails the controller's test instead of shipping exempt
 * from the record advice.
 */
public final class PermissionGatedControllers {

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, PatchMapping.class,
            DeleteMapping.class, RequestMapping.class);

    private PermissionGatedControllers() {
    }

    /**
     * Invokes every handler of {@code controller} with placeholder arguments and {@code request}
     * (whose profile must lack the permission) and asserts each answers 403.
     */
    public static void assertEveryHandlerForbidden(Object controller, HttpServletRequest request) {
        assertThat(controller).isInstanceOf(PermissionGatedController.class);
        List<String> handlers = new ArrayList<>();
        List<String> ungated = new ArrayList<>();
        for (Method method : controller.getClass().getDeclaredMethods()) {
            if (MAPPINGS.stream().noneMatch(method::isAnnotationPresent)) {
                continue;
            }
            handlers.add(method.getName());
            Integer status = statusOf(method, controller, request);
            if (status == null || status != HttpStatus.FORBIDDEN.value()) {
                ungated.add(method.getName() + " -> " + status);
            }
        }
        assertThat(handlers).as("request handlers found").isNotEmpty();
        assertThat(ungated).as("handlers that did not answer 403 without the permission").isEmpty();
    }

    private static Integer statusOf(Method method, Object controller, HttpServletRequest request) {
        Object[] args = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = placeholder(types[i], request);
        }
        try {
            method.setAccessible(true);
            Object result = method.invoke(controller, args);
            return result instanceof ResponseEntity<?> response ? response.getStatusCode().value() : null;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof ResponseStatusException rse ? rse.getStatusCode().value() : null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object placeholder(Class<?> type, HttpServletRequest request) {
        if (HttpServletRequest.class.isAssignableFrom(type)) return request;
        if (type == String.class) return "id-1";
        if (Map.class.isAssignableFrom(type)) return new HashMap<>();
        if (List.class.isAssignableFrom(type)) return new ArrayList<>();
        if (type == int.class) return 1;
        if (type == long.class) return 1L;
        if (type == boolean.class) return false;
        return null;
    }
}
