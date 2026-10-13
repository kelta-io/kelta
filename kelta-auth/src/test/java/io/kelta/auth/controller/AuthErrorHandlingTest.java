package io.kelta.auth.controller;

import io.kelta.runtime.router.GlobalExceptionHandler;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Errors are an HTML page for browsers and JSON for API callers, with Spring Boot's error
 * auto-configuration, Thymeleaf and the runtime's JSON {@link GlobalExceptionHandler} wired as in
 * the service. Never the whitelabel page, never a stack trace or exception message.
 */
@DisplayName("Error responses — HTML page for browsers, JSON for APIs")
class AuthErrorHandlingTest {

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String SECRET = "detail-that-must-not-leak";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                    JacksonAutoConfiguration.class, ThymeleafAutoConfiguration.class, ErrorMvcAutoConfiguration.class))
            .withPropertyValues("spring.web.error.whitelabel.enabled=false")
            .withUserConfiguration(Endpoints.class);

    private void withMvc(Consumer<MockMvc> test) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            test.accept(MockMvcBuilders.webAppContextSetup(context).build());
        });
    }

    private static MockHttpServletResponse perform(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request) {
        try {
            return mvc.perform(request).andReturn().getResponse();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertErrorPage(MockHttpServletResponse response, int status, String title) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);
        String html = response.getContentAsString();
        assertThat(html).contains(title).contains("Reference:").containsPattern("<code>[0-9a-f]{8}</code>")
                .doesNotContain(SECRET).doesNotContain("Exception").doesNotContain("at io.kelta")
                .doesNotContain("Whitelabel");
    }

    @Test
    @DisplayName("the service's error controller replaces Spring Boot's, and no whitelabel view exists")
    void noWhitelabel() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(BasicErrorController.class);
            assertThat(context).getBean(ErrorController.class).isInstanceOf(AuthErrorController.class);
            assertThat(context.containsBean("error")).as("whitelabel 'error' view bean").isFalse();
        });
    }

    @Nested
    @DisplayName("an exception from a handler")
    class HandlerExceptions {

        @Test
        @DisplayName("a page handler renders the error page with status 500, whatever the Accept header")
        void pageHandler() {
            withMvc(mvc -> {
                try {
                    assertErrorPage(perform(mvc, get("/test/page").header("Accept", BROWSER_ACCEPT)), 500,
                            "Something went wrong");
                    assertErrorPage(perform(mvc, get("/test/page").header("Accept", "*/*")), 500,
                            "Something went wrong");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Test
        @DisplayName("a page handler's status exception keeps its status")
        void pageHandlerStatus() {
            withMvc(mvc -> {
                try {
                    assertErrorPage(perform(mvc, get("/test/page-missing").header("Accept", BROWSER_ACCEPT)), 404,
                            "Page not found");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Test
        @DisplayName("a JSON endpoint still answers with the platform's JSON error envelope")
        void jsonEndpoint() {
            withMvc(mvc -> {
                try {
                    MockHttpServletResponse response = perform(mvc, get("/test/api").accept(MediaType.APPLICATION_JSON));
                    assertThat(response.getStatus()).isEqualTo(500);
                    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
                    assertThat(response.getContentAsString()).contains("\"errors\"").contains("INTERNAL_ERROR")
                            .doesNotContain(SECRET);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Test
        @DisplayName("a JSON endpoint that had already set text/html still answers with its JSON error")
        void presetHtmlContentType() {
            // The dispatcher clears the content type before resolving a handler exception; the
            // preset-content-type failure only ever came from the /error path (see ErrorPath).
            withMvc(mvc -> {
                try {
                    MockHttpServletResponse response = perform(mvc, get("/test/api-after-html").header("Accept", "*/*"));
                    assertThat(response.getStatus()).isEqualTo(500);
                    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
                    assertThat(response.getContentAsString()).contains("INTERNAL_ERROR");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Nested
    @DisplayName("the /error page (errors outside a handler)")
    class ErrorPath {

        @Test
        @DisplayName("an API caller gets JSON with status, path and a request id")
        void json() {
            withMvc(mvc -> {
                try {
                    MockHttpServletResponse response = perform(mvc, get("/error")
                            .accept(MediaType.APPLICATION_JSON)
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                            .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/missing"));
                    assertThat(response.getStatus()).isEqualTo(404);
                    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
                    JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
                    assertThat(body.get("status").asInt()).isEqualTo(404);
                    assertThat(body.get("error").asString()).isEqualTo("Not Found");
                    assertThat(body.get("path").asString()).isEqualTo("/missing");
                    assertThat(body.get("requestId").asString()).matches("[0-9a-f]{8}");
                    assertThat(body.has("trace")).isFalse();
                    assertThat(body.has("exception")).isFalse();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Test
        @DisplayName("a browser gets the page with the status")
        void browser() {
            withMvc(mvc -> {
                try {
                    assertErrorPage(perform(mvc, get("/error").header("Accept", BROWSER_ACCEPT)
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)), 403, "Access denied");
                    assertErrorPage(perform(mvc, get("/error").header("Accept", BROWSER_ACCEPT)), 500,
                            "Something went wrong");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Test
        @DisplayName("a page that failed while rendering (response already text/html) gets the page even without Accept: text/html")
        void failedWhileRendering() throws Exception {
            AuthErrorController controller = new AuthErrorController(new DefaultErrorAttributes(),
                    JsonMapper.builder().build());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            request.addHeader("Accept", "*/*");
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET));
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setContentType("text/html;charset=UTF-8");

            ModelAndView page = controller.error(request, response);

            assertThat(page).isNotNull();
            assertThat(page.getViewName()).isEqualTo(ErrorPages.VIEW);
            assertThat(page.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(page.getModel().values()).allSatisfy(value -> assertThat(value).isInstanceOf(String.class));
            assertThat(page.getModel().values()).noneMatch(value -> value.toString().contains(SECRET));
            assertThat(response.getContentAsString()).isEmpty();
        }
    }

    @ParameterizedTest(name = "Accept: {0} -> html={1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8 | true",
            "application/xhtml+xml                                          | true",
            "*/*                                                            | false",
            "NULL                                                           | false",
            "application/json                                               | false",
            "application/json, text/html                                    | false",
            "text/html;q=0.5, application/json                              | false",
            "application/problem+json                                       | false",
            "not a media type                                               | false",
    })
    @DisplayName("HTML only when Accept names it ahead of JSON")
    void acceptsHtmlFirst(String accept, boolean expected) {
        assertThat(ErrorPages.acceptsHtmlFirst(accept)).isEqualTo(expected);
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        TestPages testPages() {
            return new TestPages();
        }

        @Bean
        TestApi testApi() {
            return new TestApi();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        HtmlErrorPageResolver htmlErrorPageResolver() {
            return new HtmlErrorPageResolver();
        }

        @Bean
        AuthErrorController authErrorController(org.springframework.boot.webmvc.error.ErrorAttributes errorAttributes,
                                                tools.jackson.databind.ObjectMapper objectMapper) {
            return new AuthErrorController(errorAttributes, objectMapper);
        }
    }

    @Controller
    static class TestPages {

        @GetMapping("/test/page")
        String page() {
            throw new IllegalStateException(SECRET);
        }

        @GetMapping("/test/page-missing")
        String missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SECRET);
        }
    }

    @RestController
    static class TestApi {

        @GetMapping("/test/api")
        Map<String, String> api() {
            throw new IllegalStateException(SECRET);
        }

        @GetMapping("/test/api-after-html")
        Map<String, String> apiAfterHtml(HttpServletResponse response) {
            response.setContentType("text/html;charset=UTF-8");
            throw new IllegalStateException(SECRET);
        }
    }
}
