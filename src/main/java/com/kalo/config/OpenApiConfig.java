package com.kalo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI kaloOpenApi() {

        return new OpenAPI()
                .info(new Info()
                        .title("KALO Backend API")
                        .version("v1")
                        .description("""
                                KALO is a taxi-company marketplace.

                                The customer searches, picks a taxi **company**
                                from the returned offers, and that company then
                                assigns one of its own drivers. KALO does not
                                operate taxis and does not process payments: the
                                customer pays the driver directly and the final
                                price is the real taximeter amount.

                                Authenticate through `POST /api/v1/auth/login`
                                and send the returned token as
                                `Authorization: Bearer <token>`.
                                """)
                        .contact(new Contact().name("KALO"))
                )
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .name(BEARER_SCHEME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "JWT issued by /api/v1/auth/login"
                                        )
                        )
                )
                .addSecurityItem(
                        new SecurityRequirement()
                                .addList(BEARER_SCHEME)
                )
                .tags(List.of(
                        new Tag()
                                .name("Public")
                                .description(
                                        "Open to anonymous visitors. Read-only: "
                                                + "nothing here creates or changes data."
                                ),
                        new Tag()
                                .name("Authentication")
                                .description("Registration and login. Public."),
                        new Tag()
                                .name("Customer - Rides")
                                .description(
                                        "Taxi search, company selection, ride "
                                                + "tracking, cancellation, history "
                                                + "and rating. Requires CUSTOMER."
                                ),
                        new Tag()
                                .name("Partner - Company")
                                .description(
                                        "Company profile, verification, documents "
                                                + "and operational settings. "
                                                + "Requires PARTNER."
                                ),
                        new Tag()
                                .name("Partner - Fleet")
                                .description(
                                        "Drivers, vehicles, assignments, "
                                                + "availability and GPS. "
                                                + "Requires PARTNER."
                                ),
                        new Tag()
                                .name("Partner - Rides")
                                .description(
                                        "Accept or decline rides and drive the "
                                                + "ride lifecycle. Requires an "
                                                + "APPROVED and ACTIVE company."
                                ),
                        new Tag()
                                .name("Admin")
                                .description(
                                        "Partner verification, users, companies "
                                                + "and rides. Requires ADMIN."
                                )
                ));
    }
}
