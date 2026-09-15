package io.kelta.mcp.resource.docs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one {@link DocResource} bean per {@code docs/authoring/*.md}
 * topic. Each bean implements both {@code UserResource} and {@code
 * AdminResource}, so Spring's {@code List<UserResource>} /
 * {@code List<AdminResource>} autowiring in {@code McpServerConfig} picks it
 * up on both {@code /mcp/user} and {@code /mcp/admin} — metadata authoring
 * reference material is relevant to both toolsets.
 *
 * <p>Adding a new topic: drop the file at {@code docs/authoring/<topic>.md},
 * copy it into {@code src/main/resources/docs/authoring/<topic>.md}, add a
 * {@code @Bean} method here, and update {@code DocResourceTest} — it fails
 * loud if a classpath copy drifts from the repo-root source.
 */
@Configuration
public class DocResourcesConfig {

    @Bean
    public DocResource jsonApiDocResource() {
        return new DocResource("jsonapi",
                "JSON:API conventions: attributes vs relationships, pagination clamp, sparse fieldsets, atomic operations.");
    }

    @Bean
    public DocResource pageLayoutsDocResource() {
        return new DocResource("page-layouts",
                "Authoring page layouts: sections, 0-based columns, header config, related lists.");
    }

    @Bean
    public DocResource listViewsDocResource() {
        return new DocResource("list-views",
                "Authoring list views: filter grammar, row limit set, shared-view deep links.");
    }

    @Bean
    public DocResource dashboardsDocResource() {
        return new DocResource("dashboards",
                "Authoring dashboards: 1-based grid, widget catalogue, operator vocabulary, reportId.");
    }

    @Bean
    public DocResource uiPagesDocResource() {
        return new DocResource("ui-pages",
                "Authoring custom UI pages: config schema, widget catalogue, data bindings, source/row limits.");
    }

    @Bean
    public DocResource uiMenusDocResource() {
        return new DocResource("ui-menus",
                "Authoring UI menus: path grammar, grouping via parentId.");
    }
}
